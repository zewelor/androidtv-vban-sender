#!/bin/sh
set -eu
ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
IMAGE='ghcr.io/cirruslabs/android-sdk:36@sha256:f9b3ea9ed2b5fc9522adae82c7b4622ab7aa54207ef532c8e615a347dca08f31'
mkdir -p "$ROOT/build"
exec docker run --rm --network none --read-only --cap-drop ALL \
    --security-opt no-new-privileges --tmpfs /tmp:rw,nosuid,nodev \
    --user "$(id -u):$(id -g)" \
    --volume "$ROOT:/workspace:ro" --volume "$ROOT/build:/workspace/build" \
    --workdir /workspace "$IMAGE" sh tools/build-in-container.sh
