#!/usr/bin/env bash
# Build the Scope Highlighter Burp extension.
# Produces: dist/scope-highlighter.jar  (load this in Burp > Extensions > Add > Java)
set -euo pipefail
cd "$(dirname "$0")"

API_VERSION="2025.2"
API_JAR="lib/montoya-api.jar"
API_URL="https://repo1.maven.org/maven2/net/portswigger/burp/extensions/montoya-api/${API_VERSION}/montoya-api-${API_VERSION}.jar"

if [ ! -f "$API_JAR" ]; then
  echo "Downloading Montoya API ${API_VERSION}..."
  mkdir -p lib
  curl -fsSL -o "$API_JAR" "$API_URL"
fi

rm -rf build dist
mkdir -p build/classes dist

find src/main/java -name '*.java' > build/sources.txt
javac --release 17 -Xlint:all,-serial -cp "$API_JAR" -d build/classes @build/sources.txt

cat > build/MANIFEST.MF <<'EOF'
Manifest-Version: 1.0
Extension-Entrypoint: scopehighlighter.ScopeHighlighterExtension
EOF

jar --create --file dist/scope-highlighter.jar --manifest build/MANIFEST.MF -C build/classes .

echo "Built $(pwd)/dist/scope-highlighter.jar"
