#!/bin/sh
set -eu
if [ "$#" -ne 2 ]; then
    echo 'Usage: sh tools/sign-release.sh /path/to/release.p12 /path/to/password.txt' >&2
    exit 2
fi
ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
KEYSTORE=$(realpath -- "$1")
PASSWORD=$(realpath -- "$2")
test -f "$KEYSTORE"
test -f "$PASSWORD"
test -f "$ROOT/build/proof/aligned.apk"
IMAGE='ghcr.io/cirruslabs/android-sdk:36@sha256:f9b3ea9ed2b5fc9522adae82c7b4622ab7aa54207ef532c8e615a347dca08f31'
CONTAINER_USER="$(id -u):$(id -g)"
DOCKER_SECURITY=$(docker info --format '{{json .SecurityOptions}}')
# Rootless container UID 0 maps to the unprivileged host owner.
case "$DOCKER_SECURITY" in
    *'"name=rootless"'*) CONTAINER_USER=0:0 ;;
esac
docker run --rm --network none --read-only --cap-drop ALL \
    --security-opt no-new-privileges --tmpfs /tmp:rw,nosuid,nodev \
    --user "$CONTAINER_USER" \
    --volume "$ROOT/build:/build" \
    --volume "$KEYSTORE:/signing/release.p12:ro" \
    --volume "$PASSWORD:/signing/password.txt:ro" \
    --workdir /build "$IMAGE" sh -eu -c '
        tools="$ANDROID_HOME/build-tools/36.0.0"
        "$tools/apksigner" sign --ks /signing/release.p12 \
            --ks-key-alias release --ks-pass file:/signing/password.txt \
            --v4-signing-enabled false \
            --out vban-release.apk proof/aligned.apk
        "$tools/apksigner" verify --verbose --print-certs vban-release.apk
        sha256sum vban-release.apk > vban-release.apk.sha256
    '
