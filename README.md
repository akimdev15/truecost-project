# TrueCost

TrueCost works out the real cost of a weekend car rental trip out of New York City. You enter an
origin, a destination, travel dates, whether you own a personal E-ZPass, and the base rate each
rental company quoted you. TrueCost adds the toll program fee, route tolls, congestion pricing,
fuel, and hotel to each quote, ranks every option by its true total, and recommends the cheapest.

The core engineering problem is the toll decision. For every rental company it prices your own
E-ZPass, the company's per crossing plan, and the company's unlimited plan for the exact route and
rental length, and picks the cheapest. Every candidate it priced is returned, so the choice can be
checked rather than trusted. This is a master's applied project evaluated on the accuracy of the
totals, response time under parallel fetching with caching, and the correctness of the toll
decision.

## Quick start

| Level | What it proves | Needs | Time |
| --- | --- | --- | --- |
| 1 | The code builds and all 114 tests pass | JDK 21, Docker running | about 2 minutes warm, 10 or more on a first run |
| 2 | The whole system runs and plans a real trip | Level 1 plus about 8 GB of Docker memory and 8 GB of disk for the map | about an hour the first time, almost all of it the map download |
| 3 | A clean restart passes every validation check | Level 2 plus curl, jq, nc, shasum | about 2 minutes |

```
git clone https://github.com/akimdev15/truecost-project.git
cd truecost-project
./mvnw -B verify          # Level 1
make osrm                 # Level 2, one time, downloads and prepares the routing map
make up && make seed      # Level 2, infrastructure in containers and the reference data
make run                  # Level 2, the API on 8080, leave it running
make prefetch             # Level 2, in a second terminal, the prefetcher on 8082
make demo-check           # Level 2, confirms the API, the map, and a real plan request
make clean-run            # Level 3, stop make run and make prefetch first
```

`docs/SETUP.md` walks through each level in detail, with what each step costs and what to do when it
fails. `make up-all` runs the API and prefetcher in containers instead of on the host, but its first
build resolves every Maven dependency inside Docker and takes half an hour or more.

## Prerequisites

| Tool | Version tested | Notes |
| --- | --- | --- |
| JDK | 21, Corretto 21.0.11 | 21 is required. The code uses virtual threads |
| Docker | Docker Desktop, engine 29.8.0 | The daemon must be running for every level, Testcontainers starts real containers |
| Maven | 3.9.9 through `./mvnw` | Not installed separately, the wrapper downloads it |
| make, git, curl, jq | any recent | jq is needed for Level 3 and for reading responses |
| nc, shasum | preinstalled on macOS | Level 3 only |
| k6 | v2.3.0 | Optional, only for `make k6-smoke` and `make baseline` |

Hardware the project was built and measured on. Apple M3 Pro, 18 GB RAM, macOS 26.5.1, with Docker
Desktop allowed 7.65 GB. CI runs Level 1 on GitHub's `ubuntu-latest` runners on every push.

- Docker memory. Level 1 needs about 4 GB. Level 2 needs about 8 GB, because `osrm-extract` peaked
  at 6.3 GB on the clipped map. Docker Desktop sets this under Settings, Resources, Memory.
- Disk. The prepared map is about 4.7 GB, and `osrm-extract` needs temporary space on top. Leave
  at least 8 GB free before `make osrm`. The container images add about 3 GB.
- Level 3 has only been run on macOS. `scripts/validate.sh` reads `sw_vers` and `sysctl`, and
  `scripts/observe-prefetch.sh` uses BSD `date -r`.

## Expected output

Level 1 ends with:

```
[INFO] Tests run: 114, Failures: 0, Errors: 0, Skipped: 0
[INFO] BUILD SUCCESS
```

`make demo-check` prints:

```
api    UP
osrm   UP
plan   HTTP 200
```

A plan request for the sample trip returns six options ranked by `trueTotalCents`, each with its
cost breakdown, the chosen toll strategy and every candidate it beat, and a `provenance` block
saying which inputs were synthetic or fallback values:

```
curl -s localhost:8080/api/v1/trips/plan -H 'Content-Type: application/json' \
  -d @eval/golden/sample-request.json | jq '.recommendation.summary'
```

