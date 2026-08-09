# Toll strategy engine design

This document specifies the toll strategy engine, the pure function module in `toll/strategy/` that decides, for one rental option and one route, whether the user's own personal E-ZPass, a rental company per-crossing toll program, or a company unlimited toll plan is the cheapest way to pay for the tolls and congestion of a trip. It is the thesis centerpiece and is written so it can be lifted into the thesis chapter on the toll strategy algorithm, and so a Sonnet agent can translate it into Java records and pure functions without any further architectural judgment calls. The prose follows the project code standard and contains no em dashes, no en dashes, and no semicolons.

Every money field in this document is a long count of whole cents, matching the domain `Money` value type. All arithmetic is integer addition, integer multiplication, and `Math.min` on longs. There is no floating point anywhere in the engine. Where the record fields below are typed as `long ...Cents`, they are the backing value of the domain `Money` type and the implementer may wrap them in `Money` if preferred, since the operations used are exactly add, multiply by a small integer count, and minimum.

## Design principles

The engine is a pure function. It takes value objects in, returns a value object out, performs zero I/O, reads no clock, opens no connection, and touches no cache. Given the same input it always returns the same output. This is what makes it exhaustively testable in isolation and what lets the property test in Phase 3 run ten thousand randomized cases in milliseconds.

Everything the engine needs is precomputed by Phase 1 and Phase 2 and handed in. Crossing detection, rate lookup, arrival time estimation, the tunnel credit, and the once per day congestion collapse all happen upstream. The engine never recomputes any of them. It only compares the total cost of each available toll strategy and selects the cheapest, then explains the choice.

The engine sits after Phase 2 in the request path. Phase 2 produces the priced crossings and the per day congestion charges for a route and departure time. The engine consumes those, together with the rental duration, the personal tag flag, and the set of toll programs the company under evaluation offers. Phase 5 calls the engine once per rental option during fan-out and places the result into the ranked response.

## Section 1, input and output value types

### Enumerations

```
enum TollRateBasis { EZPASS, MAX_CASH }

enum ProgramType { PER_CROSSING_USAGE_DAY, PER_CROSSING_ALL_DAYS, UNLIMITED_DAILY }

enum Strategy { PERSONAL_TAG, COMPANY_PER_CROSSING, COMPANY_UNLIMITED, NO_ARRANGEMENT }
```

`TollRateBasis` selects which of a crossing's two prices a company program bills at. `EZPASS` bills the discounted transponder rate, `MAX_CASH` bills the higher Tolls by Mail or maximum cash rate, which is how several programs mark up tolls.

`ProgramType` is the single discriminator for a program's cost shape. It carries three values and it deliberately folds together the plan's two separate ideas of a program type and a fee basis into one field. The plan text lists a program type of usage-day fee, all-rental-days fee, or unlimited flat daily, and separately lists a fee basis of usage days versus all rental days. Those two ideas are not independent. A usage-day program has a usage-day fee basis, an all-rental-days program has an all-rental-days fee basis, and an unlimited program has no per-day admin fee at all and so has no fee basis. Modeling type and fee basis as two independent fields would allow contradictory combinations such as an unlimited program with an all-rental-days fee basis, which has no meaning. Folding them into one three valued enum removes that whole class of invalid state and gives the engine one clean switch. The mapping is fixed and total.

| ProgramType | Fee basis meaning | Admin fee scales with |
| --- | --- | --- |
| PER_CROSSING_USAGE_DAY | Usage days | Days a toll was actually incurred |
| PER_CROSSING_ALL_DAYS | All rental days | Every rental day, once any toll is incurred |
| UNLIMITED_DAILY | Not applicable | Every rental day, unconditionally, tolls included |

`Strategy` names the four strategies the engine ranks. A company that offers more than one program contributes more than one company strategy candidate, and the winner names both the strategy kind and, for company strategies, which program won.

### PricedCrossing

```
record PricedCrossing(
    String name,
    long ezpassCents,
    long cashOrMailCents,
    LocalDate tollDate
) { }
```

One tolled facility the route actually crosses, already detected and priced by Phase 2. `ezpassCents` is the toll at the E-ZPass rate for the vehicle class and the estimated arrival time. `cashOrMailCents` is the same crossing at the Tolls by Mail or maximum cash rate, which is always greater than or equal to the E-ZPass rate. Both are carried because the correct one to use depends on the strategy under evaluation, and the engine must not have to go back to the rate table to fetch the other.

`tollDate` is the calendar date, in America/New_York, on which the vehicle is estimated to cross this facility. Phase 2 computes it by adding the cumulative driving duration to the departure time and resolving the resulting instant to a local date. It is precomputed and stored on the crossing so the engine never touches a time zone and stays trivially pure. The set of distinct `tollDate` values across all crossings is the sole source of the usage-day count, which Section 3 defines.

