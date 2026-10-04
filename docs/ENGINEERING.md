# Engineering conventions

How this repository is organized, named, branched, and stored. The rules here are the ones the
existing history already follows, written down so a second contributor does not have to infer them
from the commit log.

## Repository layout

```
truecost/
  src/main/java/com/truecost/   application code, one package per bounded concern
  src/main/resources/           application.yml, Flyway migrations, Avro schemas, static demo UI
  src/test/java/com/truecost/   tests, mirroring the main package tree exactly
  data/seeds/                   reference data CSVs and SOURCES.md, the provenance of every row
  data/osrm/                    prepared routing graph, generated and untracked
  deploy/                       Dockerfile, compose.yaml, k8s manifests, Prometheus, Grafana, Tempo
  docs/                         runbook, design records, and the engineering logs this file indexes
  docs/design/                  one design record per phase, written before the phase was built
  eval/                         k6 load scripts and the golden request fixture
  scripts/                      setup and cluster helpers invoked by make
  .github/workflows/            CI
```

Every package under `com.truecost` maps to a container or component named in the design review
package. `api` holds the endpoint and its DTOs, `aggregate` the fan-out orchestrator, `cache` the
two tier cache, `toll` crossing detection and the strategy engine, `route` the OSRM client,
`provider` the external connectors, `persist` the JDBC repositories, `events` the Avro publisher,
and `stream` the prefetcher deployable.

## Naming conventions

| Thing | Convention | Example |
| --- | --- | --- |
| Java package | lowercase, singular, one concern | `com.truecost.toll.strategy` |
| Java class | PascalCase, noun or noun phrase | `TollStrategyEngine` |
| Unit test | `<ClassUnderTest>Test` | `CrossingDetectorTest` |
| Integration test | `<Concern>IntegrationTest` | `PrefetcherFlowIntegrationTest` |
| Test that pins behaviour | `<Concern>SnapshotTest` or `GoldenRouteTest` | `TripPlanSnapshotTest` |
| Flyway migration | `V<n>__snake_case_description.sql` | `V2__reference_data.sql` |
| Kafka topic | lowercase, hyphenated, plural | `trip-searches` |
| Avro schema | PascalCase matching the record | `SearchRequested.avsc` |
| Seed CSV | lowercase plural noun | `toll_rates.csv` |
| Make target | lowercase, hyphenated verb | `make k6-smoke` |
| Git tag | `v<major>.<minor>.<patch>` plus an optional label | `v0.1.0-baseline` |

`make itest` relies on the integration test suffix, so the naming rule for tests is load bearing
rather than cosmetic.

## Branch strategy

Trunk based development on `main`, with short lived branches for work large enough to be reviewed
as a unit.

- `main` is always buildable. CI runs on every push to every branch and on every pull request.
- A phase from PLAN.md gets its own branch named `phase-<n>-<slug>`, for example
  `phase-2-routing-and-toll-detection`. It merges back with a non fast forward merge so the phase
  boundary stays visible in the history. The four merge commits in the log are those boundaries.
- Small corrections, documentation edits, and UI iteration commit directly to `main`. The history
  shows this pattern after Phase 7, where the work stopped being phase shaped.
- Branches are deleted after merge. Local backup branches prefixed `backup-` are never pushed.

## Commit conventions

One logical change per commit. The subject line is a sentence in the imperative mood describing
what the commit does and, where it is not obvious, why. Two sentences at most, no trailer.

```
Fix cache hit rate stuck on n/a when the stale counter has never registered.
```

## Artifact storage

| Artifact | Where it lives | Tracked |
| --- | --- | --- |
| Source, tests, manifests, seeds | the repository | yes |
| Design records, runbook, engineering logs | `docs/` | yes |
| Smoke and verification evidence | `docs/evidence/` | yes |
| Build output, boot jars | `target/` | no |
| Prepared OSRM graph | `data/osrm/` | no, 9.8 GB generated, rebuilt by `make osrm` |
| k6 result files | `eval/results/` | no |
| Container images | built locally by `make images`, never committed | no |
| Course submission drafts | `docs/submission/` | no, kept local |
| Local assistant config | `CLAUDE.md`, `.claude/` | no |

The rule is that anything a clean clone can regenerate deterministically stays out of the
repository, and anything that records a decision or proves a result stays in. The one deliberate
exception is the OSRM graph, which is regenerable but slow, so `docs/SETUP.md` documents the cost
up front rather than letting a peer discover it mid build.

## Evidence conventions

Smoke test and verification output lands in `docs/evidence/` as plain text, named
`<sprint>-<what>.txt`, for example `sprint-01-verify.txt`. Evidence is committed alongside the
code it describes so that a tagged release carries the proof it was green at that point.
