#!/bin/bash
# 不依赖 gradle 的最小构建：aapt2 + javac + d8 + zipalign + apksigner
set -e
export PATH=/usr/lib/jvm/java-17-openjdk-arm64/bin:$PATH
SDK=/root/sdk
BT=$SDK/android-14
JAR=$SDK/android-34/android.jar
JAVA=/usr/lib/jvm/java-17-openjdk-arm64/bin
cd "$(dirname "$0")"
rm -rf build && mkdir -p build/gen build/obj




$JAVA/javac -nowarn -source 8 -target 8 -encoding UTF-8 \
  -classpath "$JAR" -d build/obj \
  $(find src build/gen -name '*.java')

aapt package -f -J build/gen -M AndroidManifest.xml -S res -I "$JAR" \
  -F build/base.apk --min-sdk-version 21 --target-sdk-version 34 \
  --version-code 32 --version-name 3.11

$BT/d8 --release --min-api 21 --lib "$JAR" --output build/ \
  $(find build/obj -name '*.class')

cp build/base.apk build/unaligned.apk
(cd build && zip -q unaligned.apk classes.dex)
zipalign -f 4 build/unaligned.apk build/aligned.apk

if [ ! -f keystore.jks ]; then
  $JAVA/keytool -genkeypair -keystore keystore.jks -alias dsh -keyalg RSA \
    -keysize 2048 -validity 10000 -storepass dshpass -keypass dshpass \
    -dname "CN=DSH SplashSkip, O=DSH, C=CN" >/dev/null 2>&1
fi

$BT/apksigner sign --ks keystore.jks --ks-pass pass:dshpass --key-pass pass:dshpass \
  --out build/SplashSkip.apk build/aligned.apk
$BT/apksigner verify --verbose build/SplashSkip.apk | head -4
ls -lh build/SplashSkip.apk
