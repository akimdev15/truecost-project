# Risk and issue log

Last reviewed 25 October 2026, at Hard Stop 4.

One log, two registers. Risks are things that might go wrong and would hurt the thesis claim.
Issues are things that are already wrong today. Both carry an identifier, a severity, an owner, a
mitigation, and a status, and both are reviewed at every sprint boundary.

This is a single developer project, so the owner column is Chung Hyun Kim throughout. It is kept
because the log is meant to survive the project, and an unowned row is how a risk stops being
tracked.

Severity is the product of likelihood and impact. High means it can invalidate a thesis claim or
block the submission. Medium means it degrades a result or costs schedule. Low means it is
contained and noted.

## How the registers relate

`R` rows come from the Hard Stop 1 risk register, September 2026, and are carried forward with
status rather than restated. `D` rows are design risks raised in the Hard Stop 2 Design Review
Package, 27 September 2026. `I` rows are issues opened during Sprint 1. Nothing is deleted. A row
that closes stays in the table with its closing evidence, because the record of what was feared
and did not happen is part of the argument.

## Risk register

| ID | Risk | Severity | Mitigation | Owner | Status |
| --- | --- | --- | --- | --- | --- |
| R1 | Golden set too small or too clustered to support the accuracy claim | High | The coverage grid is a collection requirement, not a reporting one. A cell with no scenario blocks closing the set | Chung Hyun Kim | Open. 0 of 20 cells filled |
| R2 | Accuracy error large enough to flip which option ranks first | High | SC2 measures rank inversion separately from magnitude. If inversions occur, the headline claim narrows from recommendation to breakdown | Chung Hyun Kim | Open |
| R3 | Silent wrong answers on routes outside seeded toll coverage | High | Reject rather than warn, justified by the directional bias argument, specified as FR14 | Chung Hyun Kim | Closed 25 October, residual Delaware River gap tracked as I-21. See I-01 |
| R4 | Published toll rates change mid semester | Medium | Effective dated rows absorb the change, the snapshot date is reported with every result | Chung Hyun Kim | Open, controlled |
| R5 | Renter reads the estimate as a guaranteed price | Medium | NFR13 rubric, three points, a second independent scorer, stricter score wins a tie, pass is 0 of 5 users calling it a price | Chung Hyun Kim | Closure evidence exists, not yet run |
| R6 | External provider becomes unavailable or changes terms | Low | Every connector is off by default with a declared fallback, and the provenance block marks every fallback. Freshness reports a synthetic hotel and a fuel fallback the same way, I-18 | Chung Hyun Kim | Closed by design |
| R7 | Schedule overrun in the final weeks | High | Fixed cut order, CI/CD first, then crossing coverage, then departure time optimisation. Accuracy work is not cuttable | Chung Hyun Kim | Active. The three weeks of build work planned before the midpoint slipped. Cut 1, departure time optimisation, is taken. The week 8 gate, FR14 and 8 golden scenarios, was missed on 25 October, so cut 2 is taken too. The skewed hot route profile is one run and usability drops from five users to three |
| R8 | Single machine failure loses work | Low | Committed and pushed continuously. 69 commits and a tagged baseline pushed to GitHub | Chung Hyun Kim | Closed |
| R9 | Latency or cache targets missed under load, found too late to act | High | Baseline load runs pulled forward to the week ending 18 October, leaving about a month to react | Chung Hyun Kim | Harness proven 4 October. Hard Stop 3 found I-14, which means traces cannot yet explain where cold path time goes. Fixing I-14 is now a precondition for the baseline runs |
| D1 | Crossing detection is systematically wrong and the error is attributed to the wrong cause | High | Detection only cross check against TollGuru on the golden routes, before the golden set closes | Chung Hyun Kim | Open, first half of October |
| D5 | The OSRM image and the map extract both resolved to a rolling latest, so a rebuild could silently change routing | High | Clip and checksum the extract, pin the image digest, vendor the car profile | Chung Hyun Kim | Partially mitigated this sprint. See I-05 |
| D6 | The fuel path uses double, so floating point could reach a ranking decision | Low | The conversion boundary is documented and asserted in FuelCostServiceTest | Chung Hyun Kim | Contained |
| D7 | Reference rates change mid semester, duplicate of R4 at design level | Medium | Effective dated rows, startup expiry check, snapshot date reported | Chung Hyun Kim | Open, controlled |
| D8 | The golden set ends up clustered and misses the decision boundary | High | The coverage grid blocks closure on an empty cell | Chung Hyun Kim | Open, tracked with R1 |
| D9 | NO_ARRANGEMENT is gated so it never competes on a tolled trip, and StrategyOracle shares the gate, so SC3 cannot detect the shared assumption | High | Settle whether declining the programme is a real option, correct both implementations if it is, and state the reading either way | Chung Hyun Kim | Open. See I-03 |
| D10 | SC5 is judged at 2.5 s while the harness and the application deadline both sit at 3 s | Medium | Tighten or restate the threshold before the baseline runs | Chung Hyun Kim | Open. See I-04 |

