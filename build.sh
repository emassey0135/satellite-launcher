#!/bin/sh
# Builds out/community-launcher.apk from the clean-room AIDL and the launcher sources.
set -e
SDK=$HOME/Library/Android/sdk; BT=$SDK/build-tools/36.0.0; JAR=$SDK/platforms/android-37.0/android.jar
export JAVA_HOME=/opt/homebrew/opt/openjdk/libexec/openjdk.jdk/Contents/Home PATH=/opt/homebrew/opt/openjdk/bin:$PATH
cd "$(dirname "$0")"; rm -rf build; mkdir -p build/gen build/classes build/dex out
$BT/aidl --lang=java -p $SDK/platforms/android-37.0/framework.aidl -I aidl -o build/gen $(find aidl -name '*.aidl')
$BT/aapt2 link -I $JAR --manifest AndroidManifest.xml -o build/base.apk
javac -nowarn --release 17 -cp $JAR -d build/classes $(find src build/gen -name '*.java')
$BT/d8 --min-api 30 --lib $JAR --output build/dex $(find build/classes -name '*.class')
(cd build/dex && zip -q ../base.apk classes.dex)
$BT/zipalign -f 4 build/base.apk build/aligned.apk
$BT/apksigner sign --ks $HOME/.android/debug.keystore --ks-pass pass:android --out out/community-launcher.apk build/aligned.apk 2>/dev/null
echo built out/community-launcher.apk
