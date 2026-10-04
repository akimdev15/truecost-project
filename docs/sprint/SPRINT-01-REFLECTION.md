# Sprint 1 reflection

4 October 2026. Baseline `v0.1.0-baseline`.

## Where this sprint actually started

This check-in asks for a project skeleton and a first runnable path. TrueCost was already past
that. Phases 0 through 8 were built between 17 July and 6 August, and CI has been running since
20 July. Standing up a skeleton would have been theatre.

So I treated the sprint as what it is for a project at this stage. The code was ahead of the
engineering discipline around it, and this sprint closed that gap. The conventions the history
already followed were never written down, the risk register lived in submitted coursework rather
than in the repository, there was no changelog, no known issues list, and no tag. A reader could
clone the project and have no way to tell what was real, what was planned, and what was broken.

That turned out to be worth doing for a reason I did not expect, which is the next section.

## What I completed

- Wrote down the conventions the history was already following. `docs/ENGINEERING.md` records the
  layout, the naming rules, the branch strategy, and the artifact storage rules. The branch
  strategy was already real, the four merge commits are phase boundaries, it just was not stated.
- `docs/SETUP.md`, a clean clone path with two levels. Level 1 is `./mvnw -B verify` and takes
  about a minute. Level 2 runs the whole system and is honest that the routing data preparation
  is the expensive part.
- `docs/RISK_LOG.md`, one register carrying the Hard Stop 1 risks and the Hard Stop 2 design
  risks forward with severity, owner, mitigation, and status, plus ten issues opened this sprint.
- `docs/KNOWN_ISSUES.md`, the operational limitations separated from project risk, because they
  are read by different people at different times.
- `docs/ARCHITECTURE.md`, the package by package map from the repository to the architecture in
  the Design Review Package, so a reader can go from a box in a diagram to the code.
- `docs/API.md`, the endpoint contract with payloads captured from a real response rather than
  written by hand, and the stub behaviour a clean clone actually runs on.
- `.env.example`, every environment variable with its default and what happens unset. There are
  no required credentials, which is a scope decision rather than an omission, and the file is
  where that is now stated.
- `CHANGELOG.md` and `docs/AI_USAGE_LOG.md`.
- `make smoke`, one target that builds, runs the full suite, and probes the live stack.
- Captured evidence in `docs/evidence/`, a full `mvnw verify` run, a live stack smoke, and a k6
  load run against both latency thresholds.
- Tagged `v0.1.0-baseline`.

## What I found, which is the part worth reading

Four things were wrong, and all four were only visible because I was writing documentation
against the actual tree rather than from memory.

**The setup path was broken for anyone but me.** `make osrm` prepared
`us-northeast-latest.osrm`. `deploy/compose.yaml` started `osrm-routed` against
`/data/nyc-metro.osrm`. Those are different files. A clean clone following the README would get a
routing container that could not start, with a reasonably cryptic failure. I never saw it because
my own `data/osrm` already held the clipped file from a manual run in August, and the compose
comment claiming the names matched was written at the same time the name changed. This is the
exact failure the rubric's self check asks about, could a peer clone the repo and reproduce the
baseline without asking a follow up question, and the honest answer until this sprint was no.
Fixed. `scripts/osrm-setup.sh` now clips by default and produces the file compose serves, with
`TRUECOST_OSRM_EXTENT=full` for the whole extract.

**I had been reporting the wrong test count.** The Design Review Package says 114 test methods.
The real number is 94. The figure came from counting occurrences of the string `@Test`, which
also matches `@Testcontainers`, 12 of them, and `@TestInstance`, 8 of them. 94 plus 12 plus 8 is
114. The package is submitted and I am not resubmitting it, but the correct figure is used
everywhere from here and the cause is logged as I-08. A measurement that is not anchored is not a
measurement, which is an uncomfortable thing to learn on a project whose whole claim is accuracy.

**`make osrm` redid its slowest stage every time.** The skip check for `osrm-extract` looked for
a bare `.osrm` file. OSRM never writes one, it writes about twenty five files sharing that base
name. So the check never matched and the multi minute stage reran on every invocation, which I
had absorbed as the step simply being slow. The marker is now a file the stage actually produces
and a no-op run is two seconds. I-11.

**The README was describing a system one phase behind.** It said Kubernetes packaging was still
Phase 8 work after Phase 8 had shipped.

None of the four were caught by CI, because all four are statements about the project rather
than properties of the code. That is the lesson I am taking out of this sprint. The test suite
protects behaviour. Nothing was protecting the claims, and the claims are what a reader uses to
decide whether to trust the behaviour.

## How this follows from the Hard Stop 2 feedback

