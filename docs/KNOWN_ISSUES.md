# Known issues

What you will actually hit running this, and what to do about it. Project level risk tracking
lives separately in `docs/RISK_LOG.md`. This file is operational.

Current as of 11 October 2026, Hard Stop 3 validation. Baseline `v0.1.0-baseline`.

## Functional limitations

**Routes outside the seeded toll coverage are not rejected yet.** A trip toward Boston or
Washington DC returns a ranking rather than an error. The ranking is biased, not merely
incomplete, because missing crossings lower the personal E-ZPass and per crossing totals while
leaving the unlimited plan flat, which pushes the recommendation toward per crossing or a personal
tag. A Manhattan to Boston trip found no tolled crossing at all and recommended per crossing at the
congestion charge alone. FR14
specifies rejection and it is the top open work item. Until it lands, treat any result whose
destination falls outside the New York metro, New Jersey, Connecticut, Hudson Valley, and Long
Island region as not meaningful. Tracked as I-01.

**The clipped map makes an out of bounds route look short rather than failed.** OSRM is served a
map clipped to `-75.35,39.85,-71.5,41.8`. A request toward a city outside it snaps to the nearest
point inside the box and returns a real looking route that stops at the boundary. Check the
snapped waypoint in the response if a duration looks too small. Tracked as I-09.

**Congestion pricing is seeded only up to the 2025 rate.** The 2028 and 2031 increases are not in
`congestion.csv`, so a trip dated 2028 or later is priced at 2025 rates. Every evaluation trip is
in 2026. Tracked as I-15.

**`make eval` has no scenarios to score.** The accuracy runner is built and its scoring math is
self tested, but `eval/golden/scenarios/` is empty because the ground truth is collected by hand.
Until scenarios are complete the target reports that nothing was scored, so the headline accuracy
numbers still have no evidence. Tracked as I-02.

**No live provider credentials are configured.** This is deliberate and not a defect. Rental base
rates are user supplied input by design, and the fuel and hotel connectors fall back to a
documented static price and a synthetic path respectively. Every fallback is marked in the
response `provenance` block. The `freshness` block is not consistent about it, see below.

**A synthetic hotel price is reported as FRESH.** `freshness.hotel` tracks how old the cached
value is, not where it came from, so a synthetic hotel estimate reads FRESH while fuel on its
fallback reads UNAVAILABLE. Read `provenance.hotelSource` to see that the hotel is synthetic. The
demo UI does not show it either, its SYNTHETIC badge is the rental source. Tracked as I-18.

## Environment and build

**Disk space on the development machine is tight.** The host is 98 percent used, and free space
fell from 12 to 9.3 GB across the Hard Stop 3 runs. A fresh OSRM preparation needs about 4.7 GB for its files plus temporary space while
`osrm-extract` runs, so it fits, but with little margin. The graph in `data/osrm` is reused, and
every validation run checks its sha256. Tracked as I-13.

**The prefetcher keeps its Kafka Streams state in the OS temp directory.** `state.dir` is not set,
so it resolves to `$TMPDIR/kafka-streams`. That directory survives `docker compose down -v`, so
after a clean restart the prefetcher finds a checkpoint ahead of the freshly emptied changelog,
logs `TaskCorruptedException` at ERROR, and rebuilds part of the store. The rest of the window
counts survive, so searches from a previous run still count toward the next run's hot routes.
The API's own results are unaffected. `scripts/clean-run.sh` deletes
`$TMPDIR/kafka-streams/truecost-prefetcher` before every run since run 6. If you start the
prefetcher by hand, delete it yourself first. Tracked as I-16.

**OSRM preparation is memory hungry and fails silently.** `osrm-extract` is killed with no error
message when Docker runs out of memory, which reads as a mysterious hang followed by nothing. The
setup script clips the extract by default to stay inside a laptop budget. If it still dies, stop
the application containers first, they compete for the same memory:

```
docker compose -f deploy/compose.yaml --profile app stop
make osrm
```

**The OSRM image is unpinned.** `deploy/compose.yaml` and `scripts/osrm-setup.sh` both reference
`ghcr.io/project-osrm/osrm-backend:latest`, and the car profile is read from inside that image
rather than vendored. A new image could change routing, and different routes mean different
detected crossings. The map file is checksummed, the image is not yet. Tracked as I-05.

**Port 5001, not 5000, for OSRM.** macOS AirPlay Receiver binds 5000. The compose mapping already
accounts for this. If you are on Linux and expected the OSRM default, the host port is still 5001.

**`osmium` comes from a third party image.** The official `ghcr.io/osmcode/osmium-tool` image was
not pullable from the development sandbox, so `stefda/osmium-tool` is used for the clipping step.
Same tool, unofficial build. It runs once during setup and touches nothing at runtime.

**The first `./mvnw verify` is slow.** Maven downloads dependencies and Testcontainers pulls
Postgres, Redis, Kafka, and Apicurio images. Budget ten minutes cold and under a minute warm.

## Observability

**Traces break at the virtual thread fan-out.** A plan request trace shows the HTTP span, the
`trip-searches` publish, and the `quote-snapshots` hop into the prefetcher, but not the route
lookup. Each OSRM call appears in Tempo as its own root trace named `osrm-route`, because the fan
out executor does not carry trace context into the virtual threads. Redis and JDBC calls are not
traced at all. To find a route lookup, search Tempo for `{name="osrm-route"}` rather than opening
the plan request trace. Tracked as I-14.

## Testing

**The property test oracle shares an assumption with the engine.** `StrategyOracle` reimplements
the toll strategy independently except for the `NO_ARRANGEMENT` gate, which it copies. The 10,000
case agreement figure therefore shows the engine matches its specification, not that the
specification is right. This is the most important caveat on the strongest looking test in the
suite. Tracked as I-03.

**SC5 is judged at 2.5 s against a 3 s deadline.** The success criterion is stricter than the
deadline the application enforces, so the harness cannot currently fail a run the criterion would
fail. One of the two numbers has to move. Tracked as I-04.

## Documentation

**`PLAN.md` phase status is not a live record.** It is the plan as written in July 2026. What is
actually built is in `CHANGELOG.md` and the README status section. Where they disagree, the
changelog is right.

## Fixed during Sprint 1

- `make osrm` produced a file name the compose stack did not serve, so a clean clone could not
  start routing. The setup script now produces what compose expects. (I-06)
- `make osrm` re-ran the multi minute `osrm-extract` stage every time, because its skip check
  looked for a bare `.osrm` file that OSRM never writes. A no-op run is now 2 seconds. (I-11)
- The README claimed Kubernetes was unbuilt after Phase 8 had shipped. (I-07)
- The test count reported in the Design Review Package was 114, produced by a substring match
  that also caught `@Testcontainers` and `@TestInstance`. The real count is 94. (I-08)