A trip outside the seeded toll coverage is refused rather than priced:

```
HTTP 422
{"error": "The destination is at (42.3601, -71.0589), which is outside the area where tolls can be priced, so no total is returned.", "code": "OUT_OF_COVERAGE"}
```

`make clean-run` ends with `clean run finished with 0 failing checks` and writes its evidence to
`docs/evidence/hs3/<label>-<timestamp>/`. The `summary.txt` there reads `fails: 0 known: 0 pass:
42`, or `known: 1 pass: 41` when the disk has less than 15 GB free, which check T9 reports as a
known issue rather than a failure.

The demo UI is at `http://localhost:8080/`, Grafana at `localhost:3000`, Prometheus at `9090`.

## Configuration and credentials

Nothing needs a key. Every external input has a documented fallback, and the response marks each
fallback in `provenance`, so a fallback is never shown as a live price.

| Input | Default | To use the live source |
| --- | --- | --- |
| Rental base rates | Entered by the user in `rentalRateOverrides`, otherwise a synthetic estimate | There is no rental API by design. No free licensed rental pricing API exists |
| Fuel price | Fixed fallback of 3.50 a gallon | A free EIA key in `EIA_API_KEY` and `TRUECOST_PROVIDERS_FUEL_EIA_ENABLED=true` |
| Hotel price | Synthetic estimate | Amadeus test environment credentials in `AMADEUS_CLIENT_ID` and `AMADEUS_CLIENT_SECRET`, plus its enabled flag |
| Routing | Self hosted OSRM on `localhost:5001` | Already live once `make osrm` has run |

Every variable is listed in `.env.example` with its default and what happens when it is unset.
Copy it to `.env`, which git ignores, and export it into the shell before starting the API, because
nothing loads it automatically:

```
cp .env.example .env
set -a && . ./.env && set +a
make run
```

The EIA API is free with a key and returns a weekly price. The Amadeus test environment is free but
returns limited test data, not production hotel prices.

## Data

| Data | Where it comes from | In the repository |
| --- | --- | --- |
| Road network | OpenStreetMap, the Geofabrik `us-northeast` extract, clipped to a New York metro box by `make osrm` | No. About 4.7 GB prepared, and rebuilt from the source on each machine |
| Toll rates, crossings, congestion pricing | Published agency tariffs, PANYNJ, MTA, NJTA, NYSTA, and the PA Turnpike, dated in `data/seeds/SOURCES.md` | Yes, `data/seeds/*.csv` |
| Rental toll program terms | Each company's published terms, dated in `SOURCES.md` | Yes, `data/seeds/toll_programs.csv` |
| Coverage area | Rectangles where every tolled facility is seeded | Yes, `data/seeds/coverage_regions.csv` |
| Synthetic rental calibration | Provisional placeholder figures, not real rates, used only when no quote is entered so tests stay deterministic | Yes, `data/seeds/rental_calibration.csv` |
| Golden accuracy scenarios | Collected by hand, one capture day per scenario | Drafts only, `eval/golden/scenarios/`, every price still empty |

The map is OpenStreetMap data, copyright OpenStreetMap contributors, available under the Open
Database License. It is not committed because of its size, and anything derived from it must keep
that attribution. Geofabrik replaces its extract every day, so two machines can build slightly
different maps. `make osrm` prints the sha256 of the extract it used, and every validation run
records it, so a result can be matched to its map. Live rental and hotel quotes collected for the
golden set are personal quotes under each provider's terms. Only the numbers and their sources are
kept, never the pages themselves.

## Testing and validation

```
make test            # unit and Testcontainers integration tests
make clean-run       # wipe every store, reseed, start both jars, run all 42 validation checks
make validate        # the same checks against a stack that is already running
make sweep           # price 84 trips through the API and recheck every fee from the seed file
make eval-selftest   # check the accuracy scoring math, no stack needed
make eval            # score the complete golden scenarios against the running API
make baseline        # cold, warm, and skewed latency runs and the cold start test
make audit           # check every doc link, make target, script, and config variable resolves
```