### CongestionDay

```
record CongestionDay(
    LocalDate date,
    boolean peak,
    long netEzpassCents,
    long netMailCents,
    String creditTunnel
) { }
```

One calendar date on which the vehicle enters the MTA Congestion Relief Zone. Phase 2 produces at most one of these per date, because the congestion charge is levied once per day. The two amounts are the net charge for that day after the tunnel credit has been subtracted and clamped at zero, given at both rate bases. `netEzpassCents` is the net congestion charge at the E-ZPass rate, `netMailCents` is the net congestion charge at the Tolls by Mail rate.

The tunnel credit and the once per day collapse are applied by Phase 2, not by the engine. This is a deliberate separation. The credit and the daily collapse are properties of the congestion schedule and the trip timeline, and they are identical no matter which toll product the user buys. A Hugh Carey tunnel credit reduces the congestion charge whether the user runs a personal tag or a company plan. Only two things about congestion vary by strategy, whether the chosen product covers the congestion charge and so zeroes it, and which rate basis applies to it when the product does not cover it. The engine needs only the netted per day amounts at each basis to handle both, so recomputing the credit inside the engine would duplicate Phase 2 logic for no gain. `peak` and `creditTunnel` are carried for the human readable explanation only and do not enter any cost formula. `creditTunnel` is null when no credit applied that day.

### TollProgram

```
record TollProgram(
    String companyName,
    String programName,
    ProgramType type,
    long dailyFeeCents,
    boolean feeCapped,
    long feeCapCents,
    TollRateBasis tollRateBasis,
    boolean coversCongestion,
    LocalDate effectiveDate
) { }
```

One toll product a rental company offers, sourced from the `toll_program` seed table with its effective date. The engine evaluates one company at a time and receives the set of that company's programs.

`dailyFeeCents` is the admin fee charged per fee-day for the two per-crossing types, and is the flat daily rate for the unlimited type. `feeCapped` states whether the total admin fee across the rental is capped, and `feeCapCents` is that cap. When `feeCapped` is false the `feeCapCents` value is ignored and the fee accumulates without limit. A boolean plus a value is used rather than a sentinel number so that a legitimate cap of zero cents is representable and never confused with the absence of a cap. Real per-crossing programs almost always carry a cap, for example the usage-day shape at a fixed dollar amount per rental, so the cap is a first class field and not an afterthought. For the unlimited type the cap fields are inert, since an unlimited plan is a flat daily rate with no admin fee to cap.

`tollRateBasis` selects `ezpassCents` or `cashOrMailCents` for each crossing when this program bills tolls per crossing. It is meaningful only for the two per-crossing types. For the unlimited type it is inert, since an unlimited plan covers all tolls at no per-crossing rate.

`coversCongestion` states whether buying this product includes the MTA congestion charge. When true, the congestion charge contributes zero to this strategy's total. When false, the congestion charge is added at the Tolls by Mail basis, as Section 2 details.

### StrategyInput

```
record StrategyInput(
    List<PricedCrossing> crossings,
    List<CongestionDay> congestionDays,
    int rentalDays,
    boolean ownsPersonalTag,
    List<TollProgram> programs
) { }
```

The complete input to the engine. `crossings` is every tolled facility the route crosses, possibly empty. `congestionDays` is every congestion day, possibly empty. `rentalDays` is the number of calendar days of the rental and is at least one. `ownsPersonalTag` is whether the user owns a personal E-ZPass. `programs` is the set of toll programs the company under evaluation offers and is non-empty, because a company that a user can rent from and drive on toll roads with always offers at least one toll product, which is the fallback when the user has no personal tag.

Note that there is no separate usage-day count field. The plan lists a usage-day estimate among the available inputs, but Section 3 explains why the engine derives the usage-day count itself from the crossing dates rather than accepting it as a hand supplied scalar. Deriving it internally removes the risk of an inconsistent guess and gives the engine and its test oracle one shared, precisely defined source of truth.

### The output, StrategyResult and StrategyCost

```
record StrategyCost(
    Strategy strategy,
    String programName,
    long totalCents,
    long feeCents,
    long tollCents,
    long congestionCents,
    int feeDays
) { }

record StrategyResult(
    Strategy winner,
    String programName,
    long totalCents,
    String explanation,
    List<StrategyCost> candidates
) { }
```

