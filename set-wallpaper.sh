#!/bin/sh
# Change the home screen wallpaper.
#   ./set-wallpaper.sh ~/Pictures/photo.jpg   use this photo (resized to 1920 px on the Mac first)
#   ./set-wallpaper.sh --none                 remove your photo (the bundled ones stay)
cd "$(dirname "$0")"
TV="./.venv/bin/python -W ignore ./tv.py"
PKG=tv.clean.home
if [ "$1" = "--none" ]; then
  $TV sh "run-as $PKG rm -f files/wallpaper.jpg"
  rm -f wallpaper.jpg
else
  [ -f "$1" ] || { sed -n '2,4p' "$0"; exit 1; }
  # Small enough for a 1 GB TV: 1920 px on the long side, JPEG.
  sips -s format jpeg -Z 1920 "$1" --out wallpaper.jpg >/dev/null
  $TV push wallpaper.jpg /data/local/tmp/wallpaper.jpg
  $TV sh "run-as $PKG sh -c 'mkdir -p files && cp /data/local/tmp/wallpaper.jpg files/wallpaper.jpg && chmod 700 files && chmod 600 files/wallpaper.jpg' && rm /data/local/tmp/wallpaper.jpg"
fi
$TV sh "am start -a android.intent.action.MAIN -c android.intent.category.HOME -n $PKG/.MainActivity -f 0x10200000 --ez home_key true >/dev/null"
echo "Done. (build-and-install.sh re-uses wallpaper.jpg from this folder.)"
