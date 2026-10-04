# Known issues

What you will actually hit running this, and what to do about it. Project level risk tracking
lives separately in `docs/RISK_LOG.md`. This file is operational.

Current as of 4 October 2026, baseline `v0.1.0-baseline`.

## Functional limitations

**Routes outside the seeded toll coverage are not rejected yet.** A trip toward Boston or
Washington DC returns a ranking rather than an error. The ranking is biased, not merely
incomplete, because missing crossings lower the personal E-ZPass and per crossing totals while
leaving the unlimited plan flat, which pushes the recommendation toward the unlimited plan. FR14
specifies rejection and it is the top open work item. Until it lands, treat any result whose
destination falls outside the New York metro, New Jersey, Connecticut, Hudson Valley, and Long
Island region as not meaningful. Tracked as I-01.

**The clipped map makes an out of bounds route look short rather than failed.** OSRM is served a
map clipped to `-75.35,39.85,-71.5,41.8`. A request toward a city outside it snaps to the nearest
point inside the box and returns a real looking route that stops at the boundary. Check the
snapped waypoint in the response if a duration looks too small. Tracked as I-09.

**`make eval` is a stub.** The accuracy evaluation runner is Phase 9 and is not built, so the
headline accuracy numbers have no evidence path yet. The target prints a pointer and exits.
Tracked as I-02.

**No live provider credentials are configured.** This is deliberate and not a defect. Rental base
rates are user supplied input by design, and the fuel and hotel connectors fall back to a
documented static price and a synthetic path respectively. Every fallback is tagged in the
response `freshness` block, so a fallback is visible rather than silently blended into the total.

## Environment and build

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