`StrategyCost` is the fully broken down cost of one evaluated candidate. `feeCents` is the admin fee for a per-crossing program or the flat plan cost for an unlimited program, and is zero for the personal tag and no-arrangement strategies. `tollCents` is the toll portion at the applicable basis, and is zero when the product covers all tolls. `congestionCents` is the congestion portion, and is zero when the product covers congestion or when there is no congestion. `feeDays` is the number of days the admin fee applied, which is the usage-day count, the rental-day count, or zero depending on the strategy, and it exists so the explanation and the tests can see exactly how the fee was formed. `totalCents` is `feeCents` plus `tollCents` plus `congestionCents`.

`StrategyResult` is the engine's answer. `winner` is the chosen strategy, `programName` is the winning company program or null for the personal tag and no-arrangement strategies, `totalCents` is the winning total, `explanation` is the human readable justification designed in Section 5, and `candidates` is every evaluated candidate with its breakdown, retained so the API and the tests can inspect the runners up. Section 5 designs the explanation and Section 4 defines how the winner is selected from the candidates.

## Section 2, the strategy enumeration and the cost formulas

The engine builds a list of candidate costs, one per applicable strategy, then selects the winner as Section 4 defines. This section gives the cent-precise formula for each strategy. The following derived quantities, defined fully in Section 3, are used throughout.

- `tollEz` is the sum over all crossings of `ezpassCents`.
- `tollMax` is the sum over all crossings of `cashOrMailCents`.
- `congEz` is the sum over all congestion days of `netEzpassCents`.
- `congMail` is the sum over all congestion days of `netMailCents`.
- `usageDays` is the count of distinct `tollDate` values across the crossings.
- `rentalDays` is the input rental day count.
- `hasTolls` is true when the crossings list is non-empty.
- `hasCongestionCost` is true when `congEz` is greater than zero or `congMail` is greater than zero.

### Congestion basis rule, stated once

When a strategy does not cover the congestion charge, that charge is added at exactly one basis per strategy, and the rule is fixed across the whole engine so there is no per-case judgment.

- The personal tag strategy adds congestion at the E-ZPass basis, `congEz`, because the user's own transponder handles the congestion charge at the E-ZPass rate.
- Any company strategy that does not cover congestion adds it at the Tolls by Mail basis, `congMail`, because a congestion charge that a toll product excludes is not run through the company transponder and instead reaches the license plate and is billed by mail at the higher rate.
- Any strategy that covers congestion adds zero.
- The no-arrangement strategy adds zero, because it exists only when there is no congestion cost at all.

This rule gives the personal tag a genuine and defensible congestion advantage, since the E-ZPass congestion rate is lower than the Tolls by Mail congestion rate, which is a clean point for the thesis.

### Strategy PERSONAL_TAG

Available only when `ownsPersonalTag` is true. The user mounts their own E-ZPass. Every crossing bills at the E-ZPass rate, the congestion charge bills at the E-ZPass rate, and there is no company admin fee.

```
feeCents        = 0
tollCents       = tollEz
congestionCents = congEz
totalCents      = tollEz + congEz
feeDays         = 0
```

### Strategy COMPANY_PER_CROSSING

One candidate per program whose `type` is `PER_CROSSING_USAGE_DAY` or `PER_CROSSING_ALL_DAYS`. The admin fee depends on the fee basis, then the cap is applied, then the tolls at the program basis and the congestion per coverage are added.

The fee-day count depends on the type.

```
feeDays = (type == PER_CROSSING_USAGE_DAY) ? usageDays
        : (usageDays > 0 ? rentalDays : 0)
```

For the usage-day type the fee applies only on days a toll was actually incurred, so the count is `usageDays`. For the all-rental-days type the fee applies to every rental day once any toll is incurred at all, so the count is `rentalDays` when at least one toll day exists, and zero when the route incurs no toll, since with no toll the program is never triggered.

The cap interacts with the fee, not with the tolls and not with the congestion. The cap bounds the total admin fee across the rental. It is applied to the raw fee, which is the daily fee times the fee-day count, and only when the program is capped.

```
rawFee   = dailyFeeCents * feeDays
feeCents = feeCapped ? Math.min(rawFee, feeCapCents) : rawFee
```

Because the cap bounds `rawFee = dailyFeeCents * feeDays`, and `feeDays` is where the two fee bases differ, the cap and the fee basis interact through `feeDays` and nowhere else. Under the usage-day basis the cap binds once `dailyFeeCents * usageDays` exceeds the cap. Under the all-rental-days basis the cap binds once `dailyFeeCents * rentalDays` exceeds the cap. The same cap therefore engages at different trip shapes depending on the basis, which is exactly the effect the boundary tests in Section 7 exercise.

The tolls bill at the program's rate basis.

```
tollCents = (tollRateBasis == EZPASS) ? tollEz : tollMax
```