## Issue register

Issues opened during Sprint 1. These are defects and gaps that exist in the code today, not
things that might happen.

| ID | Issue | Severity | Mitigation or fix | Owner | Status |
| --- | --- | --- | --- | --- | --- |
| I-01 | FR14 out of bounds rejection is not implemented, so a route leaving seeded toll coverage still returns a ranking. The bias runs toward per crossing and a personal tag, not toward the unlimited plan as first written. The 25 October sweep priced Manhattan to Boston with no tolled crossing at all, so every per crossing option cost only the congestion charge and won | High | Implement the coverage check in TripPlanner and return a typed rejection | Chung Hyun Kim | Fixed 25 October. Origin, destination, and every routed point are checked against `coverage_regions.csv` and a miss returns 422 OUT_OF_COVERAGE |
| I-02 | SC1 and SC2 have no evidence until the golden set exists. The runner is built and self tested, but `eval/golden/scenarios/` holds 0 of 20 complete scenarios | High | Collect the scenarios by hand per `eval/golden/README.md`, staged at 8, 14, and 20 | Chung Hyun Kim | Open, runner built, no scenarios yet, issue 2 |
| I-03 | StrategyOracle reimplements the NO_ARRANGEMENT gate identically to the engine, so the property test agreement figure proves the engine matches its spec rather than that the spec is right | High | Decide the semantics, then break the shared assumption in one of the two implementations | Chung Hyun Kim | Open, week ending 11 October |
| I-04 | SC5 threshold conflicts with the configured 3 s overall deadline | Medium | Restate SC5 or tighten the deadline, not both | Chung Hyun Kim | Open, week ending 18 October |
| I-05 | The OSRM image was referenced as `:latest` and the car profile is not vendored | Medium | Pin to a digest and copy `car.lua` into the repository | Chung Hyun Kim | Digest pinned in `deploy/compose.yaml` and `scripts/osrm-setup.sh` on 25 October, to the digest of the image Hard Stop 3 ran on. Vendoring `car.lua` remains open |
| I-06 | `make osrm` produced `us-northeast-latest.osrm` while compose served `/data/nyc-metro.osrm`, so a clean clone following the README got a routing container that could not start | High | `scripts/osrm-setup.sh` now clips to the metro box by default and produces the file compose serves, with `TRUECOST_OSRM_EXTENT=full` for the whole extract | Chung Hyun Kim | Fixed this sprint |
| I-07 | The README status section claimed Kubernetes was unbuilt after Phase 8 had shipped | Low | README rewritten against the actual tree | Chung Hyun Kim | Fixed this sprint |
| I-08 | The submitted Design Review Package reports 114 test methods. The real count is 94. The figure was produced by a substring match that also counted 12 `@Testcontainers` and 8 `@TestInstance` annotations | Low | Corrected here and in the Sprint 1 check-in. Counting is now done with an anchored pattern | Chung Hyun Kim | Fixed this sprint, prior document not resubmitted |
| I-09 | Boston and Washington DC routes resolve to a path that stops at the clipped map boundary rather than failing, which looks like a short route rather than an error | Medium | Folded into I-01. The coverage check rejects these before routing is interpreted | Chung Hyun Kim | Fixed 25 October |
| I-10 | No live rental, fuel, or hotel credentials are configured, so those paths run on documented fallbacks | Low | Accepted by design. Rental rates are user supplied. Fuel and hotel fallbacks are marked in provenance, and both also report UNAVAILABLE in freshness, I-18 | Chung Hyun Kim | Accepted, not a defect |
| I-11 | `make osrm` re-ran the multi minute `osrm-extract` stage on every invocation. The skip check looked for a bare `.osrm` file, which OSRM never writes, it writes a set of files sharing that base name | Medium | The completion marker is now `.osrm.ebg`, a file the stage actually produces. A no-op run is 2 seconds | Chung Hyun Kim | Fixed this sprint |
| I-12 | `make itest` filters on a `*IntegrationTest` suffix that only 3 of the 12 container backed test classes use, so it runs a subset of the integration suite and still reports success | Medium | Rename the other nine classes to the suffix, or change the filter to a JUnit tag. `make test` runs everything in the meantime | Chung Hyun Kim | Open, Sprint 2 |
| I-13 | Host disk is 99 percent used. Free space fell from 12 GB to 9.3 GB to 6.4 GB between 4 and 18 October, and was 6.0 GB on 25 October, which is now less than the 8 GB a fresh map preparation needs | Medium | The prepared graph is reused and checksummed on every run. Free space before re-preparing. The 9.8 GB figure quoted since Sprint 1 was a misreading of `ls` block counts | Chung Hyun Kim | Open, no map rebuild or heavy load run until 15 GB is free |
| I-14 | Trace context is lost across the virtual thread fan-out. `VirtualThreadExecutorConfig` returns a bare `Executors.newVirtualThreadPerTaskExecutor()`, so every `osrm-route` span starts a new root trace instead of nesting under the plan request, and Redis and JDBC calls are not traced at all. The dev profile comment promises complete traces | Medium | Wrap the executor with `ContextExecutorService.wrap` and a `ContextSnapshotFactory`, then add Lettuce and JDBC observation. Needed before the baseline load runs, because without it the cold path latency cannot be attributed | Chung Hyun Kim | Fixed 25 October. The executor is wrapped with `ContextExecutorService`, Lettuce reports through `MicrometerTracing`, and JDBC goes through `datasource-micrometer-spring-boot`. `VirtualThreadExecutorConfigTest` proves a task sees the caller's observation. Check O2 is to be confirmed on the next clean run, found by validation check O2 |
| I-15 | `congestion_schedule` has no rows after 2025-01-05, so the 2028 and 2031 increases are not seeded. A trip dated 2028 or later would be priced at 2025 rates | Low | Seed the scheduled increases with their effective dates. Every evaluation trip is in 2026, so no thesis number is affected | Chung Hyun Kim | Open, found by validation check D7 |
| I-16 | The Kafka Streams `state.dir` is unset and defaults to the OS temp directory. It survives `docker compose down -v`, so every clean restart starts with a stale checkpoint, raises `TaskCorruptedException`, logs an ERROR, and rebuilds part of the window store. The rest survives, so hot route counts carry across a clean restart. Runs 2 and 3 counted 28 and 46 searches where a clean slate would have counted 17, and adding the earlier runs' searches reproduces both numbers exactly. The OS can also clear that directory, which would reset counts mid window | Medium | The harness now deletes the state directory before every clean run, from run 6 on, and waits for the jars to exit first, from run 8 on, which makes the restart clean. The product still leaves `state.dir` unset, so the OS can clear it mid window. On 25 October `./mvnw verify` also failed one test, `PrefetcherFlowIntegrationTest`, because it shares that directory with a running prefetcher. With the jars stopped, all 94 tests passed | Chung Hyun Kim | Fixed 25 October. `truecost.stream.state-dir` defaults to `./var/kafka-streams`, compose uses a tmpfs, Kubernetes an emptyDir, `scripts/clean-run.sh` deletes the new directory, and `PrefetcherFlowIntegrationTest` uses its own temporary directory |
| I-17 | The API and the prefetcher both report `service.name=truecost`, so in Tempo the two deployables cannot be told apart, and a trace that crosses the Kafka hop looks like one service | Low | Give the prefetcher its own `spring.application.name` | Chung Hyun Kim | Fixed 25 October. The prefetcher runs with the `prefetcher` profile, which sets `spring.application.name` to `truecost-prefetcher` |
| I-18 | A synthetic hotel price is reported as FRESH. `freshness.hotel` tracks cache age rather than source, so it reads FRESH while `provenance.hotelSource` reads SYNTHETIC. Fuel on its fallback reads UNAVAILABLE, so the two are inconsistent, and earlier documents claimed every fallback was flagged in freshness | Low | Fixed 25 October. A synthetic hotel now reads UNAVAILABLE in freshness, the same value as the fuel fallback, and the UI badges every synthetic or fallback rental, hotel, and fuel value | Chung Hyun Kim | Fixed 25 October, found by the independent verifier, check A15 |
| I-19 | Validation needs every compose port free on the host. A local Next.js app holding port 3000 kept Grafana from starting, so check T3 failed on 18 October | Low | Free the port before a run. Validation could report which port is taken | Chung Hyun Kim | Open, worked around |
| I-20 | The demo scenario buttons switch the page back to Auto and submit at once, discarding quotes typed in Manual mode, so a renter can believe their quotes were used when they were not | Medium | Fixed 25 October. Choosing a scenario keeps the selected pricing mode, the typed quotes, and the car class. In Manual mode with no quotes typed it loads the trip and asks for a rate instead of submitting | Chung Hyun Kim | Fixed 25 October, found preparing the midpoint demo |
| I-21 | The Philadelphia trip may cross the Delaware River on a tolled bridge that is not seeded, such as the Delaware River Port Authority bridges, the Delaware River Joint Toll Bridge Commission bridges, or the Pennsylvania Turnpike bridge. Only crossings the detector finds are priced, and the route geometry was not inspected because no map data is available on this branch, so the omission is unconfirmed. The coverage rectangles also include unseeded tolled facilities such as the Newburgh Beacon and Mid Hudson bridges and the Atlantic City Expressway edge | Medium | Run the R1 route against the live OSRM and list the bridges its geometry crosses, then seed any tolled one or trim the rectangles. Behaviour for Philadelphia is deliberately unchanged | Chung Hyun Kim | Open, found implementing I-01 |

## Review cadence

The log is reviewed at each sprint check-in. A row changes status only with evidence, a commit, a
test, or a captured run, named in the status cell. Rows that close keep their evidence.
