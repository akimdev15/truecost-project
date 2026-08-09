# Fuel strategy engine design, post-demo feature

This document specifies a fuel strategy engine that decides, per rental option, the cheapest way to
pay for the fuel a trip needs, exactly as the existing `toll/strategy/TollStrategyEngine` decides
the cheapest way to pay for tolls. It is written so a Sonnet agent implements it in Java without
further architectural judgment calls. The prose follows the project code standard, no em dashes, no
en dashes, and no semicolons. Money is a long count of whole cents throughout, matching the
`Money` value type. Model delegation follows CLAUDE.md, this Opus-level design fixes the engine
boundaries and the data model, a Sonnet agent writes the engine, the oracle, the property test, and
the wiring.

## Section 0, what exists and what this adds

Today fuel is a single naive estimate. `provider/fuel/FuelCostService` computes
`gallonsNeeded times pumpPrice` where `gallonsNeeded = totalDistanceMiles / mpg`, the pump price
comes from the EIA regional feed with a static fallback, and the MPG comes from
`vehicle_class.default_mpg`. That estimate is the cost of refueling the car yourself. It is the same
for every rental company, which is why the fuel line looks constant across options.

This feature replaces that single estimate with a decision across the real fuel purchase options a
renter actually faces, and the winning option becomes the fuel line. Because two of the three
options are priced from company specific rates, the fuel figure then varies by company, which is
the correct behavior and closes the gap a user noticed during the demo.

New pieces this feature adds.

- A `fuel_program` reference table and seed, one row per rental company, sourced the same manual way
  as `toll_programs.csv`.
- A `tank_gallons` column on `vehicle_class`, the tank capacity a prepaid full tank is priced from.
- A pure function package `fuel/strategy/`, the `FuelStrategyEngine`, its input and result records,
  and the brute force oracle used only in tests.
- Wiring in `aggregate/TripPlanner` and the `api/dto` records so the response carries the chosen
  fuel strategy and its explanation, mirroring `ChosenStrategy`.

It consumes, unchanged, `FuelCostService` for the pump price and the gallons math, the
`vehicle_class` MPG, and the `totalDistanceMiles` the aggregator already computes.

## Section 1, the decision being modeled

A renter has three real ways to pay for fuel. The names vary by company, the shapes do not.

1. Self refuel, the full to full default. Return the car with a full tank, the company charges
   nothing for fuel, and the renter buys gas at the pump. Cost to the renter is
   `gallonsNeeded times pumpPriceCentsPerGallon`. This is the baseline every company offers.

2. Prepaid full tank, the fuel purchase option. Buy one full tank upfront at the company's
   advertised per gallon rate, return the car as empty as comfortable, and skip the refueling stop.
   Cost is `tankGallons times prepaidRateCentsPerGallon`, the whole tank, because the common
   contract gives no credit for unused fuel. It only wins when the prepaid rate is at or below pump
   and the trip burns close to a full tank, otherwise the renter pays for gas they do not use.

3. Refueling service, the return it empty and let them fill it option. Return the car not full and
   the company refuels it at a punitive per gallon rate, well above pump. Cost is
   `gallonsNeeded times refuelServiceRateCentsPerGallon`. It essentially never wins on price, it is
   modeled so the engine can prove it never wins and so the explanation can name it as the expensive
   fallback a renter should avoid.

The engineering question, per rental option, is identical in shape to the toll question, given the
route's gallons needed, the car's tank size, the local pump price, and this company's prepaid and
refueling rates, which of the three is cheapest. The decision boundary, prepaid overtaking self
refuel as trip distance rises toward a full tank, is the fuel analog of the toll strategy boundary
and is the second thesis figure this feature produces.

## Section 2, the reference data

### 2.1 `fuel_program`, one row per company

Collected manually from each company's rental terms and fuel policy pages into
`data/seeds/fuel_programs.csv`, verified and snapshot dated the same way `toll_programs.csv` is. The
structural fields matter more than the exact numbers, which drift.

```
fuel_program
  company_code                       text, foreign key to rental_company
  prepaid_rate_cents_per_gallon      long, the advertised prepaid full tank rate, nullable when the
                                     company does not offer prepaid
  prepaid_credits_unused             boolean, true only for the rare company that refunds the
                                     unused portion of a prepaid tank, default false
  refuel_service_rate_cents_per_gallon  long, the punitive rate charged when the car is returned
                                     not full, never null, every company has one
  effective_date                     date
```

When `prepaid_rate_cents_per_gallon` is null the prepaid strategy is simply not a candidate for that
company, the same way a company with no unlimited toll plan contributes no unlimited candidate.

### 2.2 `tank_gallons` on `vehicle_class`

`vehicle_class` already carries `default_mpg`. Add `tank_gallons`, a decimal, the usable tank
capacity used to price a prepaid full tank. Seed realistic values per class, roughly 12 gallons for
Economy up to roughly 24 gallons for a large SUV or minivan. This is a static seed like the MPG
table, not a per model lookup.

## Section 3, the pure engine

The engine lives in `fuel/strategy/`, has zero input or output beyond its arguments, and is
exhaustively testable in isolation, matching the toll engine's constraints.

### 3.1 Input

```
FuelStrategyInput
  gallonsNeeded                double, totalDistanceMiles divided by the car's mpg, round trip
  tankGallons                  double, the car's tank capacity
  pumpPriceCentsPerGallon      long, the resolved regional pump price
  prepaidRateCentsPerGallon    Long, nullable, absent means no prepaid candidate
  prepaidCreditsUnused         boolean
  refuelServiceRateCentsPerGallon  long
```