The congestion follows the basis rule above.

```
congestionCents = coversCongestion ? 0 : congMail
```

The total is the sum.

```
totalCents = feeCents + tollCents + congestionCents
```

When the route incurs no toll, `feeDays` is zero for both bases, so `feeCents` is zero and `tollCents` is zero, and the candidate reduces to its congestion term, which is zero if the program covers congestion and `congMail` if it does not. That is the correct behavior, since an untriggered program charges no admin fee.

### Strategy COMPANY_UNLIMITED

One candidate per program whose `type` is `UNLIMITED_DAILY`. The flat daily rate applies to every rental day unconditionally, the plan covers all tolls, and it covers the congestion charge only when it says so.

```
feeDays         = rentalDays
feeCents        = dailyFeeCents * rentalDays
tollCents       = 0
congestionCents = coversCongestion ? 0 : congMail
totalCents      = feeCents + congestionCents
```

The flat fee is charged for the whole rental whether or not any toll is crossed, which is the nature of an unlimited plan, so there is no cap and no usage-day dependence. All tolls are included, so `tollCents` is zero. The cap fields and the toll rate basis of an unlimited program are inert and are not read.

### Strategy NO_ARRANGEMENT

Added as a candidate only when `hasTolls` is false and `hasCongestionCost` is false, that is when the route triggers zero tolls and zero congestion cost. In that case no toll product is worth buying and the user needs no personal tag.

```
feeCents        = 0
tollCents       = 0
congestionCents = 0
totalCents      = 0
feeDays         = 0
```

When it is a candidate it always costs zero, so nothing can beat it, and the tie-break in Section 4 makes it win any tie at zero. When the route does incur a toll, no-arrangement is not offered as a candidate, because a driver who owns no personal tag and buys no product but still crosses a tolled facility is automatically enrolled in the company per-crossing program and billed its admin fee, so doing nothing is not a real independent option once a toll exists.

## Section 3, the usage-day estimation rule

The usage-day count is the number of distinct calendar dates on which the itinerary actually crosses a tolled facility. The engine derives it directly from the crossings, and it is defined precisely as follows.

```
usageDays = number of distinct tollDate values across the crossings list
```

Each `PricedCrossing` carries a `tollDate`, which Phase 2 computed by taking the departure time, adding the cumulative driving duration to the crossing, and resolving the resulting instant to a calendar date in America/New_York. The usage-day count is simply the size of the set of those dates. When there are no crossings the set is empty and the count is zero.

This is the derivation the plan asks the engine to use rather than a heuristic guess, and the reason is that the usage-day count sits directly inside the fee formula of the usage-day program, so a wrong count moves the total by one daily fee per wrong day and can flip which strategy is cheapest. Consider the canonical NYC weekend trip. The user picks the car up Friday, drives out of the city across a tolled bridge, parks at the destination all day Saturday, and drives back across the bridge on Sunday. The rental is three calendar days, but tolls are incurred on only two of them, Friday and Sunday. The usage-day count is two, not three. A heuristic that assumed usage days equals rental days would compute three, would overstate a usage-day program's admin fee by one daily fee, and could recommend a different and more expensive product than the true cheapest one.

A concrete flip makes the risk exact. Take a weekend trip with two crossings, one out and one back, at a combined E-ZPass toll of thirty dollars and zero cents, no congestion, no personal tag, and a rental length of four days with the car parked in the middle so the true usage-day count is two. A usage-day program charges six dollars and ninety five cents per usage day, capped at nineteen dollars and seventy five cents, tolls at the E-ZPass rate. Its true cost is the fee for two usage days, thirteen dollars and ninety cents, which is below the cap, plus thirty dollars of tolls, for a total of forty three dollars and ninety cents. The company also offers an all-rental-days program at four dollars per rental day, capped at twenty dollars, tolls at the E-ZPass rate, whose cost on a four day rental is sixteen dollars of fee plus thirty dollars of tolls, forty six dollars even. With the usage-day count correct at two, the usage-day program wins at forty three dollars and ninety cents. Now suppose the engine had been handed a wrong usage-day estimate of three. The usage-day fee would rise to three times six dollars and ninety five cents, which is twenty dollars and eighty five cents, capped down to nineteen dollars and seventy five cents, giving a usage-day total of forty nine dollars and seventy five cents. That is now more than the all-rental-days total of forty six dollars, so the recommended winner flips from the usage-day program to the all-rental-days program purely because of a wrong day count. This is why the count must be derived from the real timeline and never assumed.

