#!/usr/bin/env bash
# Loads data/seeds/*.csv into Postgres. Truncates the reference tables and reloads them, so
# running this twice in a row leaves the same rows in place, not duplicates.
# Requires the compose stack, run make up first.
set -euo pipefail

cd "$(dirname "$0")/.."

SPRING_PROFILES_ACTIVE=seed ./mvnw spring-boot:run
