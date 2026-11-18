#!/bin/sh
# Build the clean home launcher and install it on the TV over ADB.
#   ./build-and-install.sh            build, install, grant permissions, enable the Home-button service
#   ./build-and-install.sh --no-build install the APK that is already built
# Needs: the Android SDK at /opt/homebrew/share/android-commandlinetools (brew install --cask
# android-commandlinetools), JDK 17+, and ADB debugging on the TV (Settings > My Fire TV > Developer options).
set -e
cd "$(dirname "$0")"
TV="./.venv/bin/python -W ignore ./tv.py"
APK=launcher/app/build/outputs/apk/debug/app-debug.apk
PKG=tv.clean.home
SVC=$PKG/.HomeKeyService

if [ "$1" != "--no-build" ]; then
  [ -f launcher/local.properties ] || echo "sdk.dir=/opt/homebrew/share/android-commandlinetools" > launcher/local.properties
  (cd launcher && JAVA_HOME="${JAVA_HOME:-$(/usr/libexec/java_home)}" ./gradlew -q assembleDebug)
fi

# adb install can hang waiting for an on-screen confirmation; push and pm install instead.
$TV push "$APK" /data/local/tmp/cleanhome.apk
$TV sh "pm install -r /data/local/tmp/cleanhome.apk 2>&1; rm -f /data/local/tmp/cleanhome.apk"

# The channel list, so Live TV opens a real channel.
$TV sh "pm grant $PKG android.permission.READ_TV_LISTINGS"
# Lets the app keep its own Home watch on and bound after reboots and updates (WatchKeeper).
$TV sh "pm grant $PKG android.permission.WRITE_SECURE_SETTINGS"
# Settings > Screensaver after (a system setting) needs the WRITE_SETTINGS app-op.
$TV sh "appops set $PKG WRITE_SETTINGS allow"
# Lets the Home watch see a Home press while the Fire TV home is open on purpose (it reads the log
# only during such a visit). Optional: without it, Home on the Fire TV home just does nothing.
$TV sh "pm grant $PKG android.permission.READ_LOGS"

# The screensaver: the wallpaper gallery with a clock (PhotoDream) instead of Amazon's sponsored one.
$TV sh "settings put secure screensaver_components $PKG/$PKG.PhotoDream; settings put secure screensaver_enabled 1"

# Home-button service. A reinstall unbinds it and re-writing the same value doesn't rebind, so take it
# out of the list and put it back (keeping any other accessibility services).
$TV sh "s=\$(settings get secure enabled_accessibility_services); s=\$(echo \"\$s\" | tr ':' '\\n' | grep -v -e '^tv.clean.home/' -e '^null\$' | paste -sd: -); settings put secure enabled_accessibility_services \"\$s\"; sleep 1; settings put secure enabled_accessibility_services \"\${s:+\$s:}$SVC\"; settings put secure accessibility_enabled 1; sleep 3"
$TV sh "settings get secure enabled_accessibility_services"

# Wallpaper: wallpaper.jpg next to this script, if there is one. Android 11 keeps the shell out of
# the app's folders, so it goes in through run-as (works because this is a debug build).
if [ -f wallpaper.jpg ]; then
  $TV push wallpaper.jpg /data/local/tmp/wallpaper.jpg
  $TV sh "run-as $PKG sh -c 'mkdir -p files && cp /data/local/tmp/wallpaper.jpg files/wallpaper.jpg && chmod 700 files && chmod 600 files/wallpaper.jpg' && rm /data/local/tmp/wallpaper.jpg"
fi

# Film photos for the gallery (photos/), if there are any; unchanged ones are skipped.
[ -f photos/names.txt ] && ./set-photos.sh | tail -1

# Open the clean home as a Home intent (a plain "am start -n" leaves a second copy in a normal task, and
# a Home key press does nothing if the Fire TV home happens to be in front).
$TV sh "am start -a android.intent.action.MAIN -c android.intent.category.HOME -n $PKG/.MainActivity -f 0x10200000 --ez home_key true >/dev/null"
echo "Installed."
