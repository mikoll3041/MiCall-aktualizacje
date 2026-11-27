#!/usr/bin/env bash
# Budowa MiCall.apk bez Gradle: aapt2 → javac → d8 → zipalign (narzędzia z Android SDK na GitHub Actions).
# Wynik: build/MiCall-unsigned.apk (podpisuje go centrala swoim kluczem).
set -euo pipefail
cd "$(dirname "$0")"
CODE="${CODE:-1}"
VER="${VER:-1.0.$CODE}"
SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
BT="$SDK/build-tools/$(ls "$SDK/build-tools" | sort -V | tail -1)"
PLAT="$(ls -d "$SDK"/platforms/android-* | grep -E 'android-[0-9]+$' | sort -t- -k2 -n | tail -1)"
JAR="$PLAT/android.jar"
echo "build-tools: $BT"
echo "platforma:   $PLAT"
echo "wersja:      $VER ($CODE)"
rm -rf build && mkdir -p build/gen build/classes build/dex build/x
"$BT/aapt2" compile --dir res -o build/res.zip
"$BT/aapt2" link -o build/base.apk -I "$JAR" --manifest AndroidManifest.xml --java build/gen \
  --min-sdk-version 28 --target-sdk-version 34 --version-code "$CODE" --version-name "$VER" build/res.zip
javac -encoding UTF-8 -source 8 -target 8 -Xlint:-options -Xlint:deprecation -bootclasspath "$JAR" -classpath "$JAR" \
  -d build/classes $(find src build/gen -name '*.java')
(cd build/classes && jar cf ../classes.jar .)
"$BT/d8" --release --min-api 28 --lib "$JAR" --output build/dex build/classes.jar
# resources.arsc bez kompresji (wymóg Androida 11+), reszta skompresowana
(cd build/x && unzip -q ../base.apk)
rm -f build/u.apk
(cd build/x && zip -q -0 -X ../u.apk resources.arsc && zip -q -9 -X -r ../u.apk . -x resources.arsc)
(cd build/dex && zip -q -9 -X ../u.apk classes.dex)
"$BT/zipalign" -p -f 4 build/u.apk build/MiCall-unsigned.apk
"$BT/zipalign" -c 4 build/MiCall-unsigned.apk
"$BT/aapt2" dump badging build/MiCall-unsigned.apk | head -5
ls -la build/MiCall-unsigned.apk