The tests start their own Postgres, Redis, Kafka, and Apicurio and stub every outbound provider
with WireMock, so they need Docker but not the compose stack. The acceptance tests pin the toll
strategy engine against an independent oracle, pin a golden trip response, and prove the cache
singleflight and the prefetcher. One caveat. `StrategyOracle` copies the engine's `NO_ARRANGEMENT`
rule, so that part of the agreement is not independent, I-03.

## Troubleshooting

| Symptom | Cause | Fix |
| --- | --- | --- |
| Tests fail with `Could not find a valid Docker environment` | Docker is not running | Start Docker Desktop and wait for `docker info` to succeed |
| `make osrm` stops with no error message | `osrm-extract` ran out of Docker memory | Raise Docker memory to 8 GB, stop other containers, rerun `make osrm`, it resumes |
| `make osrm` takes hours | The 1.8 GB Geofabrik download is slow | Leave it running. If it stops, rerun `make osrm` and it resumes the same day's file |
| Grafana will not start, or validation check T3 fails | Something else holds port 3000 | Stop that process, then `docker compose -f deploy/compose.yaml up -d grafana` |
| OSRM fails to bind | Port 5000 is taken by macOS AirPlay | Already avoided, OSRM uses 5001. Check nothing else holds 5001 |
| A plan returns 422 `OUT_OF_COVERAGE` | The trip leaves the area whose tolls are seeded | Expected. Boston and Washington DC are outside it |
| A key in `.env` has no effect | Nothing loads `.env` | `set -a && . ./.env && set +a` in the shell that runs `make run` |
| The first `make up-all` takes half an hour | Maven resolves every dependency inside the image build | Use `make up`, `make seed`, `make run`, and `make prefetch` instead |
| `make clean-run` fails check D0 | A store was not empty after the teardown | Read `clean-state.txt` in the run directory. It names what survived |

`docs/RUNBOOK.md` has the commands to inspect and debug every subsystem, and
`docs/KNOWN_ISSUES.md` lists what is limited today, with workarounds.

## Status

Phases 0 to 8 are built, plus out of bounds rejection, the accuracy runner, and image publishing
from CI. Each row cites the validation check behind it, from `scripts/validate.sh`.

| Area | State | Evidence |
| --- | --- | --- |
| Build and test suite | Works | 114 tests across 29 classes, Linux CI and macOS |
| Clean restart from the CSVs | Works | `make clean-run`, check D0 fails the run if any store survives the teardown |
| Reference data load | Works | D1 to D9, every table matches its CSV |
| Cost arithmetic, ranking, and determinism | Works | A1 to A9 |
| User supplied base rates | Works | A13 and A14 |
| Input validation | Works | A10, an empty request returns 400 naming every missing field |
| Out of bounds rejection | Works | A12, 422 OUT_OF_COVERAGE |
| Fallback marking | Works | A15, provenance, freshness, and UI badges agree |
| Kafka publish, schema registry, and hot route prefetch | Works | K1 to K4, and `scripts/observe-prefetch.sh` |
| Metrics and tracing | Works | M1, M2, O1, O2 |
| Accuracy against real costs | Not measured | the runner works, the golden set has 0 of 20 complete scenarios, I-02 |
| Latency under load | Not measured | `make baseline` is built, no run recorded yet, R9 |

`PLAN.md` is the phase plan, `CHANGELOG.md` is what shipped, and where they disagree the changelog
is right.

## Documentation

| Document | What it is for |
| --- | --- |
| `docs/SETUP.md` | clean clone setup, level by level |
| `.env.example` | every environment variable, its default, and what happens unset |
| `docs/ARCHITECTURE.md` | how the code maps to the architecture, package by package |
| `docs/API.md` | the endpoint contract with real payloads, and the fallback behaviour |
| `docs/RUNBOOK.md` | commands to run, inspect, and debug each subsystem |
| `docs/ENGINEERING.md` | layout, naming, branch strategy, and where artifacts live |
| `docs/RISK_LOG.md` | risks and issues with severity, owner, and status |
| `docs/KNOWN_ISSUES.md` | what is limited today, with workarounds |
| `data/seeds/SOURCES.md` | where every reference data number came from, and when |
| `CHANGELOG.md` | what shipped and when |
| `docs/design/` | one design record per phase, written before it was built |
