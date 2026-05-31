#!/data/data/com.termux/files/usr/bin/sh
set -e
cd "$(dirname "$0")"

export ANDROID_HOME=/data/data/com.termux/files/home/android-sdk
export JAVA_HOME=/data/data/com.termux/files/usr/lib/jvm/java-21-openjdk

gradle assembleDebug -x lint

APK=app/build/outputs/apk/debug/app-debug.apk
cp "$APK" /storage/emulated/0/Download/twopane-fm.apk
rish -c "cp /storage/emulated/0/Download/twopane-fm.apk /data/local/tmp/twopane-fm.apk && pm install -r /data/local/tmp/twopane-fm.apk" 2>&1

echo "=== DONE ==="
