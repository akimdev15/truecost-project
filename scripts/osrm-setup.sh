#!/usr/bin/env bash
# Downloads the Geofabrik us-northeast OpenStreetMap extract and prepares it for OSRM, the self
# hosted routing engine, using the modern multi level Dijkstra pipeline, osrm-extract then
# osrm-partition then osrm-customize, which the osrm-backend image documents as the replacement
# for the older contraction hierarchies osrm-contract pipeline.
#
# This is a one time, multi gigabyte, multi minute step, run it once before make osrm serves
# real routes, and rerun it whenever the extract needs refreshing. The prepared files live in
# data/osrm, which is gitignored, so every teammate runs this script locally rather than the
# prepared data being committed.
#
# Usage, from the project root, requires Docker:
#   scripts/osrm-setup.sh
#   scripts/osrm-setup.sh --force   # redo every step even if its output already exists
set -euo pipefail

cd "$(dirname "$0")/.."

DATA_DIR="data/osrm"
EXTRACT_NAME="us-northeast-latest"
EXTRACT_URL="https://download.geofabrik.de/north-america/us-northeast-latest.osm.pbf"
OSRM_IMAGE="ghcr.io/project-osrm/osrm-backend:latest"
FORCE=false

if [[ "${1:-}" == "--force" ]]; then
    FORCE=true
fi

mkdir -p "$DATA_DIR"

run_osrm() {
    docker run --rm -v "$(pwd)/$DATA_DIR:/data" "$OSRM_IMAGE" "$@"
}

pbf_path="$DATA_DIR/$EXTRACT_NAME.osm.pbf"
if [[ "$FORCE" == true || ! -f "$pbf_path" ]]; then
    echo "downloading $EXTRACT_URL, this is close to two gigabytes"
    curl -L --fail -o "$pbf_path.tmp" "$EXTRACT_URL"
    mv "$pbf_path.tmp" "$pbf_path"
else
    echo "reusing existing extract at $pbf_path, pass --force to redownload"
fi

osrm_path="$DATA_DIR/$EXTRACT_NAME.osrm"
if [[ "$FORCE" == true || ! -f "$osrm_path" ]]; then
    echo "running osrm-extract with the car profile, this builds the routing graph from the raw map data"
    run_osrm osrm-extract -p /opt/car.lua "/data/$EXTRACT_NAME.osm.pbf"
else
    echo "reusing existing extract graph at $osrm_path, pass --force to rerun osrm-extract"
fi

partition_marker="$DATA_DIR/$EXTRACT_NAME.osrm.partition"
if [[ "$FORCE" == true || ! -f "$partition_marker" ]]; then
    echo "running osrm-partition, this builds the multi level partition used for fast queries"
    run_osrm osrm-partition "/data/$EXTRACT_NAME.osrm"
else
    echo "reusing existing partition at $partition_marker, pass --force to rerun osrm-partition"
fi

customize_marker="$DATA_DIR/$EXTRACT_NAME.osrm.cell_metrics"
if [[ "$FORCE" == true || ! -f "$customize_marker" ]]; then
    echo "running osrm-customize, this computes the cell weights the multi level Dijkstra query needs"
    run_osrm osrm-customize "/data/$EXTRACT_NAME.osrm"
else
    echo "reusing existing customized data at $customize_marker, pass --force to rerun osrm-customize"
fi

echo "osrm data preparation complete under $DATA_DIR"
echo "bring up the routing service with, docker compose -f deploy/compose.yaml up -d osrm"
echo "then check it with, curl -s 'localhost:5001/route/v1/driving/-73.99,40.75;-75.16,39.95?overview=false' | jq '.routes[0].duration'"
