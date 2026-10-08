#!/bin/sh
set -eu
SDK_ROOT=${ANDROID_HOME:?Android SDK image must define ANDROID_HOME}
ANDROID_JAR="$SDK_ROOT/platforms/android-36/android.jar"
TOOLS="$SDK_ROOT/build-tools/36.0.0"
rm -rf build/classes build/test-classes build/dex
mkdir -p build/classes build/test-classes build/dex
find src vendor/adblib/src -name '*.java' | sort > build/sources.list
javac -source 8 -target 8 -bootclasspath "$ANDROID_JAR" -d build/classes @build/sources.list
javac --release 8 -cp build/classes -d build/test-classes tests/ProtocolCheck.java tests/RoutingCheck.java tests/AppAwareHarness.java tests/AdbClientCheck.java tests/AdbClientHarness.java tests/CapturePacketsHarness.java tests/AudioControllerCheck.java
java -cp build/classes:build/test-classes app.vbansender.ProtocolCheck
java -cp build/classes:build/test-classes app.vbansender.RoutingCheck
java -cp build/classes:build/test-classes com.cgutman.adblib.AdbClientCheck
java -cp build/classes:build/test-classes app.vbansender.app.AudioControllerCheck
find build/classes -name '*.class' -print0 | xargs -0 "$TOOLS/d8" \
    --lib "$ANDROID_JAR" --min-api 31 --output build/dex
mkdir -p build/proof/assets/licenses
cp LICENSE build/proof/assets/licenses/vban-sender.txt
cp third-party-scrcpy-LICENSE build/proof/assets/licenses/scrcpy.txt
cp vendor/adblib/LICENSE build/proof/assets/licenses/adblib.txt
jar --create --file build/vban-engine.jar -C build/dex classes.dex \
    -C build/proof/assets licenses
python3 tests/e2e.py
python3 tests/app-aware-e2e.py
python3 tests/adb-e2e.py
python3 tests/pacing-e2e.py
python3 tests/boot-probe-e2e.py
sha256sum build/vban-engine.jar > build/vban-engine.jar.sha256
cat build/vban-engine.jar.sha256
sh tools/package-proof-in-container.sh
