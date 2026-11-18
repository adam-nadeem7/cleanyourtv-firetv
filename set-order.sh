#!/bin/sh
# Change the order of the app list without rebuilding.
#   ./set-order.sh com.netflix.ninja com.amazon.firetv.youtube ...   set a new order (top to bottom)
#   ./set-order.sh --list                                          show installed launchable packages
#   ./set-order.sh --reset                                         back to the built-in order
# The PS5 tile always stays first; apps you leave out follow alphabetically. Press Home to see it.
cd "$(dirname "$0")"
TV="./.venv/bin/python -W ignore ./tv.py"
PKG=tv.clean.home
case "$1" in
  --list)
    $TV sh "cmd package query-activities --brief -a android.intent.action.MAIN -c android.intent.category.LEANBACK_LAUNCHER | grep / | cut -d/ -f1 | sort -u"
    exit ;;
  --reset)
    $TV sh "run-as $PKG rm -f files/order.txt; am start -a android.intent.action.MAIN -c android.intent.category.HOME -n $PKG/.MainActivity -f 0x10200000 --ez home_key true >/dev/null"
    echo "Built-in order restored."
    exit ;;
  "")
    sed -n '2,6p' "$0"; exit 1 ;;
esac
list=$(printf '%s\\n' "$@")
$TV sh "run-as $PKG sh -c 'mkdir -p files && chmod 700 files && printf \"$list\" > files/order.txt && chmod 600 files/order.txt && cat files/order.txt'"
$TV sh "am start -a android.intent.action.MAIN -c android.intent.category.HOME -n $PKG/.MainActivity -f 0x10200000 --ez home_key true >/dev/null"