A modeling note keeps the definition unambiguous for the engine and its oracle. A congestion day does not by itself create a usage day. The usage-day count is drawn only from crossing dates. In the NYC weekend trip space this loses nothing, because every entry into the Congestion Relief Zone is made across a tolled bridge or tunnel, so a congestion day always coincides with a crossing day, and the crossing already contributes that date. Fixing the definition to crossing dates alone gives the engine and the oracle one identical, precisely stated rule, which is what the property test requires.

## Section 4, selecting the winner and the tie-break

The engine assembles the candidate list, computes each candidate's `totalCents` by the formulas in Section 2, and selects the winner as the candidate with the lowest total. Ties are possible and common, most obviously in the zero cost case where the personal tag, an untriggered per-crossing program, and no-arrangement all cost zero, so the tie-break must be a fixed total order that the engine and the oracle both implement identically.

Candidates are ordered by these keys in sequence.

1. `totalCents` ascending. The cheapest total wins.
2. Strategy priority ascending, where NO_ARRANGEMENT is zero, PERSONAL_TAG is one, COMPANY_PER_CROSSING is two, and COMPANY_UNLIMITED is three. On an equal total the simplest and most user friendly option wins, preferring the option that needs no purchase and no equipment, then the user's own tag, then the cheapest company product.
3. Program name ascending. When two company programs tie on total and priority, the alphabetically first program name wins, purely so the choice is deterministic.

The first candidate under this order is the winner. This total order is specified once here and both the engine and the oracle must apply it exactly, since the property test compares the winner and the total and any disagreement in the tie-break would produce spurious failures.

## Section 5, the result object and the explanation

The result carries the winning strategy, its total, and a human readable explanation, all of which flow into the Phase 5 API response and the Phase 5 aggregation output. The explanation is prose, and it is not part of the property test, which asserts only the winner and the total. The prose therefore has latitude in its exact wording, as long as it names the winner and its total, names the runner up and the margin, and states the decisive factor. The following template and factor logic give the implementer a fully specified default.

The runner up is the second candidate under the Section 4 order. The margin is the runner up total minus the winner total. The decisive factor is chosen by the first matching rule.

1. If the winner is NO_ARRANGEMENT, the factor is no tolls or congestion.
2. Else if the winner is a per-crossing program whose raw fee exceeded its cap, that is `feeCapped` is true and `dailyFeeCents * feeDays` is greater than `feeCapCents`, the factor is the cap.
3. Else if recomputing the winner with its `coversCongestion` flag flipped would change whether it still beats the runner up, the factor is congestion coverage.
4. Else the factor is the toll total.

The rendered explanation follows a fixed template with slots filled from the winner, the runner up, the margin, and the factor.

- Base template. "{winnerLabel} wins at {winnerTotal}. Next best is {runnerLabel} at {runnerTotal}, a margin of {margin}. {factorSentence}"
- Factor sentence, no tolls or congestion. "This route crosses no tolled facility and enters no congestion zone, so no toll product and no personal tag are needed."
- Factor sentence, cap. "The program admin fee reached its cap of {capAmount}, so extra toll days add no fee, which is what makes it cheapest."
- Factor sentence, congestion coverage. "The plan covers the congestion charge of {congestionAmount}, which is the deciding factor against the runner up."
- Factor sentence, toll total. "The toll total of {tollAmount} is low enough that avoiding any daily plan fee is what wins."

The `winnerLabel` is a readable name such as "Personal E-ZPass", or the company and program name such as "Hertz PlatePass per-crossing", or "Avis e-Toll Unlimited", or "No toll arrangement". Rendered examples for each boundary scenario appear in Section 7.

## Section 6, why a personal tag plus an unlimited plan is never sensible

The engine never evaluates a combined strategy of running a personal tag while also buying a company unlimited plan, and the reason belongs directly in the engine documentation. The unlimited plan's cost is a flat daily rate times the rental days plus, if not covered, the congestion charge, and it already covers every toll. Mounting the personal tag on top of it cannot lower the flat fee, which is fixed by the rental length, and cannot lower the toll cost, which the plan has already reduced to zero. The tag therefore adds no benefit. At best the tag sits idle and the combination costs exactly what the unlimited plan alone costs. At worst the tag is read at a crossing and double bills a toll the plan already covers, so the combination costs more than the unlimited plan alone. The combination is therefore weakly dominated by the unlimited plan alone and can never be strictly cheaper than the better of the two standalone options.

Two worked examples make this concrete. Take a three day trip with four crossings totaling thirty dollars at the E-ZPass rate and one congestion day at nine dollars at the E-ZPass rate.

