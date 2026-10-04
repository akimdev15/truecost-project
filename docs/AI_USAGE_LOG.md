# AI usage log

The in repository record of assistant supported work. It lives here rather than only in the course
submissions so that the provenance travels with the code.

Tool: Claude, used from the terminal alongside Neovim, throughout the project.

## The working rule

I design and specify, and I generate code only where I can already describe the expected output
precisely. Everything generated is read before it is committed. The test suite is the real check,
and the toll strategy engine is pinned against an independently written oracle rather than against
its own output.

## Standing categories

**Confirming reasoning I had already done.** The largest category. I lay out an approach I have
settled on and ask for the case against it. Two decisions changed this way. The service split,
where I had been drifting toward a wider microservices decomposition and cut back to two
deployables because TrueCost is one cohesive request and response workload and splitting it would
spend the latency I am supposed to be measuring. And the rental pricing source, where working
through the terms of service and the accuracy implications moved base rates to user supplied
input. Both were my calls.

**Repetitive implementation once the design was fixed.** Repository classes, DTO records, Flyway
scaffolding, provider connectors following an established pattern, Testcontainers setup, and the
routine parts of the test suite.

**Documentation.** Drafting and tidying the runbook, the design records, and the engineering logs,
and catching places where a document had drifted out of sync with the code.

**Terminology and reference checking.** Confirming a pattern has the name I am giving it, and
locating the primary source when I know the fact but not the citation.

## What I did without assistance

Choosing the problem and deciding it was worth a thesis. The toll strategy logic, which is the
part the project rests on. All scope decisions. Collecting and verifying the toll, congestion, and
rental program rates against the published schedules, done by hand because the accuracy claim
depends on it. Designing the evaluation, meaning what counts as success and what the golden cases
have to prove.

## Sprint log

| Sprint | Date | What the assistant did | What I did | How it was verified |
| --- | --- | --- | --- | --- |
| Phases 0 to 8 | Jul to Aug 2026 | Implementation to written specifications, test scaffolding, runbook and design record drafting | Every design record, the schema, the Kafka topology, the strategy semantics, the seed data and its provenance | 94 tests against real containers, every runbook command run before commit |
| Hard Stop 1 and 2 packages | Sep 2026 | Document drafting, diagram generation, cross reference checking | Content, decisions, requirement set, risk judgements | Claims traced back to the repository before submission |
| Sprint 1 check-in | 4 Oct 2026 | Repository audit against the rubric, drafting the engineering logs in this directory, the `make smoke` target, and the `osrm-setup.sh` fix | Accepting or rejecting each finding, the risk severities and owners, the reflection and its conclusions | Full `mvnw verify` run and captured, live stack probe captured, every claim in the check-in checked against the tree |

## Findings the assistant surfaced in Sprint 1

Recording these specifically, because they are cases where the tool found something I had not,
and the honest thing is to say so rather than present them as my own review.

- `make osrm` prepared `us-northeast-latest.osrm` while `deploy/compose.yaml` served
  `/data/nyc-metro.osrm`. A clean clone following the README would get a routing container that
  could not start. I had not noticed because my local `data/osrm` already held the clipped file
  from an earlier manual run. Fixed in `scripts/osrm-setup.sh`, logged as I-06.
- The test count reported in the submitted Design Review Package, 114, was wrong. The real figure
  is 94. The error came from a substring match that also counted 12 `@Testcontainers` and 8
  `@TestInstance` annotations. Logged as I-08. The package is already submitted and I am not
  resubmitting it, but the correct number is used everywhere from here.
- The README status section still said Kubernetes was unbuilt after Phase 8 had shipped. Logged
  as I-07.

## Where this shows in the artifact

The repository contains assisted implementation code throughout, written to specifications I
wrote. The course documents were drafted with assistance and edited by me. The design decisions,
the data, and the evaluation are mine. I can explain and defend any component in the system, which
is the line I hold while using the tool.
