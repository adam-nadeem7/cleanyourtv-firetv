#!/bin/sh
# Renamed: packages are now removed for the user, not only disabled. See remove.sh.
exec "$(dirname "$0")/remove.sh" "$@"