First example, the tag is the better standalone option. The personal tag alone costs the thirty dollars of E-ZPass tolls plus the nine dollars of E-ZPass congestion, thirty nine dollars even. An unlimited plan at thirteen dollars per day over three days costs thirty nine dollars and, when it covers congestion, adds nothing, so thirty nine dollars. The combination, tag plus unlimited, still pays the thirty nine dollar unlimited fee while the tag does nothing useful, so it costs thirty nine dollars at best, which merely equals the unlimited standalone and is strictly worse than simply using the tag, since buying the plan was wasted money the moment you already own the tag. The sensible move is the tag alone at thirty nine dollars, not the combination.

Second example, the unlimited plan is the better standalone option. Raise the tolls so the four crossings total sixty dollars at the E-ZPass rate with no congestion. The personal tag alone now costs sixty dollars. The unlimited plan at thirteen dollars per day over three days costs thirty nine dollars and covers all sixty dollars of tolls. The combination pays the thirty nine dollar plan fee while the tag sits idle behind a plan that already covers everything, so it costs thirty nine dollars, exactly the unlimited standalone, and the tag contributed nothing. The sensible move is the unlimited plan alone at thirty nine dollars, not the combination.

In both directions the combination is never below the minimum of the two standalone strategies, so it can never be the unique cheapest answer. The personal tag is only ever useful as a substitute for a company product, never as a complement to the unlimited plan, because the unlimited plan's price is independent of toll usage and already covers what the tag would cover. Since the engine already evaluates the personal tag and the unlimited plan as separate candidates and takes the minimum, adding the combination as a candidate could only ever tie or lose, so it is excluded by design.

## Section 7, the brute-force oracle and the randomized generator

### The oracle

The property test in Phase 3 runs ten thousand randomized scenarios and asserts that the engine's winner and total equal a brute-force oracle's winner and total in every case. The oracle is a second, independent, intentionally naive implementation of the same rules, written for obvious correctness rather than for speed or reuse.

Obvious correctness here means a specific and testable set of properties. The oracle is a separate class in the test sources that shares no code with the engine, calls none of the engine's helper methods, and imports none of the engine's internal functions. It reads only this specification, not the engine source, so a mistake in the engine cannot propagate into the oracle. It enumerates every candidate strategy in a plain loop and computes each candidate's cost as the most literal possible transcription of the English rules in Section 2, with every term inlined and no shared helper, so a reader can check each line against the prose by eye. It computes the usage-day count by literally building a set of the crossing dates and taking its size, recomputed independently rather than reused from the engine. It computes toll totals and congestion totals by summing the crossing and congestion lists directly in the loop. It applies the cap with a direct `Math.min` exactly where Section 2 places it. It then sorts the candidate list by the Section 4 total order and takes the first element.

The two implementations differ in structure by intent. The engine is written for the production path and may fold shared computation, such as the usage-day count and the toll totals, into reusable private methods and compute them once. The oracle recomputes everything the long way inside its enumeration. Because the two are written independently from the same spec and are structured differently, a bug in one is very unlikely to be mirrored in the other, which is the entire value of an oracle test. The one place the two must agree exactly is the tie-break total order, which is why Section 4 specifies it once as a strict sequence of keys, so both implement the identical comparator.

### The randomized scenario generator

The generator must make the ten thousand cases a real stress test and not an accidentally narrow one, so its parameter ranges are specified here. Each scenario is derived deterministically from a master seed plus the case index, so any failure reproduces exactly from its index.

- Crossing count. Zero to eight crossings. The zero case exercises the no-arrangement and untriggered-program paths and must appear.
- Crossing prices. For each crossing, `ezpassCents` uniform in fifty to two thousand, that is fifty cents to twenty dollars, and `cashOrMailCents` equal to `ezpassCents` plus a uniform markup of zero to fifteen hundred, so the mail rate is always greater than or equal to the E-ZPass rate as real schedules guarantee.
- Crossing dates. Each crossing is assigned a `tollDate` drawn from the rental window, with the distribution arranged so that some scenarios have a usage-day count strictly below the rental days, the parked-in-the-middle shape, and some have usage days equal to rental days, the crossing-every-day shape. Both shapes must appear.
- Rental length. One to seven days.
- Personal tag ownership. A random boolean, both values appearing.
- Congestion. With a substantial probability, include zero to `rentalDays` congestion days, each with `netEzpassCents` uniform in zero to nine hundred and `netMailCents` equal to that plus a uniform zero to six hundred, so the mail congestion rate is at least the E-ZPass rate. Fully credited days at zero must appear, and no-congestion scenarios must appear.
- Program mix. One to three programs per scenario, drawn so that all three program types appear across the suite and so that many single scenarios offer more than one type at once, which is what forces genuine competition among strategies. Per-crossing daily fees uniform in three hundred to twelve hundred, unlimited daily rates uniform in one thousand to twenty five hundred.
- Caps. About seventy percent of per-crossing programs are capped. Cap values uniform in one thousand to four thousand. Crucially, a deliberate fraction of programs set the cap near the daily fee times a plausible day count, so the cap actually binds in a meaningful share of cases rather than sitting harmlessly far above every possible fee.
- Rate basis and coverage. `tollRateBasis` random over its two values and `coversCongestion` a random boolean, both values of each appearing across the suite.
- Near-tie biasing. Uniform random values almost never produce two strategies within a few cents of each other, yet the interesting flips live exactly at those near ties. So a fraction of scenarios, about one in five, are constructed to place two strategies close together, for example by choosing an unlimited daily rate so that the flat plan total lands within a few dollars of the personal tag total or a per-crossing total. This makes the tie-break and the cap logic actually exercised under stress rather than by luck.

