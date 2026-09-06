# TrueCost

A real-time, all-in cost planner for weekend car rental trips out of New York City.

You enter an origin, a destination, travel dates, whether you own a personal E-ZPass, and the base rate each rental company quoted you. TrueCost computes the true total for every option, base rate, route tolls, congestion pricing, fuel, and hotel, and decides per option whether your own E-ZPass, the company per-crossing fee, or the company unlimited toll plan is cheapest for that exact route and rental length. Every option is ranked by true total and the cheapest is recommended.

Base rates are user-supplied because no free licensed rental pricing API exists, and the alternatives were an unofficial third-party connector or scraping. Everything else, route tolls, congestion pricing, toll program terms, fuel, and hotel, is fetched or computed from a real source.

## Tech stack

Java 21 (virtual threads), Spring Boot, Kafka Streams, PostgreSQL, Redis with Caffeine, self-hosted OSRM routing, Docker Compose, Kubernetes, and Prometheus with Grafana for metrics.

## Prerequisites

- Docker Desktop, running
- The bundled Maven wrapper (`./mvnw`), no separate Maven install needed
- Optional, `k6` for the load test (`brew install k6`)

## Quick start

Bring up the whole system in containers, this builds the images on first run:

```
make up-all
```

Open the app at **http://localhost:8080**.

Routing needs a one-time data preparation (a large download, several minutes). Trip planning returns real routes and tolls only after this finishes:

```
make osrm
```

Then restart the stack:

```
make down-all && make up-all
```

Tear everything down when done:

```
make down-all
```

## Run the tests

The suite is self-contained through Testcontainers, it starts its own PostgreSQL and Redis in Docker, so it does not need the stack up:

```
make test
```

## Useful commands

| Command | What it does |
| --- | --- |
| `make up-all` | Start everything in containers |
| `make down-all` | Stop everything and remove volumes |
| `make test` | Run the full test suite |
| `make demo-check` | Confirm the API, OSRM, and a plan request are healthy |
| `make demo-load` | Drive load so the cache and singleflight metrics climb live |
| `make logs` | Follow the API and prefetcher logs |

Once the stack is up, Grafana is at http://localhost:3000 and Prometheus at http://localhost:9090.

## Project structure

The code is organized by feature. It ships as two runnable services from one codebase.

```
src/main/java/com/truecost/
  TrueCostApplication.java   the synchronous API service
  api/          REST controller and request and response records
  aggregate/    TripPlanner, the orchestrator that fans out and ranks options
  toll/         crossing detection and congestion pricing
    strategy/   TollStrategyEngine, the pure-function toll decision engine
  route/        OSRM routing client
  provider/     rental, fuel, and hotel data sources
  cache/        two-tier cache, singleflight, and cache keys
  persist/      repositories over PostgreSQL
  stream/       PrefetcherApplication, the Kafka Streams prefetcher service
  events/  domain/  seed/

src/main/resources/   application config, Flyway migrations, and the web UI
data/seeds/           real toll and congestion rates, cited in SOURCES.md
src/test/             the automated test suite (Testcontainers)
deploy/               Docker Compose, Kubernetes, and observability config
docs/design/          one design document per component
```

## How it works

- A request fans out to routing, tolls, and the rental, fuel, and hotel providers at once on virtual threads, so the wall time is the slowest single call, not the sum.
- Results flow through a two-tier cache, Caffeine then Redis. A keyed singleflight collapses many concurrent identical requests onto a single upstream fetch.
- A pure, deterministic toll strategy engine picks the cheapest toll arrangement per option, verified against a brute-force oracle across thousands of scenarios.
