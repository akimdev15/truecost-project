# Setup, from a clean clone

Written so someone who has never seen this project can get it running without asking a question.
There are two levels. Level 1 proves the code is correct and takes about two minutes. Level 2 runs
the whole system against real routing data and takes about an hour, almost all of it an unattended
map download and graph build.

Start with Level 1. It needs no API keys, no map data, and no running stack.

## Prerequisites

| Tool | Version used here | Check | Notes |
| --- | --- | --- | --- |
| JDK | 21 (Corretto 21.0.11) | `java -version` | 21 is required, the build sets release 21 and the code uses virtual threads |
| Docker | 29.8.0 | `docker info` | The daemon must be running, Testcontainers starts real containers |
| Git | any recent | `git --version` | |
| make | any recent | `make --version` | preinstalled on macOS and Linux |
| curl and jq | any recent | `jq --version` | required for Level 3, otherwise only for manual request checks |
| nc and shasum | preinstalled on macOS | `which nc shasum` | required for Level 3 |
| k6 | v2.3.0 | `k6 version` | only for the load smoke, optional |

Maven is not in the list on purpose. The repository ships the Maven wrapper, so `./mvnw` downloads
the right Maven itself.

Docker memory matters. Level 1 needs about 4 GB available to Docker. Level 2 needs about 8 GB, and
the map preparation step is the reason. On Docker Desktop this is Settings, Resources, Memory.

Disk matters too, and it was missing from this list until Hard Stop 3. The routing data takes
about 4.7 GB once prepared, 1.7 GB for the source extract, 0.6 GB for the clipped extract, and
2.4 GB for the prepared graph, and `osrm-extract` needs temporary space on top of that while it
runs. Leave at least 8 GB free before running `make osrm`.

## Level 1, prove it builds and the tests pass

```
git clone https://github.com/akimdev15/truecost-project.git
cd truecost-project
./mvnw -B verify
```

That is the whole thing. `verify` compiles both deployables, generates the Avro classes, runs all
89 tests, and repackages the two boot jars. The integration tests start their own Postgres, Redis,
Kafka, and Apicurio through Testcontainers and stub every outbound HTTP provider with WireMock, so
nothing external is required and no credentials are involved.

Expected ending:

```
[INFO] Tests run: 89, Failures: 0, Errors: 0, Skipped: 0
[INFO] BUILD SUCCESS
```

The first run is slower because Maven downloads dependencies and Docker pulls the Testcontainers
images. A warm run finishes in under a minute.

## Level 2, run the whole system

### Step 1, prepare the routing data

```
make osrm
```

This is the long step and the only one worth reading about before you start it. It downloads the
Geofabrik us-northeast OpenStreetMap extract, close to 2 GB, clips it to a New York metro bounding
box, and then runs the three stage OSRM pipeline, `osrm-extract`, `osrm-partition`, and
`osrm-customize`. It is safe to rerun, every stage is skipped if its output already exists, and
`--force` redoes everything.

Two things to know.

The clip is deliberate. The full us-northeast extract needs more memory in the `osrm-extract` edge
expansion stage than a typical laptop Docker allocation has, and when it runs out the container is
killed with no error message at all. The clipped box covers New York City, all of New Jersey,
Connecticut, the Hudson Valley up through New Paltz, and Long Island to Montauk, which contains
every route the seeded toll data can price. If you have the memory and want the full extract, run
`TRUECOST_OSRM_EXTENT=full make osrm` and point the `osrm` service command in
`deploy/compose.yaml` at `/data/us-northeast-latest.osrm`.

If `osrm-extract` is killed anyway, stop the application containers first and rerun, they compete
for the same memory:

```
docker compose -f deploy/compose.yaml --profile app stop
make osrm
```

The script prints and saves a sha256 of the map file it prepared. Record that number alongside any
accuracy measurement, because a different map produces different routes and therefore different
detected toll crossings.

### Step 2, start everything

```
make up-all
```

This builds the API and prefetcher images and starts eleven containers, Postgres, Redis, Kafka,
Apicurio, OSRM, Prometheus, Tempo, Grafana, the seed job, the API, and the prefetcher. The seed job
loads the reference data CSVs from `data/seeds` into Postgres and exits, the API waits for it.

