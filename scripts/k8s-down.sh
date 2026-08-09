#!/usr/bin/env bash
# Deletes the kind cluster the Phase 8 exercise created, tearing down every deployment, the infra,
# and the observability stack in one step.
set -euo pipefail

CLUSTER=truecost

if kind get clusters | grep -qx "$CLUSTER"; then
  kind delete cluster --name "$CLUSTER"
else
  echo "no kind cluster named $CLUSTER"
fi
