#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"
: "${JAVA_HOME:?请设置 JAVA_HOME，使用 JDK 17 或更新版本}"
: "${ANDROID_JAR:?请设置 ANDROID_JAR，指向 Android 35 的 android.jar}"
: "${ANDROID_BUILD_TOOLS:?请设置 ANDROID_BUILD_TOOLS，指向 Android build-tools 35.0.0}"
export PATH="$JAVA_HOME/bin:$PATH"
build_dir="$PWD/build"
mkdir -p "$build_dir/classes" "$build_dir/generated" "$build_dir/dex"
"$ANDROID_BUILD_TOOLS/aapt2" compile --dir res -o "$build_dir/resources.zip"
"$ANDROID_BUILD_TOOLS/aapt2" link -I "$ANDROID_JAR" --manifest AndroidManifest.xml --java "$build_dir/generated" --min-sdk-version 26 --target-sdk-version 35 -o "$build_dir/base.apk" "$build_dir/resources.zip"
find src "$build_dir/generated" -name '*.java' > "$build_dir/sources.txt"
javac -encoding UTF-8 --release 8 -classpath "$ANDROID_JAR" -d "$build_dir/classes" @"$build_dir/sources.txt"
jar cf "$build_dir/classes.jar" -C "$build_dir/classes" .
"$ANDROID_BUILD_TOOLS/d8" --lib "$ANDROID_JAR" --min-api 26 --output "$build_dir/dex" "$build_dir/classes.jar"
cp "$build_dir/base.apk" "$build_dir/unsigned.apk"
(cd "$build_dir/dex" && zip -q -j "$build_dir/unsigned.apk" ./*.dex)
"$ANDROID_BUILD_TOOLS/zipalign" -f 4 "$build_dir/unsigned.apk" "$build_dir/aligned.apk"
# Keep the signing key outside the repository. Reuse it to update an installed app.
if [[ -z "${APK_KEYSTORE:-}" ]]; then
  APK_KEYSTORE="$build_dir/local.keystore"
  export APK_KEYSTORE_PASSWORD=android
  APK_KEY_ALIAS=smshub
  if [[ ! -f "$APK_KEYSTORE" ]]; then
    keytool -genkeypair -keystore "$APK_KEYSTORE" -storepass:env APK_KEYSTORE_PASSWORD -keypass:env APK_KEYSTORE_PASSWORD -alias "$APK_KEY_ALIAS" -keyalg RSA -keysize 3072 -validity 10000 -dname "CN=SMS Hub Local Build" > /dev/null 2>&1
  fi
fi
: "${APK_KEYSTORE_PASSWORD:?请设置 APK_KEYSTORE_PASSWORD}"
"$ANDROID_BUILD_TOOLS/apksigner" sign --ks "$APK_KEYSTORE" --ks-key-alias "${APK_KEY_ALIAS:-smshub}" --ks-pass env:APK_KEYSTORE_PASSWORD --key-pass env:APK_KEYSTORE_PASSWORD --out "$build_dir/sms-hub-0.1.0.apk" "$build_dir/aligned.apk"
"$ANDROID_BUILD_TOOLS/apksigner" verify --verbose "$build_dir/sms-hub-0.1.0.apk"
printf '\nAPK: %s\n' "$build_dir/sms-hub-0.1.0.apk"