The first `make up-all` is slow, budget half an hour or more. The Dockerfile resolves the whole
Maven dependency tree inside the build container, and that download does not reuse your host
Maven cache. Later runs reuse the Docker layer cache and are fast. If you would rather not wait,
run the infrastructure in containers and the two processes on the host:

```
make up
make seed
make run        # in one terminal, the API on 8080
make prefetch   # in another, the prefetcher on 8082
```

That is the same code against the same containers, and it is the path `make smoke` checks.

### Step 3, confirm it is alive

```
make demo-check
```

Expected:

```
api    UP
osrm   UP
plan   HTTP 200
```

Then open `http://localhost:8080/` for the demo UI, or send the golden request yourself:

```
curl -s localhost:8080/api/v1/trips/plan \
  -H 'Content-Type: application/json' \
  -d @eval/golden/sample-request.json | jq
```

## Level 3, validate a clean restart

Level 3 needs Level 2's prepared map in `data/osrm` and the jq, nc, and shasum tools above. It
has only been run on macOS. The environment snapshot uses `sw_vers` and `sysctl`, and
`observe-prefetch.sh` uses BSD `date -r`, so on Linux those parts will need changing.

```
./mvnw -B verify       # builds the two jars the run uses
make clean-run         # wipes every store, rebuilds from the CSVs, starts both jars, validates
```

`scripts/clean-run.sh` tears the compose stack down with its volumes, records that Postgres,
Redis, Kafka, and Apicurio really are empty, seeds from the CSVs, starts the prefetcher and the
API from the built jars, and runs `scripts/validate.sh`. Each run writes its own directory under
`docs/evidence/hs3/`, which is ignored by git, with an environment snapshot naming the jar, image, map, and seed checksums,
every check result, and the logs. It takes under a minute. Exit status is the number of failing
checks, and a check that reproduces a documented issue is reported as KNOWN rather than as a pass.

The teardown does not touch the prepared OSRM graph, which is a bind mount and survives on
purpose. The run checksums both the clipped extract and the prepared graph instead. The
prefetcher's Kafka Streams state lives in the OS temp directory, outside every volume, so the
script deletes it explicitly. Before it did, hot route counts carried from one run into the
next, I-16.

To watch the hot route prefetch happen live, which takes about five minutes because a signal is
only emitted once its window closes:

```
KEEP_RUNNING=true scripts/clean-run.sh
scripts/observe-prefetch.sh docs/evidence/hs3/<the run directory it printed>
```

## Ports

| Port | Service |
| --- | --- |
| 8080 | API and demo UI |
| 8082 | prefetcher actuator |
| 5432 | Postgres |
| 6379 | Redis |
| 9092 | Kafka |
| 8081 | Apicurio registry |
| 5001 | OSRM, 5001 rather than 5000 because macOS AirPlay Receiver binds 5000 |
| 9090 | Prometheus |
| 3000 | Grafana |
| 3200 | Tempo |

## Credentials and external services

There are none to configure. This is a deliberate scope decision rather than an omission.

- Rental base rates are user supplied input, because no free licensed rental pricing API exists and
  the alternatives were an unofficial connector or scraping, neither defensible for a thesis
  accuracy claim. Absent an override the synthetic provider calibrated from
  `data/seeds/rental_calibration.csv` fills in.
- Fuel falls back to a documented static price when no EIA key is set.
- Hotel falls back to its synthetic path when no Amadeus key is set.

Every one of these paths is marked in the response `provenance` block, so a fallback is visible
rather than blended in. The `freshness` block agrees, fuel on its fallback and a synthetic hotel
both report UNAVAILABLE, I-18.

## Shutting down

```
make down-all     # stops everything and removes volumes
```

`make down` stops the stack but keeps the Postgres volume, which is what you want between runs.

## If something fails

`docs/RUNBOOK.md` has a section per subsystem with the commands to inspect and debug each one.
`docs/KNOWN_ISSUES.md` lists what is currently broken or limited, with the workaround for each.
