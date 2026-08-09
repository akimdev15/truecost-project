# Multi stage build producing the two TrueCost deployables from one source tree, the synchronous
# API and the Kafka Streams prefetcher, per PLAN.md's Phase 6 deployment topology. The build stage
# compiles once and both runtime stages copy their own boot jar out of it, so the two images share
# every layer up to the jar. Select an image with the build target, for example
#   docker build --target api -t truecost-api .
#   docker build --target prefetcher -t truecost-prefetcher .

FROM eclipse-temurin:21-jdk AS build
WORKDIR /workspace
COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
RUN ./mvnw -q -B dependency:go-offline
COPY src/ src/
RUN ./mvnw -q -B -DskipTests package

FROM eclipse-temurin:21-jre AS api
WORKDIR /app
COPY --from=build /workspace/target/truecost-0.0.1-SNAPSHOT.jar app.jar
# SyntheticRentalProvider and the seed loader read the reference CSVs from data/seeds at runtime,
# relative to the working directory, so the seed data ships in the image. The multi gigabyte OSRM
# data does not, it is a mounted volume, and .dockerignore keeps it out of the build context.
COPY data/seeds ./data/seeds
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]

FROM eclipse-temurin:21-jre AS prefetcher
WORKDIR /app
COPY --from=build /workspace/target/truecost-0.0.1-SNAPSHOT-prefetcher.jar app.jar
COPY data/seeds ./data/seeds
# The prefetcher publishes a QuoteSnapshot for every quote it warms, so it runs with events on.
ENV TRUECOST_EVENTS_ENABLED=true
ENTRYPOINT ["java", "-jar", "app.jar"]
