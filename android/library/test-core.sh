#!/bin/sh
# SPDX-License-Identifier: Unlicense
set -eu
library_dir=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
test_dir=$(mktemp -d)
trap 'rm -rf "$test_dir"' EXIT HUP INT TERM
find "$library_dir/src/main/java/org/sibalsa/core" "$library_dir/src/test/java" -name '*.java' -print > "$test_dir/sources"
javac --release 11 -Xlint:all -d "$test_dir/classes" @"$test_dir/sources"
java -ea -cp "$test_dir/classes" org.sibalsa.core.CoreTest
