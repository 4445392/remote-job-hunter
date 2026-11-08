#!/usr/bin/env bash
# Builds jobhunter.jar with only the JDK (Java 21+). Add "test" to run the self-tests first.
set -euo pipefail
cd "$(dirname "$0")"
rm -rf out out-test
mkdir -p out
javac -encoding UTF-8 -Xlint:all -d out $(find src/main/java -name '*.java')
cp -r src/main/resources/* out/
if [ "${1:-}" = "test" ]; then
  mkdir -p out-test
  javac -encoding UTF-8 -cp out -d out-test $(find src/test/java -name '*.java')
  java -cp out:out-test jobhunter.SelfTest
fi
jar --create --file jobhunter.jar --main-class jobhunter.Main -C out .
echo "Built jobhunter.jar"
