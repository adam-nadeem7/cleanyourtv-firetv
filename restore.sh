#!/bin/sh
# Put back every package in debloat.txt (including commented-out lines) and undo Private DNS.
#   ./restore.sh            everything
#   ./restore.sh <package>  one package
cd "$(dirname "$0")"
TV="./.venv/bin/python -W ignore ./tv.py"
back() {
  # Removed for the user: reinstall the system copy. Disabled: enable. Either way it ends up on.
  r=$($TV sh "cmd package install-existing $1 2>&1; pm enable --user 0 $1 2>&1" | grep -m1 -oE "new state: .*|installed for user.*|Unknown package.*")
  echo "${r:-?}"
}
if [ -n "$1" ]; then
  back "$1"
  exit
fi
grep -oE '^#? *[a-z][a-z0-9_]*(\.[a-zA-Z0-9_]+)+' debloat.txt | sed 's/^# *//' | while read -r pkg; do
  printf '%-42s ' "$pkg"
  back "$pkg"
done
$TV sh "cmd appops set com.amazon.firebat RUN_ANY_IN_BACKGROUND allow"
$TV sh "settings put global private_dns_mode opportunistic"
echo "Prime Video background running back on; Private DNS back to automatic."
echo "Third-party apps (ZDF, Joyn) come back from the Appstore if needed; the screensaver:"
echo "  settings put secure screensaver_components com.amazon.ftv.screensaver/.app.services.ScreensaverService"
