#!/usr/bin/env bash
# Phase 8, from zero to a served request through a kind cluster in one script. Creates the cluster,
# builds the two images, loads them into the cluster nodes, installs metrics-server so the HPAs have
# a CPU signal, applies the kustomize manifests, waits for the app rollouts, and prints how to reach
# the API. Idempotent, rerunning it reuses an existing cluster and reapplies the manifests.
set -euo pipefail

CLUSTER=truecost
ROOT="$(cd "$(dirname "$0")/.." && pwd)"

require() { command -v "$1" >/dev/null 2>&1 || { echo "missing required tool: $1" >&2; exit 1; }; }
require kind
require kubectl
require docker

if ! kind get clusters | grep -qx "$CLUSTER"; then
  echo "creating kind cluster $CLUSTER"
  kind create cluster --name "$CLUSTER"
else
  echo "reusing existing kind cluster $CLUSTER"
fi

echo "building images"
docker build --target api -t truecost-api:latest -f "$ROOT/deploy/Dockerfile" "$ROOT"
docker build --target prefetcher -t truecost-prefetcher:latest -f "$ROOT/deploy/Dockerfile" "$ROOT"

echo "loading images into the cluster"
kind load docker-image truecost-api:latest --name "$CLUSTER"
kind load docker-image truecost-prefetcher:latest --name "$CLUSTER"

echo "installing metrics-server for the HPAs"
kubectl apply -f https://github.com/kubernetes-sigs/metrics-server/releases/latest/download/components.yaml
# kind's kubelet serves a self signed cert, so metrics-server must be told to skip TLS verification.
kubectl -n kube-system patch deployment metrics-server --type=json \
  -p='[{"op":"add","path":"/spec/template/spec/containers/0/args/-","value":"--kubelet-insecure-tls"}]'

echo "applying manifests"
kubectl apply -k "$ROOT/deploy/k8s/base"

echo "waiting for infra to be ready"
kubectl -n truecost rollout status deployment/postgres --timeout=180s
kubectl -n truecost rollout status deployment/kafka --timeout=180s

echo "waiting for the reference data seed job to complete"
# The seed Job loads the reference data the API needs to price a trip, and it retries until Postgres
# is ready. Waiting for it here means a plan request right after this script finishes has its
# reference data, so zero to a served request holds rather than racing the seed.
kubectl -n truecost wait --for=condition=complete job/seed --timeout=240s

echo "waiting for app rollouts"
kubectl -n truecost rollout status deployment/api --timeout=180s
kubectl -n truecost rollout status deployment/prefetcher --timeout=180s

cat <<'EOF'

cluster is up. Reach the API with a port-forward in another terminal:

  kubectl -n truecost port-forward svc/api 8080:8080

then

  curl -s localhost:8080/actuator/health | jq

Grafana:     kubectl -n truecost port-forward svc/grafana 3000:3000
Prometheus:  kubectl -n truecost port-forward svc/prometheus 9090:9090

Tear the whole thing down with make k8s-down.
EOF
