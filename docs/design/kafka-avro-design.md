# Kafka Streams and Avro design

This document records the architecture decisions for how TrueCost uses Avro with Kafka and Kafka Streams. It is written so it can be lifted into the thesis architecture chapter. The prose follows the project code standard and contains no em dashes, no en dashes, and no semicolons.

## Scope and context

TrueCost publishes a search event per user trip search, keyed by a route key. A Kafka Streams topology windows and counts these events per route and date bucket, emits routes that exceed a threshold to a hot routes topic, and a prefetch consumer refreshes Redis from those signals ahead of TTL expiry. A second path captures every fetched rental quote into a quote snapshot table in Postgres for the thesis accuracy analysis. The stack is fully self hosted. It runs in Docker Compose locally and later in a kind Kubernetes cluster. It never uses Confluent Cloud.

The events are Avro encoded. Avro was chosen because it gives a compact binary encoding, a machine readable schema, and first class schema evolution with a registry enforced compatibility gate, which is exactly the production discipline the thesis wants to demonstrate.

## Decision 1, schema registry

Choice, Apicurio Registry.

Apicurio Registry is licensed Apache 2.0, so it is open source with no field of use restriction. The Confluent Community Schema Registry is free to run internally but ships under the Confluent Community License, which is not an OSI approved open source license and carries a clause against offering it as a competing hosted service. That clause is irrelevant to this project in practice, but for a thesis that argues a fully self hosted and fully open stack, an Apache 2.0 registry removes a licensing footnote and keeps the argument clean. Apicurio is production proven, is backed by Red Hat, supports Avro, and can emit the Confluent compatible wire format, so the industry standard serialization ecosystem stays available. The trade against Confluent Community is slightly less ubiquitous documentation for the Spring Kafka path, which is a minor cost against a clear licensing and openness win.

Storage backend. Apicurio Registry supports in memory, Kafka backed, and SQL backed storage. Local Docker Compose uses the in memory storage for simplicity, since the .avsc files in the repository are the real source of truth for the schemas and the application auto registers them on startup, so the registry is a runtime compatibility gate rather than a system of record. The kind and production overlay switches the registry to SQL storage backed by the Postgres instance the stack already runs, which demonstrates the durable production pattern and reuses infrastructure that is already present.

## Decision 2, schema evolution and compatibility mode

Choice, BACKWARD compatibility set at the registry global level, with an option to tighten the quote snapshots subject to BACKWARD_TRANSITIVE.

There is no live production traffic to protect in a single developer project, so NONE would technically work. It is rejected because the thesis needs to show the correct production pattern, and a registry with compatibility disabled shows nothing. BACKWARD is the industry default and the most defensible teaching choice. Under BACKWARD a consumer using the new schema can read data written with the old schema, which supports the standard rollout order of upgrading consumers first and then producers. It is the natural fit for this system where the same application both produces and consumes, and where the quote snapshot history accumulates over time and must stay readable by an evolved reader.

The practical rule that BACKWARD imposes on future changes is simple. A new field may be added only if it carries a default, and a field may be removed only if it had a default. The schemas are designed with that discipline in mind. Optional fields use a union of null and the value type with a null default, and boolean flags carry an explicit default, so the obvious next changes remain compatible without contortions.

The quote snapshots subject is the one place where records are retained for a long time and later read in bulk for analysis, so a new reader may need to read every historical version rather than only the immediately previous one. BACKWARD_TRANSITIVE, which checks a new schema against all prior versions rather than just the last, is the stricter and more correct mode for that subject. Setting it per subject is optional and is called out here so the implementer can apply it when the schema first evolves.

Compatibility is enforced by the registry. The build should also fail fast on an incompatible change rather than discovering it at runtime, so the Avro plugin generates the classes and a registry compatibility check runs in the deploy pipeline before publish.

## Decision 3, the Avro schemas

Namespace convention, com.truecost.events. All event records live in this namespace. The three schema files are under src/main/avro and are the source of truth.

- src/main/avro/SearchRequested.avsc
- src/main/avro/HotRouteSignal.avsc
- src/main/avro/QuoteSnapshot.avsc

Money representation. Every monetary field in the events is a long count of whole cents, matching the domain Money value type. Avro decimal is deliberately not used. The reasons are consistency with the rest of the system, which never uses floating point in pricing, and simplicity, since Avro decimal is a bytes plus scale logical type that complicates code generation and inspection for no benefit when the domain already standardizes on long cents.

Logical types. Instant fields use the timestamp-millis logical type so they map to java.time.Instant in generated code and remain human readable in tooling. Identifier fields use the uuid logical type. Latitude and longitude are plain doubles in decimal degrees, which is the correct type for coordinates and is unrelated to money.

Field design rationale.

