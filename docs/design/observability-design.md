# Observability design, Phase 7

This document records the Phase 7 decisions, metrics with Prometheus and Grafana and distributed
tracing with OpenTelemetry. It follows the project code standard, no em dashes, no en dashes, and no
semicolons. The tracing architecture decisions are the Opus pass PLAN.md reserves for this phase.

## Metrics

Micrometer exports to Prometheus through the actuator prometheus endpoint, scraped every five
seconds. HTTP server request timers carry a percentile histogram so Prometheus computes a real p95
per endpoint rather than a client side quantile. Every metric carries an application tag so one
Prometheus and one Grafana serve both deployables side by side.

The metric inventory, most of it already emitted before this phase.

- HTTP server request latency and count, per endpoint and status, from the Spring Boot web
  instrumentation, histogram enabled.
- Provider call latency, `truecost.provider.call.latency`, a timer tagged provider and outcome,
  present, absent, or circuit_open, from ResilientHttpClient.
- Circuit breaker state, `resilience4j_circuitbreaker_state`, from the resilience4j actuator binding.
- Cache effectiveness, `truecost.cache.request` tagged class and outcome, l1_hit, l2_hit, miss, or
  stale, plus `truecost.cache.singleflight.coalesced`, `truecost.cache.stale.served`, and
  `truecost.cache.refresh`, all from the two tier cache.
- The business metric added this phase, `truecost.strategy.chosen`, a counter tagged kind, the
  winning toll strategy per priced option, so the thesis can show the choice distribution shift
  across the Phase 9 matrix.
- Kafka client and Kafka Streams metrics from the Micrometer bindings on the prefetcher.

Grafana loads four provisioned dashboards from deploy/grafana, API overview, providers, cache
effectiveness, and Kafka pipeline, and one provisioned Prometheus and one Tempo datasource. Three
alert rules live in deploy/prometheus/alerts.yml, request p95 above 300 milliseconds, any circuit
breaker open, and cache hit rate below half over five minutes.

## Tracing, decision 1, backend

Choice, Grafana Tempo. Tempo is Apache 2.0, integrates natively with the Grafana and Prometheus
stack this phase already stands up, and speaks OTLP, so the application exports over the standard
protocol with no vendor lock. Jaeger would also work and is a reasonable alternative, but Tempo
reuses the Grafana datasource and trace to logs correlation already present and keeps the stack to
one visualization surface, which is the cleaner story for a self hosted thesis. Tempo runs as a
single binary locally with local block storage, and the kind overlay would repoint it at object
storage.

## Tracing, decision 2, sampling

Choice, parent based sampler over a ratio. Development samples at probability 1.0 so every trace is
complete, set in the dev profile. Production would lower the ratio, and the parent based sampler is
what matters there, once the API samples a trace the sampling decision rides in the propagated
context so the prefetcher records the same trace rather than making an independent coin flip. That
is what keeps a trace whole across the Kafka message boundary. The default profile samples at 0.0 so
tests and a plain run emit no spans and need no backend.

## Tracing, decision 3, propagation and the message boundary

Micrometer Tracing over the OpenTelemetry bridge propagates the W3C traceparent. Across HTTP it
rides the request headers. Across Kafka it rides the message headers, which is why
spring.kafka.template.observation-enabled and spring.kafka.listener.observation-enabled are on, so
the producer writes the trace context into the SearchRequested and QuoteSnapshot records' headers
and the prefetcher's listener continues the same trace. This is the genuine distributed part, a
single trace spans the synchronous API request, the asynchronous hop through the broker, and the
prefetcher's own fan out, all real process and network boundaries without a wider microservices
split.

## Tracing, decision 4, log correlation

Micrometer Tracing puts traceId and spanId in the SLF4J MDC. The dev log pattern prints them in a
bracketed pair per line, and the prod LogstashEncoder emits the whole MDC as JSON fields, so every
log line carries its trace id and Grafana pivots from a Tempo span to its logs by that id. The
correlation format is the raw traceId and spanId, no custom envelope.

## Tracing, decision 5, instrumentation boundaries

Spring Boot observation instruments Redis through Lettuce, Postgres through the datasource
micrometer binding, and Kafka through the producer and listener observation, so those three real
dependencies produce spans automatically and show in the waterfall with no code change.

The two outbound HTTP clients, RouteClient calling OSRM and ResilientHttpClient calling the rental,
fuel, and hotel providers, use java.net.http.HttpClient directly rather than a RestClient or
WebClient, which Boot observation does not auto instrument, so each wraps its outbound call in an
io.micrometer.observation.Observation. ResilientHttpClient opens a provider.call span tagged by
provider around the whole circuit breaker guarded call, so the span covers the retry and reflects
the real time the provider took, and RouteClient opens an osrm.route span around the OSRM fetch,
which records the exception on the span when OSRM is unreachable. With these in place the waterfall
shows every real dependency, the provider fan out, OSRM, Redis, Postgres, and the Kafka hop, so a
single slow request under load points at the exact dependency that accounted for the latency, which
is the Phase 7 acceptance for tracing.
