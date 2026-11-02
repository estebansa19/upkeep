#!/usr/bin/env bash
# Builds build/upkeep-<version>.jar with only a JDK (21+). Usage: ./build.sh [--skip-tests]
set -euo pipefail
cd "$(dirname "$0")"

bin="${JAVA_HOME:+$JAVA_HOME/bin/}"
version="$(tr -d '[:space:]' < VERSION)"
sep=":"
case "$(uname -s)" in MINGW*|MSYS*|CYGWIN*) sep=";" ;; esac

rm -rf build/classes build/test-classes
mkdir -p build/classes build/test-classes
find src/main/java -name '*.java' > build/sources.txt
"${bin}javac" --release 21 -encoding UTF-8 -Xlint:deprecation -d build/classes @build/sources.txt
cp -R src/main/resources/. build/classes/
printf 'Main-Class: io.github.estebansa19.upkeep.Main\nImplementation-Title: Upkeep\nImplementation-Version: %s\n' "$version" > build/MANIFEST.MF
"${bin}jar" --create --file "build/upkeep-$version.jar" --manifest build/MANIFEST.MF -C build/classes .

if [ "${1:-}" != "--skip-tests" ]; then
  find src/test/java -name '*.java' > build/test-sources.txt
  "${bin}javac" --release 21 -encoding UTF-8 -cp build/classes -d build/test-classes @build/test-sources.txt
  "${bin}java" -cp "build/classes${sep}build/test-classes" io.github.estebansa19.upkeep.TestRunner
fi
echo "built build/upkeep-$version.jar"
