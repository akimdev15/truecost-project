#!/usr/bin/env bash
# Downloads the Geofabrik us-northeast OpenStreetMap extract and prepares it for OSRM, the self
# hosted routing engine, using the modern multi level Dijkstra pipeline, osrm-extract then
# osrm-partition then osrm-customize, which the osrm-backend image documents as the replacement
# for the older contraction hierarchies osrm-contract pipeline.
#
# By default this clips the download to the NYC metro bounding box before preparing it, because
# the full us-northeast extract needs more memory for the osrm-extract edge expansion step than a
# typical laptop Docker allocation has, and because deploy/compose.yaml serves nyc-metro.osrm.
# Set TRUECOST_OSRM_EXTENT=full to prepare the whole us-northeast extract instead, which needs
# roughly 12 GB of Docker memory and produces us-northeast-latest.osrm. If you do that, point the
# osrm service command in deploy/compose.yaml at that file.
#
# This is a one time, multi gigabyte, multi minute step, run it once before the osrm service can
# serve real routes, and rerun it whenever the extract needs refreshing. The prepared files live
# in data/osrm, which is gitignored, so every teammate runs this script locally rather than the
# prepared data being committed.
#
# Usage, from the project root, requires Docker:
#   scripts/osrm-setup.sh
#   scripts/osrm-setup.sh --force              # redo every step even if its output already exists
#   TRUECOST_OSRM_EXTENT=full scripts/osrm-setup.sh
set -euo pipefail

cd "$(dirname "$0")/.."

DATA_DIR="data/osrm"
SOURCE_NAME="us-northeast-latest"
SOURCE_URL="https://download.geofabrik.de/north-america/us-northeast-latest.osm.pbf"
OSRM_IMAGE="ghcr.io/project-osrm/osrm-backend:latest"
OSMIUM_IMAGE="stefda/osmium-tool"

# Covers New York City, all of New Jersey, Connecticut, the Hudson Valley up through New Paltz,
# and Long Island out to Montauk. Boston and Washington DC fall outside it on purpose, they are
# outside the seeded toll coverage the strategy engine can price, see FR14.
CLIP_BBOX="-75.35,39.85,-71.5,41.8"

EXTENT="${TRUECOST_OSRM_EXTENT:-metro}"
FORCE=false
if [[ "${1:-}" == "--force" ]]; then
    FORCE=true
fi

case "$EXTENT" in
    metro) TARGET_NAME="nyc-metro" ;;
    full)  TARGET_NAME="$SOURCE_NAME" ;;
    *)     echo "TRUECOST_OSRM_EXTENT must be metro or full, got $EXTENT" >&2; exit 1 ;;
esac

mkdir -p "$DATA_DIR"

run_osrm() {
    docker run --rm -v "$(pwd)/$DATA_DIR:/data" "$OSRM_IMAGE" "$@"
}

step_needed() {
    [[ "$FORCE" == true || ! -f "$1" ]]
}

source_pbf="$DATA_DIR/$SOURCE_NAME.osm.pbf"
if step_needed "$source_pbf"; then
    echo "downloading $SOURCE_URL, this is close to two gigabytes"
    curl -L --fail -o "$source_pbf.tmp" "$SOURCE_URL"
    mv "$source_pbf.tmp" "$source_pbf"
else
    echo "reusing existing source extract at $source_pbf, pass --force to redownload"
fi

target_pbf="$DATA_DIR/$TARGET_NAME.osm.pbf"
if [[ "$EXTENT" == "metro" ]]; then
    if step_needed "$target_pbf"; then
        echo "clipping to the NYC metro bounding box $CLIP_BBOX, this keeps osrm-extract inside a laptop memory budget"
        docker run --rm -v "$(pwd)/$DATA_DIR:/data" "$OSMIUM_IMAGE" \
            osmium extract -b "$CLIP_BBOX" "/data/$SOURCE_NAME.osm.pbf" -o "/data/$TARGET_NAME.osm.pbf"
    else
        echo "reusing existing clipped extract at $target_pbf, pass --force to reclip"
    fi
fi

# osrm-extract writes a set of files sharing the .osrm base name, there is no bare .osrm file, so
# the completion marker is one of the files it actually produces. Checking for the base name
# itself silently reran this multi minute step on every invocation.
extract_marker="$DATA_DIR/$TARGET_NAME.osrm.ebg"
if step_needed "$extract_marker"; then
    echo "running osrm-extract with the car profile, this builds the routing graph from the raw map data"
    echo "if this is killed with no error, Docker ran out of memory, stop the app stack first with"
    echo "  docker compose -f deploy/compose.yaml --profile app stop"
    run_osrm osrm-extract -p /opt/car.lua "/data/$TARGET_NAME.osm.pbf"
else
    echo "reusing existing extract graph at $extract_marker, pass --force to rerun osrm-extract"
fi

partition_marker="$DATA_DIR/$TARGET_NAME.osrm.partition"
if step_needed "$partition_marker"; then
    echo "running osrm-partition, this builds the multi level partition used for fast queries"
    run_osrm osrm-partition "/data/$TARGET_NAME.osrm"
else
    echo "reusing existing partition at $partition_marker, pass --force to rerun osrm-partition"
fi

customize_marker="$DATA_DIR/$TARGET_NAME.osrm.cell_metrics"
if step_needed "$customize_marker"; then
    echo "running osrm-customize, this computes the cell weights the multi level Dijkstra query needs"
    run_osrm osrm-customize "/data/$TARGET_NAME.osrm"
else
    echo "reusing existing customized data at $customize_marker, pass --force to rerun osrm-customize"
fi

echo
echo "osrm data preparation complete, serving file is $DATA_DIR/$TARGET_NAME.osrm"
echo "recording the input checksum so an accuracy run can name the map it was measured against:"
shasum -a 256 "$target_pbf" | tee "$DATA_DIR/$TARGET_NAME.osm.pbf.sha256"
echo
echo "bring up the routing service with, docker compose -f deploy/compose.yaml up -d osrm"
echo "then check it with, curl -s 'localhost:5001/route/v1/driving/-73.99,40.75;-75.16,39.95?overview=false' | jq '.routes[0].duration'"