SearchRequested carries the raw search inputs, not only the derived key, because the prefetch consumer must be able to reconstruct the fetch. It includes the route key and date bucket used for grouping, the origin and destination coordinates, the departure and return instants, the personal E-ZPass flag, an optional car class, an event id for tracing, and occurredAt as the event time used for windowing. Departure time is required because toll pricing is deterministic per route, vehicle class, and departure time bucket, so prefetch cannot warm tolls without it.

HotRouteSignal is designed to make the prefetch consumer self contained. Rather than emitting only a route key and count and forcing the consumer to look route parameters up elsewhere, it carries a representative set of route parameters, the coordinates, departure and return instants, and optional car class, alongside the window bounds, the search count, and the threshold that was exceeded. The representative parameters come from the searches aggregated in the window. A route key groups many searches whose exact departure times may differ within one date bucket, so the departure instant is representative rather than exact, which is acceptable because toll pricing is bucketed by a time of day window during prefetch. The window bounds, count, and threshold are carried for observability and for thesis reporting.

QuoteSnapshot captures one fetched rental quote for the accuracy analysis. It carries a snapshot id, the route key, the exact endpoint coordinates so analysis can join back to a precise origin and destination since the route key geohash is lossy, the company, car class, and source provider so synthetic and live data can be separated, the base rate, taxes and fees, and total rental in cents, the pickup and return instants, the rental day count so accuracy can be reported by trip length, a stale flag indicating whether the quote came from a stale while revalidate read, and fetchedAt as the recorded timestamp. The total here is the rental quote total, not the full trip true total, because a snapshot is a raw fetched quote and not a computed trip option.

## Decision 3b, topic design

Key type, plain string route key with the string serializer, not an Avro key.

The message key is a single opaque route key string. Wrapping one string in Avro would add a second schema subject and wire overhead for no benefit. A string key keeps Kafka Streams co partitioning and repartitioning simple, avoids a pointless key subject under the subject naming strategy, and stays human readable when tailing a topic with kcat, which the runbook relies on. Values are Avro.

Topics.

| Topic | Value schema | Key | Partitions local | Replication local | Cleanup policy | Retention local |
| --- | --- | --- | --- | --- | --- | --- |
| trip-searches | SearchRequested | route key string | 3 | 1 | delete | 1 day |
| hot-routes | HotRouteSignal | route key string | 1 | 1 | delete | 1 day |
| quote-snapshots | QuoteSnapshot | route key string | 3 | 1 | delete | 7 days |

Partition rationale. On a single node local broker one partition would run, but three partitions on trip-searches and quote-snapshots let the topology and consumers show real task parallelism and match the shape the kind cluster will use, so there is no repartition surprise later. hot-routes is low volume and its consumer is the prefetch worker, so a single partition keeps ordering trivial and is sufficient. Replication factor is 1 locally because there is one broker. The kind overlay raises replication to 3 against a three broker set and this is documented rather than hardcoded.

Retention and cleanup rationale. All three topics use the delete cleanup policy. trip-searches and hot-routes are transient signals, so a one day retention gives generous replay headroom for development and reprocessing while staying cheap. quote-snapshots gets seven days so the sink consumer can be down for a while without losing data, while Postgres remains the permanent system of record for snapshots. Compaction is not used on any of these, because each topic carries a stream of distinct time stamped events rather than a keyed latest value.

Kafka Streams internal topics. The windowed count materializes a state store, and Kafka Streams creates its own changelog and repartition topics automatically. Their replication factor follows the streams replication.factor setting, which is 1 locally and 3 in the kind overlay. The windowed store retention must be at least the window size plus the grace period, so Phase 6 sets the store retention and a small grace period, for example one minute, so late events inside grace are still counted. These are not manually declared topics.

Quote snapshot sinking mechanism. The snapshot capture does not use Kafka Connect and does not use a Kafka Streams to Postgres processor. The recommended and simplest correct mechanism, given the application already has a JDBC datasource, is a dedicated Spring Kafka listener that consumes the quote-snapshots topic and batch inserts rows into the quote_snapshot table through the existing repository. Every fetched rental quote, whether fetched on the synchronous path or by the prefetch consumer, publishes a QuoteSnapshot event to the quote-snapshots topic. Routing snapshots through a topic rather than inserting inline keeps Postgres writes off the synchronous request hot path, buffers write bursts under load, which is precisely the heavy load story the thesis tells, and captures quotes uniformly regardless of which path fetched them. This refines the wording in the Phase 6 plan, where the snapshot sink is described as a second branch. The quote data does not flow through the search counting topology, so the snapshot sink is cleaner as a separate topic with its own consumer than as a literal branch of the search topology. Kafka Connect is not worth the operational overhead for a single developer thesis project, and a single listener over the existing datasource is both simpler and fully sufficient.

## Decision 4, Gradle build integration