The Design Review Package came back at 95 with no corrections to make, so there was nothing to
fix. What the feedback did do was name where the field is going, and four of those directions
are things this sprint could act on rather than just agree with.

| Named in the feedback | What Sprint 1 did about it |
| --- | --- |
| Reproducible development environments | `docs/SETUP.md` and `.env.example`, and the broken clean clone path that writing them exposed |
| Deterministic builds and supply chain integrity | The map extract is now clipped deterministically and checksummed. The OSRM image digest is still unpinned and is logged as I-05 rather than claimed |
| AI assisted development with transparent provenance | `docs/AI_USAGE_LOG.md` moved into the repository, with a sprint table and a named list of what the tool found that I had not |
| Property based testing for validating complex systems | No new tests, but the honest limit of the existing one is now written down. The oracle shares the engine's `NO_ARRANGEMENT` gate, so the 10,000 case result is weaker than it looks |

The thing the feedback praised was separating completed work from designed work from planned
work. That separation is the organising principle of every document added this sprint, and it is
why `docs/KNOWN_ISSUES.md` exists as a file rather than as a paragraph nobody reads.

## What is blocked or not done

Nothing is blocked in the sense of waiting on someone else. Two things are not done and they are
the same two that the Design Review Package named.

- **FR14 out of bounds rejection is not implemented.** A route leaving the seeded toll coverage
  still returns a ranking. The ranking is biased rather than merely incomplete, because missing
  crossings lower the personal E-ZPass and per crossing totals while leaving the unlimited plan
  flat. I-01, and the top item for next sprint.
- **The accuracy runner does not exist.** `make eval` is a stub, so SC1 and SC2 have no evidence
  path. I-02.

One thing I chose not to chase. `make up-all` builds both images from source and the build was
still running after thirty minutes on this machine, because the Dockerfile resolves the full
dependency tree inside the container. I stopped it and captured the smoke against the infra stack
with the API running from its built jar instead, which exercises the same code against the same
real Postgres, Redis, and OSRM. CI builds both images on every push, so the image path is covered.
The slow local image build is a developer experience annoyance, not a correctness gap, and it is
not worth a sprint item yet.

## Risk implications

R8, losing work to a machine failure, is now closed rather than merely argued, because there is a
tagged baseline on the remote.

R9, latency targets missed and found too late, is better than it was but not closed. The harness
runs and both thresholds pass with a wide margin, p95 of 4.41 ms warm against 300 ms and 66.13 ms
cold against 3 s, over 6012 requests with no failures. The margin is misleading on purpose and I
have written that into the evidence file. No external provider was reachable in that run, so the
cold path measured the fan-out, the cache, and the strategy engine, and not the network latency to
a live provider, which is the part most likely to move p95. What this closes is the risk that the
harness itself does not work. The measurement still has to happen in the week ending 18 October.

D5, the routing moving target, moved from unmitigated to partly mitigated. The map extract is now
clipped deterministically and checksummed by the setup script, so an accuracy measurement can name
the map it was taken against. The OSRM image is still `:latest` and the car profile still lives
inside that image, so the risk is reduced and not closed. I-05.

I-06, the broken setup path, is the kind of defect that would have cost a reviewer an hour and me
my credibility on reproducibility, which is one of the five rubric criteria. It is closed, but it
raises the standing question of what else is only true on my machine. The answer for now is the
Level 1 path in `docs/SETUP.md`, which needs nothing but a JDK and Docker and which I can ask
someone else to run.

## What Sprint 2 targets

In order, and the first two are the ones that matter.

1. **FR14 out of bounds rejection**, I-01, week ending 11 October. Implement the coverage check
   and return a typed rejection rather than a biased ranking. This closes R3 as an implementation
   closure rather than a design one, which is the gap the Design Review Package flagged.
2. **Settle the NO_ARRANGEMENT question**, I-03, week ending 11 October. `StrategyOracle` copies
   the engine's gate, so the 10,000 case property test proves the engine matches its spec rather
   than that the spec is right. Decide whether declining the programme is a real option, correct
   whichever implementation is wrong, and state the reading either way.
3. **Start the accuracy runner**, I-02. Without it the headline claim has no evidence path, and
   it has the longest lead time of anything left.
4. **Pin the OSRM image digest and vendor the car profile**, I-05, week ending 18 October, before
   the baseline load runs so the latency numbers and the accuracy numbers name the same routing
   stack.
5. **Resolve the SC5 threshold conflict**, I-04, same week.

The golden set is the thing I am most behind on, zero of twenty coverage cells filled, and it
gates both accuracy criteria. It does not appear above as its own item because it is collection
work that runs alongside the code, but if Sprint 2 ends with it still empty that is the signal
that the schedule risk R7 is turning real.
