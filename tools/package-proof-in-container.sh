#!/bin/sh
set -eu
SDK_ROOT=${ANDROID_HOME:?Android SDK image must define ANDROID_HOME}
TOOLS="$SDK_ROOT/build-tools/36.0.0"
mkdir -p build/signing
"$TOOLS/aapt2" link -I "$SDK_ROOT/platforms/android-36/android.jar" \
    --manifest app/AndroidManifest.xml -A build/proof/assets \
    -o build/proof/unsigned.apk
jar --update --file build/proof/unsigned.apk -C build/dex classes.dex
"$TOOLS/zipalign" -f 4 build/proof/unsigned.apk build/proof/aligned.apk
# Local proof signing identity, not a release key. Never included in CI uploads.
if [ ! -f build/signing/proof.p12 ]; then
    keytool -genkeypair -keystore build/signing/proof.p12 -storetype PKCS12 \
        -storepass meovban-proof -keypass meovban-proof -alias proof \
        -dname 'CN=VBAN local proof' -keyalg RSA -keysize 2048 -validity 3650
    chmod 600 build/signing/proof.p12
fi
"$TOOLS/apksigner" sign --ks build/signing/proof.p12 \
    --ks-key-alias proof --ks-pass pass:meovban-proof \
    --out build/vban.apk build/proof/aligned.apk
"$TOOLS/apksigner" verify --verbose build/vban.apk
sha256sum build/vban.apk > build/vban.apk.sha256
cat build/vban.apk.sha256
# Same code and signing identity, explicit developer access for owned-APK tests.
python3 - <<'PY'
from xml.etree import ElementTree as ET
ET.register_namespace('android', 'http://schemas.android.com/apk/res/android')
tree = ET.parse('app/AndroidManifest.xml')
version = '{http://schemas.android.com/apk/res/android}versionName'
tree.getroot().set(version, tree.getroot().get(version) + '-dev')
tree.getroot().find('application').set('{http://schemas.android.com/apk/res/android}debuggable', 'true')
tree.write('build/proof/developer-manifest.xml', encoding='utf-8', xml_declaration=True)
PY
"$TOOLS/aapt2" link -I "$SDK_ROOT/platforms/android-36/android.jar" \
    --manifest build/proof/developer-manifest.xml -A build/proof/assets \
    -o build/proof/developer-unsigned.apk
jar --update --file build/proof/developer-unsigned.apk -C build/dex classes.dex
"$TOOLS/zipalign" -f 4 build/proof/developer-unsigned.apk build/proof/developer-aligned.apk
"$TOOLS/apksigner" sign --ks build/signing/proof.p12 \
    --ks-key-alias proof --ks-pass pass:meovban-proof \
    --out build/vban-dev.apk build/proof/developer-aligned.apk
"$TOOLS/apksigner" verify --verbose build/vban-dev.apk
sha256sum build/vban-dev.apk > build/vban-dev.apk.sha256
cat build/vban-dev.apk.sha256