## Section 8, the named boundary scenarios

The plan calls for three named boundary tests by name, the cap flipping the winner, congestion coverage flipping the winner, and a zero-toll route selecting no arrangement. Each is specified below with exact numbers so the implementer writes the assertion, not the illustration.

### Boundary A, the cap flips the winner

The company offers a usage-day per-crossing program and an unlimited plan. The user owns no personal tag.

- Program P, PER_CROSSING_USAGE_DAY, daily fee six hundred ninety five cents, capped at one thousand nine hundred seventy five cents, E-ZPass basis, does not cover congestion.
- Program Q, UNLIMITED_DAILY, daily fee nine hundred ninety nine cents, congestion coverage irrelevant here.
- Rental days five. Five crossings on five distinct dates, so usage days five. Each crossing E-ZPass six hundred cents and mail nine hundred cents, so `tollEz` is three thousand. No congestion.

Program P raw fee is six hundred ninety five times five, three thousand four hundred seventy five, capped down to one thousand nine hundred seventy five. Tolls at the E-ZPass basis are three thousand. Congestion is zero. Total P is four thousand nine hundred seventy five, which is forty nine dollars and seventy five cents. Program Q is nine hundred ninety nine times five, four thousand nine hundred ninety five, which is forty nine dollars and ninety five cents. Program P wins by twenty cents.

The cap is decisive. Without the cap, program P would cost three thousand four hundred seventy five of fee plus three thousand of tolls, six thousand four hundred seventy five, and program Q at four thousand nine hundred ninety five would win. The cap pulls program P down to four thousand nine hundred seventy five and flips the winner. The rendered explanation reads, "Hertz PlatePass per-crossing wins at 49.75. Next best is the unlimited plan at 49.95, a margin of 0.20. The program admin fee reached its cap of 19.75, so extra toll days add no fee, which is what makes it cheapest."

### Boundary B, congestion coverage flips the winner

The user owns a personal tag, and the company offers an unlimited plan that covers congestion.

- Program Q, UNLIMITED_DAILY, daily fee thirteen hundred cents, covers congestion.
- Rental days three. Four crossings totaling `tollEz` of three thousand six hundred cents at the E-ZPass rate. One congestion day with net E-ZPass nine hundred cents and net mail one thousand three hundred fifty cents, so `congEz` is nine hundred and `congMail` is one thousand three hundred fifty.

The personal tag costs tolls three thousand six hundred plus congestion at the E-ZPass basis nine hundred, four thousand five hundred, which is forty five dollars. The unlimited plan costs thirteen hundred times three, three thousand nine hundred, and since it covers congestion it adds nothing, so thirty nine dollars. The unlimited plan wins by six dollars.

Congestion coverage is decisive. If the unlimited plan did not cover congestion, it would add the mail congestion of one thousand three hundred fifty, reaching five thousand two hundred fifty, and the personal tag at four thousand five hundred would win. The plan's congestion coverage is exactly what flips the winner. The rendered explanation reads, "Avis e-Toll Unlimited wins at 39.00. Next best is Personal E-ZPass at 45.00, a margin of 6.00. The plan covers the congestion charge of 13.50, which is the deciding factor against the runner up."

### Boundary C, a zero-toll route selects no arrangement

The route crosses no tolled facility and enters no congestion zone. The user owns a personal tag and the company offers both a per-crossing and an unlimited program.

- Crossings empty, congestion days empty, rental days two, personal tag owned.
- Program P, PER_CROSSING_USAGE_DAY, daily fee six hundred ninety five cents, capped at one thousand nine hundred seventy five cents.
- Program Q, UNLIMITED_DAILY, daily fee nine hundred ninety nine cents.

