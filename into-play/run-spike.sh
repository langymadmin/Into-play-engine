#!/usr/bin/env bash
# Build Forge headless and run the into-play spike.
#
# Run it from anywhere; it works in the repo root itself. Everything here is the
# result of getting it wrong first, so the comments are the reasons, not
# decoration.
set -euo pipefail

cd "$(git -C "$(dirname "$0")" rev-parse --show-toplevel)"
HERE="into-play"

FORGE_VERSION="${FORGE_VERSION:-2.0.16-SNAPSHOT}"
OUT="${OUT:-/tmp/into-play-spike}"
mkdir -p "$OUT"

echo "==> building engine modules (no desktop/android/ios GUI needed)"
# `install`, not `package`: the spike resolves forge-core/forge-game from the
# local repo, and Forge's poms use ${revision}, so a module built but not
# installed cannot be resolved from outside the reactor.
mvn -q -pl forge-gui -am -DskipTests -Dcheckstyle.skip=true -Dmaven.javadoc.skip=true install

echo "==> assembling classpath"
# dependency:build-classpath does NOT work here: the poms declare
# <version>${revision}</version>, and resolving forge-gui outside the reactor
# fails with "Could not find artifact forge:forge:pom:${revision}". So the
# classpath is assembled from ~/.m2 directly, newest version per artifact.
#
# google-collections-1.0 MUST be excluded. It is the pre-Guava library with the
# same com.google.common.collect package, and if it wins the classpath order you
# get a baffling NoSuchMethodError on ImmutableSet.of(...). Same story for the
# -android Guava variant.
find ~/.m2/repository -name '*.jar' ! -name '*sources*' ! -name '*javadoc*' \
  | sed "s|^$HOME/.m2/repository/||" \
  | awk -F/ '{ ver=$(NF-1); base=""; for(i=1;i<=NF-2;i++) base=base $i "/"; print base "\t" ver "\t" $0 }' \
  | sort -t$'\t' -k1,1 -k2,2V \
  | awk -F'\t' '{ a[$1]=$3 } END { for (k in a) print a[k] }' \
  | grep -vE 'google-collections|guava-.*-android' \
  | sed "s|^|$HOME/.m2/repository/|" \
  | paste -sd: > "$OUT/deps.txt"

CP="$OUT"
for m in forge-core forge-game forge-ai forge-gui; do
  # target/classes BEFORE the jar, and the order is load-bearing. The jar is
  # only as fresh as the last `mvn package`, so editing a Forge source file and
  # recompiling it with javac leaves the jar holding the old class — which then
  # wins, and you debug a stack trace whose line numbers do not match the file
  # in front of you. That cost an hour once; this line is the receipt.
  CP="$CP:$m/target/classes:$m/target/$m-$FORGE_VERSION.jar"
done
CP="$CP:$(cat "$OUT/deps.txt")"
echo "$CP" > "$OUT/CP.txt"
echo "    $(tr ':' '\n' < "$OUT/CP.txt" | grep -c '\.jar') jars"

echo "==> compiling spike"
# Two source roots on purpose: the flat files at into-play/ are throwaway
# experiments in the default package, while into-play/src holds the real
# forge.intoplay package that the app will actually talk to.
javac -cp "$CP" -d "$OUT" "$HERE"/*.java
javac -cp "$CP" -d "$OUT" "$HERE"/src/forge/intoplay/*.java

if [[ "${1:-}" == "--build-only" ]]; then
  echo "==> built; classpath in $OUT/CP.txt"
  exit 0
fi

echo "==> running"
# forge-gui/res holds the 34k card scripts, editions and language files.
java -Xmx2g -cp "$CP" "${1:-Spike}" forge-gui/res
