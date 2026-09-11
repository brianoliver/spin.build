#!/usr/bin/env bash
# Confirms the provided-scope dependency's classes did not leak into the packaged jar.
# See test-fixtures.sh for how/when this runs.
set -uo pipefail

jar_file="$(find target -maxdepth 1 -name '*.jar' ! -name '*-sources.jar' ! -name '*-javadoc.jar' | head -1)"

if [ -z "$jar_file" ]; then
    echo "no packaged jar found under target/"
    exit 1
fi

if unzip -l "$jar_file" | grep -q 'jakarta/servlet/'; then
    echo "expected jakarta/servlet/ classes to be absent from $jar_file (provided scope), but found them:"
    unzip -l "$jar_file" | grep 'jakarta/servlet/'
    exit 1
fi

echo "ok: $jar_file does not contain jakarta/servlet/ classes"
