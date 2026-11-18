#!/bin/sh
# Remove the packages in debloat.txt for user 0, one pm call per package (chained pm calls can stop
# after the first). "Removed" = pm uninstall -k --user 0: gone for you, auto-updates can't bring it
# back, the system copy stays so restore.sh can reinstall it. Where Fire OS refuses, disable instead.
#   ./remove.sh             all batches
#   ./remove.sh 5           one batch
cd "$(dirname "$0")"
TV="./.venv/bin/python -W ignore ./tv.py"
batch=0
while IFS= read -r line; do
  case "$line" in
    "## batch "*) batch=$(echo "$line" | sed 's/## batch \([0-9]*\).*/\1/'); continue ;;
    ""|"#"*) continue ;;
  esac
  [ -n "$1" ] && [ "$batch" != "$1" ] && continue
  pkg=$(echo "$line" | awk '{print $1}')
  printf '%-42s ' "$pkg"
  out=$($TV sh "pm uninstall -k --user 0 $pkg 2>&1")
  case "$out" in
    *Success*) echo "removed" ;;
    *"not installed for 0"*|*"Unknown package"*) echo "already gone" ;;
    *) d=$($TV sh "pm disable-user --user 0 $pkg 2>&1" | grep -m1 -oE "new state: .*|Cannot [^:]*|Unknown package.*")
       echo "not removable ($(echo "$out" | tr -d '\r' | tail -1 | cut -c1-40)); ${d:-?}" ;;
  esac
done < debloat.txt

# Prime Video stays (you use it) but Fire OS keeps it running in the background to feed Amazon's
# rows: ~130 MB and a fifth of the CPU while you watch something else. Background running off;
# it still opens and plays normally. Undo: cmd appops set com.amazon.firebat RUN_ANY_IN_BACKGROUND allow
if [ -z "$1" ]; then
  $TV sh "cmd appops set com.amazon.firebat RUN_ANY_IN_BACKGROUND ignore; am force-stop com.amazon.firebat"
  echo "Prime Video: background running off"
fi