No-arrangement is a candidate because there are no tolls and no congestion cost, and it costs zero. The personal tag costs zero, since there are no tolls and no congestion. Program P is untriggered, so its fee days are zero, its fee is zero, its tolls are zero, and its total is zero. Program Q, being an unlimited flat plan, still charges nine hundred ninety nine times two, one thousand nine hundred ninety eight, which is nineteen dollars and ninety eight cents for nothing. The candidates at zero are no-arrangement, the personal tag, and program P. The tie-break selects no-arrangement, priority zero, over the personal tag, priority one, and program P, priority two. The winner is no-arrangement at zero. The rendered explanation reads, "No toll arrangement wins at 0.00. Next best is Personal E-ZPass at 0.00, a margin of 0.00. This route crosses no tolled facility and enters no congestion zone, so no toll product and no personal tag are needed."

## Section 9, the decision boundary figure

This worked example ties the pieces together and is the thesis figure that shows the winning strategy shifting with rental length while the usage stays fixed, which is the heart of why the engine exists. It is a weekend trip out of the city with one tolled crossing out and one back, so the usage-day count is fixed at two regardless of how many days the car is held. The combined E-ZPass toll is three thousand cents, thirty dollars, there is no congestion, and the user owns no personal tag.

The company offers three products. Program U is usage-day per-crossing at six hundred ninety five cents per usage day, capped at one thousand nine hundred seventy five, E-ZPass basis. Program A is all-rental-days per-crossing at four hundred cents per rental day, capped at two thousand, E-ZPass basis. Program N is unlimited at fifteen hundred cents per rental day, covering tolls. With usage fixed at two, program U is flat at min of six hundred ninety five times two and the cap, one thousand three hundred ninety, plus three thousand of tolls, four thousand three hundred ninety on every length. Program A grows with rental days, four hundred times the rental days capped at two thousand, plus three thousand. Program N grows with rental days, fifteen hundred times the rental days.

| Rental days | Program U | Program A | Program N | Winner |
| --- | --- | --- | --- | --- |
| 2 | 43.90 | 38.00 | 30.00 | Unlimited N |
| 3 | 43.90 | 42.00 | 45.00 | All-days A |
| 4 | 43.90 | 46.00 | 60.00 | Usage-day U |
| 5 | 43.90 | 50.00 | 75.00 | Usage-day U |
| 7 | 43.90 | 50.00 | 105.00 | Usage-day U |

The winner moves from the unlimited plan at two days, to the all-rental-days program at three days, to the usage-day program at four days and beyond, purely as the rental lengthens while the tolls stay fixed. This is the decision boundary the thesis reports, and it is also the sharpest demonstration of Section 3. At four rental days the true winner is the usage-day program at forty three dollars and ninety cents on a usage-day count of two, but if the engine were handed a wrong usage-day count of three, the usage-day total would rise to the capped fee of nineteen dollars and seventy five cents plus thirty dollars, forty nine dollars and seventy five cents, and the winner would flip to the all-rental-days program at forty six dollars. The recommendation is only correct when the usage-day count is derived from the real timeline.

## Section 10, summary of decisions for the implementer

- The engine is a pure function, zero I/O, no clock, deterministic, taking a `StrategyInput` and returning a `StrategyResult`.
- Money is long cents throughout, and all arithmetic is integer add, integer multiply by a small count, and `Math.min`.
- Program type and fee basis are one three valued `ProgramType` enum, not two independent fields, which removes contradictory states.
- The cap is modeled as a boolean `feeCapped` plus a `long feeCapCents`, so a real cap of zero is distinct from the absence of a cap.
- The usage-day count is derived inside the engine as the count of distinct crossing dates, not accepted as a separate input, and a congestion day does not create a usage day because congestion days coincide with crossing days in the NYC trip space.
- Uncovered congestion adds `congEz` for the personal tag and `congMail` for any company strategy, and covered congestion adds zero.
- The cap bounds only the admin fee, applied to daily fee times fee days, and the fee basis differs only in that fee-day count.
- The winner is the lowest total, with the tie-break total order NO_ARRANGEMENT, then PERSONAL_TAG, then COMPANY_PER_CROSSING, then COMPANY_UNLIMITED, then program name ascending, implemented identically by the engine and the oracle.
- No-arrangement is a candidate only when there are zero tolls and zero congestion cost, and it wins any tie at zero.
- The personal tag plus unlimited plan combination is never evaluated because it is weakly dominated by the two standalone strategies the engine already ranks.
- The oracle is an independent, spec-driven, structurally different, literally transcribed implementation, sharing only the tie-break order with the engine.
- The result carries the winner, the total, every candidate breakdown, and a human readable explanation naming the winner, the runner up, the margin, and the decisive factor, and the explanation is not asserted by the property test.
