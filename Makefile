COMPOSE = docker compose -f deploy/compose.yaml

.PHONY: help up down run prefetch images test itest psql redis kafka-topics seed osrm k6-smoke demo-load demo-reset demo-check k8s-up k8s-down eval up-all down-all logs

help:
	@echo "truecost make targets"
	@echo ""
	@echo "  up            start the infra only compose stack, Postgres, Redis, Kafka, Apicurio, OSRM, observability"
	@echo "  up-all        build and start everything in containers, infra plus seed, API, and prefetcher"
	@echo "  down          stop and remove the compose stack"
	@echo "  down-all      stop and remove the full containerized stack including volumes"
	@echo "  logs          follow the API and prefetcher container logs"
	@echo "  run           run the API with the dev profile against the compose stack, host process"
	@echo "  prefetch      run the Kafka Streams prefetcher deployable against the compose stack, host process"
	@echo "  images        build the API and prefetcher Docker images from deploy/Dockerfile"
	@echo "  test          run unit and Testcontainers integration tests"
	@echo "  itest         run only the integration tests, filtered by naming convention"
	@echo "  psql          open psql against the local Postgres"
	@echo "  redis         open redis-cli against the local Redis"
	@echo "  kafka-topics  list Kafka topics on the local broker"
	@echo "  seed          load reference data CSVs into Postgres, requires make up first"
	@echo "  osrm          one time OSRM data preparation, run scripts/osrm-setup.sh"
	@echo "  k6-smoke      run the trip plan load smoke, requires make up, make run, and OSRM up"
	@echo "  demo-load     run repeated demo traffic to climb cache hit rate and coalesced counters live"
	@echo "  demo-reset    restart the API and clear the cache for a clean metrics deck before presenting"
	@echo "  demo-check    preflight, confirm the API, OSRM, and a plan request are all healthy"
	@echo "  k8s-up        create a kind cluster and deploy the API and prefetcher, from zero"
	@echo "  k8s-loadtest  run the in cluster k6 load job to drive the API HPA"
	@echo "  k8s-down      delete the kind cluster"
	@echo ""
	@echo "  not yet implemented, print a phase pointer and exit"
	@echo "  eval"

up:
	$(COMPOSE) up -d

up-all:
	$(COMPOSE) --profile app up -d --build

down:
	$(COMPOSE) down

down-all:
	$(COMPOSE) --profile app down -v

logs:
	$(COMPOSE) logs -f api prefetcher

run:
	SPRING_PROFILES_ACTIVE=dev ./mvnw spring-boot:run

prefetch:
	SPRING_PROFILES_ACTIVE=dev ./mvnw spring-boot:run \
		-Dspring-boot.run.main-class=com.truecost.stream.PrefetcherApplication \
		-Dspring-boot.run.jvmArguments="-Dtruecost.events.enabled=true -Dserver.port=8082"

images:
	docker build --target api -t truecost-api -f deploy/Dockerfile .
	docker build --target prefetcher -t truecost-prefetcher -f deploy/Dockerfile .

test:
	./mvnw test

itest:
	./mvnw test -Dtest='*IntegrationTest'

psql:
	psql postgresql://truecost:truecost@localhost:5432/truecost

redis:
	redis-cli -h localhost -p 6379

kafka-topics:
	$(COMPOSE) exec kafka /opt/kafka/bin/kafka-topics.sh --bootstrap-server localhost:9092 --list

seed:
	./scripts/seed.sh

osrm:
	./scripts/osrm-setup.sh

k6-smoke:
	k6 run eval/k6/trip-plan-smoke.js

demo-load:
	k6 run eval/k6/demo-load.js

demo-reset:
	$(COMPOSE) --profile app restart api
	-$(COMPOSE) exec redis redis-cli FLUSHALL
	@echo "clean deck, metrics reset and cache cleared, wait a few seconds for the API to report healthy"

demo-check:
	@curl -sf localhost:8080/actuator/health >/dev/null && echo "api    UP" || echo "api    DOWN"
	@curl -sf 'localhost:5001/route/v1/driving/-73.99,40.75;-75.16,39.95?overview=false' >/dev/null && echo "osrm   UP" || echo "osrm   DOWN"
	@curl -sf -o /dev/null -w "plan   HTTP %{http_code}\n" localhost:8080/api/v1/trips/plan -H 'Content-Type: application/json' -d @eval/golden/sample-request.json

k8s-up:
	./scripts/k8s-up.sh

k8s-down:
	./scripts/k8s-down.sh

k8s-loadtest:
	kubectl -n truecost create configmap k6-scripts \
		--from-file=hpa-load.js=eval/k6/hpa-load.js \
		--dry-run=client -o yaml | kubectl apply -f -
	kubectl -n truecost delete job hpa-load --ignore-not-found
	kubectl -n truecost apply -f deploy/k8s/base/loadtest.yaml
	@echo "load running. watch the API scale with: kubectl -n truecost get hpa api -w"

eval:
	@echo "not yet implemented, see PLAN.md phase 9"