Avro code generation plugin, com.github.davidmc24.gradle.avro.plugin. This is the maintained community Gradle Avro plugin. It reads .avsc files from src/main/avro by convention, generates Java into build/generated-main-avro-java, and wires that directory into the main source set automatically, so no manual source set surgery is needed. Recommended plugin configuration, stringType set to String so string fields generate as java.lang.String rather than CharSequence, fieldVisibility set to PRIVATE, and setters disabled so generated event classes are constructed through their builders. Decimal logical type support is not enabled because the events use long cents.

Serializer library, Apicurio Avro serdes, artifact io.apicurio:apicurio-registry-serdes-avro-serde, which is Apache 2.0. Using the Apicurio serdes rather than the Confluent serializer keeps the entire path Apache 2.0, which is consistent with the reason for choosing the Apicurio registry in the first place. The serdes are configured to auto register schemas in development and to emit the Confluent compatible wire format, so the on the wire bytes use the widely recognized magic byte plus schema id layout and future interop with Confluent based tooling is preserved without pulling a Confluent licensed jar. The subject naming strategy is the topic name strategy, so subjects are trip-searches-value, hot-routes-value, and quote-snapshots-value, and there are no key subjects because keys are strings.

Spring Kafka wiring. Producers and the plain quote snapshot consumer set the Apicurio Avro serializer and deserializer as the value serde through Spring Kafka properties, with the registry url and auto register and confluent id handler options passed in the producer and consumer property maps. Kafka Streams uses a serde built from the same Apicurio serializer and deserializer as the default value serde, with the string serde for keys. The exact Apicurio SerdeConfig property constant names should be confirmed against the pinned Apicurio version at implementation time, since they have shifted across Apicurio major versions. If the Streams serde wiring proves awkward against the pinned version, the documented fallback is to use the Confluent Avro serdes pointed at the Apicurio Confluent compatible endpoint, which changes the serde classes but not the schemas, the topics, or the wire format.

Auto registration and the production pattern. Auto registration is enabled for local development so the loop stays frictionless. The production and kind pattern registers schemas explicitly from the deploy pipeline with auto registration disabled, which is the correct production discipline and is worth stating in the thesis. The .avsc files remain the source of truth in both modes.

## Decision 5, what Phase 0 needs

Recommendation, add the Apicurio Registry container to the Phase 0 Docker Compose now, and wire the Avro Gradle plugin and src/main/avro in Phase 0.

Phase 0 already stands up Postgres and Redis even though they are not exercised until later phases, precisely so the compose stack is set up in one pass and does not churn as phases land. The schema registry belongs in that same one pass setup for the same reason. Adding the codegen plugin and the src/main/avro directory in Phase 0 keeps the build shape stable, lets the three .avsc files generate their Java from the start even though the topology that uses them arrives in Phase 6, and it lets the Phase 1 quote_snapshot table design line up field for field with the QuoteSnapshot event. The cost of adding the container early is one more service in compose and a small amount of memory, which is negligible against avoiding a later compose and build change. Phase 0 only stands up the registry and the codegen plugin. The topology, the topic declarations, the serdes, and the snapshot consumer are Phase 6 work.

Fixed local port for the registry, 8081, which is free in the existing port map.

## Where topic and stream configuration lives

Topics are declared in code with sensible local defaults, as the plan requires. The recommended layout for Phase 0 and Phase 6 to consume programmatically is as follows.

- Topic parameters, the names, partition counts, replication factor, and retention, live as configuration properties in src/main/resources/application.yml under a truecost.kafka.topics namespace, so the dev profile and the kind profile can differ by overriding properties rather than by changing code.
- A configuration properties record binds that namespace, and a Spring configuration class, recommended path src/main/java/com/truecost/stream/TopicsConfig.java, exposes one NewTopic bean per topic built from those properties. Spring KafkaAdmin then creates the topics on startup. This is the idiomatic Spring way to declare topics in code and it keeps the numbers out of the compiled classes.
- Stream tuning, the window size, hop, grace period, hotness threshold, and prefetch lead time, lives under a truecost.stream namespace in the same application.yml and is bound by its own properties record, so the thesis can vary these without recompiling.

## Summary of choices

- Registry, Apicurio Registry, Apache 2.0, in memory storage locally and Postgres backed storage in kind.
- Compatibility, BACKWARD globally, with BACKWARD_TRANSITIVE available for the quote snapshots subject.
- Schemas, three records in com.truecost.events, long cents for money, timestamp-millis and uuid logical types, optional fields defaulted for safe evolution.
- Topics, trip-searches, hot-routes, and quote-snapshots, string route key, Avro values, delete cleanup, replication 1 locally, quote snapshots sunk to Postgres by a plain Spring Kafka listener over the existing datasource rather than Kafka Connect.
- Build, com.github.davidmc24.gradle.avro.plugin over src/main/avro, Apicurio Apache 2.0 Avro serdes emitting the Confluent compatible wire format.
- Phase 0, registry container and Avro plugin added now for a one pass setup, topology deferred to Phase 6.