The engine takes primitives only, the aggregator resolves `gallonsNeeded`, `tankGallons`, and the
pump price before calling it, so the engine never touches a repository, a clock, or a provider.

### 3.2 Strategies and their cost

```
FuelStrategy
  SELF_REFUEL         gallonsNeeded times pumpPriceCentsPerGallon
  PREPAID_TANK        prepaidCreditsUnused
                        ? gallonsNeeded times prepaidRateCentsPerGallon
                        : tankGallons times prepaidRateCentsPerGallon
  REFUEL_SERVICE      gallonsNeeded times refuelServiceRateCentsPerGallon
```

All arithmetic rounds to whole cents at the final step, never mid computation, and uses long math on
cents to stay off floating point in the money path, gallons stay double only until multiplied by a
cents rate. `PREPAID_TANK` is a candidate only when a prepaid rate is present. The
`prepaidCreditsUnused` branch exists so the rare credit granting company is modeled honestly rather
than penalized, in that branch prepaid is priced on gallons used, not the whole tank.

### 3.3 Output

```
FuelStrategyCost
  strategy         the FuelStrategy
  totalCents       long
  gallonsBilled    double, gallonsNeeded or tankGallons depending on the strategy, for the breakdown

FuelStrategyResult
  winner           the FuelStrategy with the lowest totalCents, ties broken SELF_REFUEL first,
                   then PREPAID_TANK, then REFUEL_SERVICE, a total order
  totalCents       the winner's cost
  candidates       every evaluated FuelStrategyCost in ascending cost order
  explanation      a human readable sentence naming the winner, the runner up, the margin, and the
                   one factor that decided it, in the exact voice TollStrategyEngine.explain uses
```

The explanation must be correct for whichever strategy wins, the toll engine had a bug where a
fallback sentence assumed self refuel won, do not repeat it, write one clear sentence per winning
strategy.

### 3.4 The oracle

A separate brute force function used only in tests enumerates all present strategies, computes each
cost directly from the formulas in 3.2, and returns the minimum. The engine result must equal the
oracle result for every input, which is the property test's assertion.

## Section 4, integration

`aggregate/TripPlanner` already computes `totalDistanceMiles` and resolves the pump price and the per
class MPG through `FuelCostService`. For each rental option it now also.

1. Reads the company's `fuel_program` row and the car class `tank_gallons`.
2. Builds a `FuelStrategyInput` and calls `FuelStrategyEngine.evaluate`.
3. Sets the option's `fuelCents` to the winning `totalCents`, replacing the old self refuel only
   number, and attaches a `ChosenFuelStrategy` record to the option.

`FuelCostService` keeps computing `gallonsNeeded` and the pump price, it is the input source, the
engine is the decision. The fuel figure now legitimately varies by company, so the response note in
the aggregation design that calls hotel and fuel per trip constants is updated, fuel becomes an
option level figure, hotel stays a per trip constant.

New response record, mirroring `ChosenStrategy`.

```
ChosenFuelStrategy
  kind             SELF_REFUEL, PREPAID_TANK, or REFUEL_SERVICE
  totalCents       long
  gallonsBilled    double
  explanation      the engine's sentence
  candidates       every FuelStrategyCost, for the compare drawer
```

`TripOption` gains one field, `ChosenFuelStrategy fuelStrategy`. The `trueTotalCents` sum is
unchanged in shape, it already adds `fuelCents`, that value is now the winning fuel strategy cost
rather than the naive estimate.

## Section 5, the UI

The results card already renders a toll strategy panel with a compare drawer. Add the same treatment
for fuel, a chosen fuel method label, the one sentence explanation, and a compare all fuel strategies
drawer listing the candidates with gallons billed and cost. A session level fuel strategy mix panel,
mirroring the toll strategy wins card, is optional and low priority, add it only if the fuel decision
turns out to vary enough across a demo to be worth showing.

## Section 6, testing and acceptance

- Property test, at least ten thousand randomized scenarios across gallons needed, tank sizes, pump
  prices, and the presence or absence of a prepaid rate, the engine result equals the oracle in
  every case.
- Boundary tests, a short trip keeps self refuel, a long trip whose gallons approach a full tank with
  a prepaid rate below pump flips to prepaid, and refuel service never wins when either other option
  is present.
- Full branch coverage on the `fuel/strategy` package, including the `prepaidCreditsUnused` branch
  and the absent prepaid rate branch.
- The chosen fuel strategy carries a human readable explanation that is correct for the winning
  strategy, asserted for at least one self refuel win and one prepaid win.
- An end to end assertion that two companies with different prepaid rates produce different fuel
  figures for the same trip and car class, proving fuel is now company specific.

## Section 7, open decisions to confirm before building

All have a recommended default so the build is not blocked, confirm them while collecting the seed
data.

- Prepaid unused fuel credit. Recommendation, model the punitive no credit case as the default and
  carry `prepaid_credits_unused` for the exceptions, since no credit is the common real contract.
- Tank capacity source. Recommendation, a static `tank_gallons` per vehicle class in the seed, not a
  per model lookup, matching how MPG is already handled.
- Refueling service shape. Recommendation, a per gallon punitive rate, the dominant modern model,
  rather than a flat convenience fee, since the per gallon form subsumes the decision cleanly.
- Pump price source. Recommendation, keep the existing EIA regional feed with its static fallback,
  a true per route gas price is not freely available, and record the snapshot date in the thesis as
  the toll rates already are.
- Whether fuel gets its own session mix panel in the UI. Recommendation, defer it, the per option
  fuel strategy and compare drawer are enough to demonstrate the decision.
