#!/bin/sh
set -eu
ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
IMAGE='ghcr.io/cirruslabs/android-sdk:36@sha256:f9b3ea9ed2b5fc9522adae82c7b4622ab7aa54207ef532c8e615a347dca08f31'
CONTAINER_USER="$(id -u):$(id -g)"
DOCKER_SECURITY=$(docker info --format '{{json .SecurityOptions}}')
# Rootless container UID 0 maps to the unprivileged host owner.
case "$DOCKER_SECURITY" in
    *'"name=rootless"'*) CONTAINER_USER=0:0 ;;
esac
mkdir -p "$ROOT/build"
exec docker run --rm --network none --read-only --cap-drop ALL \
    --security-opt no-new-privileges --tmpfs /tmp:rw,nosuid,nodev \
    --user "$CONTAINER_USER" \
    --volume "$ROOT:/workspace:ro" --volume "$ROOT/build:/workspace/build" \
    --workdir /workspace "$IMAGE" sh tools/build-in-container.sh
