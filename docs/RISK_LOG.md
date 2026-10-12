# Risk and issue log

Last reviewed 11 October 2026, at Hard Stop 3, early implementation validation.

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
| R3 | Silent wrong answers on routes outside seeded toll coverage | High | Reject rather than warn, justified by the directional bias argument, specified as FR14 | Chung Hyun Kim | Partially closed. Design settled, not implemented. See I-01 |
| R4 | Published toll rates change mid semester | Medium | Effective dated rows absorb the change, the snapshot date is reported with every result | Chung Hyun Kim | Open, controlled |
| R5 | Renter reads the estimate as a guaranteed price | Medium | NFR13 rubric, three points, a second independent scorer, stricter score wins a tie, pass is 0 of 5 users calling it a price | Chung Hyun Kim | Closure evidence exists, not yet run |
| R6 | External provider becomes unavailable or changes terms | Low | Every connector is off by default with a declared fallback, and the provenance block marks every fallback. The freshness block is not consistent about it, I-18 | Chung Hyun Kim | Closed by design, marking partial, I-18 |
| R7 | Schedule overrun in the final weeks | Medium | Fixed cut order, CI/CD first, then crossing coverage, then departure time optimisation. Accuracy work is not cuttable | Chung Hyun Kim | Open |
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
| I-01 | FR14 out of bounds rejection is not implemented, so a route leaving seeded toll coverage still returns a ranking that is biased toward the unlimited plan | High | Implement the coverage check in TripPlanner and return a typed rejection | Chung Hyun Kim | Open, week ending 11 October |
| I-02 | `make eval` is a stub, so SC1 and SC2 have no evidence path | High | Build the accuracy runner against the golden set | Chung Hyun Kim | Open, Phase 9 |
| I-03 | StrategyOracle reimplements the NO_ARRANGEMENT gate identically to the engine, so the property test agreement figure proves the engine matches its spec rather than that the spec is right | High | Decide the semantics, then break the shared assumption in one of the two implementations | Chung Hyun Kim | Open, week ending 11 October |
| I-04 | SC5 threshold conflicts with the configured 3 s overall deadline | Medium | Restate SC5 or tighten the deadline, not both | Chung Hyun Kim | Open, week ending 18 October |
| I-05 | The OSRM image is still pinned to `:latest` and the car profile is not vendored | Medium | Pin to a digest and copy `car.lua` into the repository | Chung Hyun Kim | Open, week ending 18 October |
| I-06 | `make osrm` produced `us-northeast-latest.osrm` while compose served `/data/nyc-metro.osrm`, so a clean clone following the README got a routing container that could not start | High | `scripts/osrm-setup.sh` now clips to the metro box by default and produces the file compose serves, with `TRUECOST_OSRM_EXTENT=full` for the whole extract | Chung Hyun Kim | Fixed this sprint |
| I-07 | The README status section claimed Kubernetes was unbuilt after Phase 8 had shipped | Low | README rewritten against the actual tree | Chung Hyun Kim | Fixed this sprint |
| I-08 | The submitted Design Review Package reports 114 test methods. The real count is 94. The figure was produced by a substring match that also counted 12 `@Testcontainers` and 8 `@TestInstance` annotations | Low | Corrected here and in the Sprint 1 check-in. Counting is now done with an anchored pattern | Chung Hyun Kim | Fixed this sprint, prior document not resubmitted |
| I-09 | Boston and Washington DC routes resolve to a path that stops at the clipped map boundary rather than failing, which looks like a short route rather than an error | Medium | Folded into I-01. The coverage check rejects these before routing is interpreted | Chung Hyun Kim | Open |
| I-10 | No live rental, fuel, or hotel credentials are configured, so those paths run on documented fallbacks | Low | Accepted by design. Rental rates are user supplied. Fuel and hotel fallbacks are marked in provenance, and only fuel also reports UNAVAILABLE in freshness, I-18 | Chung Hyun Kim | Accepted, not a defect |
| I-11 | `make osrm` re-ran the multi minute `osrm-extract` stage on every invocation. The skip check looked for a bare `.osrm` file, which OSRM never writes, it writes a set of files sharing that base name | Medium | The completion marker is now `.osrm.ebg`, a file the stage actually produces. A no-op run is 2 seconds | Chung Hyun Kim | Fixed this sprint |
| I-12 | `make itest` filters on a `*IntegrationTest` suffix that only 3 of the 12 container backed test classes use, so it runs a subset of the integration suite and still reports success | Medium | Rename the other nine classes to the suffix, or change the filter to a JUnit tag. `make test` runs everything in the meantime | Chung Hyun Kim | Open, Sprint 2 |
| I-13 | Host disk is 98 percent used, and free space fell from 12 to 9.3 GB across the runs. A fresh OSRM preparation needs about 4.7 GB for its files, 1.7 GB of source extract, 0.6 GB of clipped extract, and 2.4 GB of prepared graph, plus temporary space while `osrm-extract` runs. It fits, with little margin | Low | The prepared graph is reused and checksummed on every run. Free space before re-preparing. The 9.8 GB figure quoted since Sprint 1 was a misreading of `ls` block counts | Chung Hyun Kim | Open, found by validation check T9 |
| I-14 | Trace context is lost across the virtual thread fan-out. `VirtualThreadExecutorConfig` returns a bare `Executors.newVirtualThreadPerTaskExecutor()`, so every `osrm-route` span starts a new root trace instead of nesting under the plan request, and Redis and JDBC calls are not traced at all. The dev profile comment promises complete traces | Medium | Wrap the executor with `ContextExecutorService.wrap` and a `ContextSnapshotFactory`, then add Lettuce and JDBC observation. Needed before the baseline load runs, because without it the cold path latency cannot be attributed | Chung Hyun Kim | Open, w/e 18 Oct, found by validation check O2 |
| I-15 | `congestion_schedule` has no rows after 2025-01-05, so the 2028 and 2031 increases are not seeded. A trip dated 2028 or later would be priced at 2025 rates | Low | Seed the scheduled increases with their effective dates. Every evaluation trip is in 2026, so no thesis number is affected | Chung Hyun Kim | Open, found by validation check D7 |
| I-16 | The Kafka Streams `state.dir` is unset and defaults to the OS temp directory. It survives `docker compose down -v`, so every clean restart starts with a stale checkpoint, raises `TaskCorruptedException`, logs an ERROR, and rebuilds part of the window store. The rest survives, so hot route counts carry across a clean restart. Runs 2 and 3 counted 28 and 46 searches where a clean slate would have counted 17, and adding the earlier runs' searches reproduces both numbers exactly. The OS can also clear that directory, which would reset counts mid window | Medium | The harness now deletes the state directory before every clean run, from run 6 on, and waits for the jars to exit first, from run 8 on, which makes the restart clean. The product still leaves `state.dir` unset, so the OS can clear it mid window | Chung Hyun Kim | Harness side fixed, product side open |
| I-17 | The API and the prefetcher both report `service.name=truecost`, so in Tempo the two deployables cannot be told apart, and a trace that crosses the Kafka hop looks like one service | Low | Give the prefetcher its own `spring.application.name` | Chung Hyun Kim | Open, found reading the run 3 trace |
| I-18 | A synthetic hotel price is reported as FRESH. `freshness.hotel` tracks cache age rather than source, so it reads FRESH while `provenance.hotelSource` reads SYNTHETIC. Fuel on its fallback reads UNAVAILABLE, so the two are inconsistent, and earlier documents claimed every fallback was flagged in freshness | Low | Provenance marks it correctly. The UI does not mark the hotel at all, its SYNTHETIC badge is the rental source. Report fallbacks in freshness consistently, or document that freshness is cache age only | Chung Hyun Kim | Open, found by the independent verifier, check A15 |

## Review cadence

The log is reviewed at each sprint check-in. A row changes status only with evidence, a commit, a
test, or a captured run, named in the status cell. Rows that close keep their evidence.
