#!/bin/sh
# Copy the film photos in photos/ to the TV's wallpaper gallery.
#   ./set-photos.sh                       push photos/*.jpg and photos/names.txt (only new or changed ones)
#   ./set-photos.sh --add pic.jpg "Name"  add one photo (resized to 1920 px) and push
# names.txt has one line per photo: <file name without .jpg>|<name shown on screen>. A photo that is
# not listed there is ignored; delete its line (and the file) to remove it from the TV on the next push.
set -e
cd "$(dirname "$0")"
TV="./.venv/bin/python -W ignore ./tv.py"
PKG=tv.clean.home
DIR=photos

if [ "$1" = "--add" ]; then
  [ -f "$2" ] && [ -n "$3" ] || { sed -n '2,6p' "$0"; exit 1; }
  id=$(basename "$2" | sed 's/\.[^.]*$//' | tr 'A-Z ' 'a-z-')
  sips -s format jpeg -s formatOptions 88 -Z 1920 "$2" --out "$DIR/$id.jpg" >/dev/null
  grep -v "^$id|" "$DIR/names.txt" > "$DIR/names.tmp" 2>/dev/null || true
  echo "$id|$3" >> "$DIR/names.tmp" && mv "$DIR/names.tmp" "$DIR/names.txt"
fi

$TV sh "run-as $PKG sh -c 'mkdir -p files/photos && chmod 700 files files/photos'"
# What the TV already has, by md5, so unchanged photos are skipped.
have=$($TV sh "run-as $PKG sh -c 'cd files/photos && md5sum *.jpg 2>/dev/null'" || true)
cut -d'|' -f1 "$DIR/names.txt" | while read -r id; do
  f="$DIR/$id.jpg"
  [ -f "$f" ] || { echo "skip $id (no $f)"; continue; }
  sum=$(md5 -q "$f")
  if echo "$have" | grep -q "^$sum  $id.jpg"; then echo "same $id"; continue; fi
  $TV push "$f" /data/local/tmp/photo.jpg >/dev/null
  $TV sh "run-as $PKG sh -c 'cp /data/local/tmp/photo.jpg files/photos/$id.jpg && chmod 600 files/photos/$id.jpg' && rm /data/local/tmp/photo.jpg"
  echo "sent $id"
done
# names.txt last: the TV picks the gallery up when it changes. Remove photos no longer listed.
$TV push "$DIR/names.txt" /data/local/tmp/names.txt >/dev/null
keep=$(cut -d'|' -f1 "$DIR/names.txt" | sed 's/$/.jpg/' | tr '\n' ' ')
$TV sh "run-as $PKG sh -c 'cp /data/local/tmp/names.txt files/photos/names.txt && chmod 600 files/photos/names.txt; cd files/photos; for f in *.jpg; do case \" $keep \" in *\" \$f \"*) ;; *) rm -f \"\$f\";; esac; done' && rm /data/local/tmp/names.txt"
$TV sh "am start -a android.intent.action.MAIN -c android.intent.category.HOME -n $PKG/.MainActivity -f 0x10200000 --ez home_key true >/dev/null"
echo "$(wc -l < "$DIR/names.txt" | tr -d ' ') photos on the TV."
