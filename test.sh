#!/usr/bin/env bash
# Compile everything (main + self-test) and run the headless self-test.
set -euo pipefail
cd "$(dirname "$0")"

API_JAR="lib/montoya-api.jar"
if [ ! -f "$API_JAR" ]; then
  ./build.sh
fi

rm -rf build/test-classes
mkdir -p build/test-classes

find src/main/java src/test/java -name '*.java' > build/test-sources.txt
javac --release 17 -cp "$API_JAR" -d build/test-classes @build/test-sources.txt

java -Djava.awt.headless=true -cp "build/test-classes:$API_JAR" scopemanager.SelfTest
