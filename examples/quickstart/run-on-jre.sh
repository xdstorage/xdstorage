#!/usr/bin/env bash
# Runs the quickstart demo on a runtime WITHOUT the Java compiler and from a working
# directory it cannot write to (docs/tasks/2.2.0/08; README § Run it on a JRE):
#   1. mvn -Pjre package   compile, generate the wrappers at BUILD time, collect the runtime libraries
#   2. jlink               a runtime image with the engine's modules — no java.compiler in it
#   3. the demo            from a read-only working directory; and, when bubblewrap is
#                          installed, once more inside a sandbox whose WHOLE root filesystem
#                          is read-only — what `docker run --read-only` gives a container.
# Nothing is compiled at runtime: no ./gencode anywhere, on any leg.
#
# Needs: a JDK 17+ (JAVA_HOME, or `java` on PATH) for the build and jlink; Maven; the engine
# and the codegen plugin resolvable by Maven (README § Run it on a JRE).
set -euo pipefail
cd "$(dirname "$0")"

JAVA_HOME="${JAVA_HOME:-$(dirname "$(dirname "$(readlink -f "$(command -v java)")")")}"
# What the engine and its dependencies need at runtime — found by running the demo on
# smaller and smaller images (jdeps' static answer is narrower: it cannot see what the
# dependencies pull in). A logging backend of your own may need more.
MODULES="java.base,java.logging,java.management,java.naming,java.transaction.xa,java.xml,java.sql,jdk.unsupported"
APP=target/jre-app
RUN="$APP/run"
DATA="$APP/data"
IMAGE_JAVA="$PWD/target/jre/bin/java"
MAIN=quickstart.Quickstart
readonly ROOT="$PWD"

step() { printf '\n== %s\n' "$*"; }
run_demo() {   # run_demo <label> [sandbox command...]; prints the status line, returns the demo's status
  local label="$1"; shift
  local out="$ROOT/target/jre-demo-$label.log" code
  set +e
  ( cd "$RUN" && "$@" "$IMAGE_JAVA" -cp "$ROOT/$JAR:$ROOT/$APP/lib/*" "$MAIN" "$ROOT/$DATA/db-$label" ) > "$out" 2>&1
  code=$?
  set -e
  grep -v '^SLF4J' "$out" | head -n 12 | cut -c1-400
  printf '%-22s exit=%s  gencode in the working directory: %s\n' "$label" "$code" \
      "$([ -e "$RUN/gencode" ] && echo 'YES — compiled at runtime!' || echo none)"
  return $code
}

step "1/3 build: the wrappers are generated now, by xdstorage-codegen-maven-plugin"
mvn -q -Pjre package -DskipTests
JAR="$(ls target/xdstorage-quickstart-*.jar | grep -v original | head -n 1)"
JAR_CONTENTS="$("$JAVA_HOME/bin/jar" tf "$JAR")"
grep -q 'META-INF/xdstorage/aot-wrappers.properties' <<< "$JAR_CONTENTS" \
    || { echo "the jar carries no wrapper manifest — was -Pjre on?"; exit 3; }
echo "wrappers inside $JAR: $(grep -c 'io/github/xdstorage/code/.*Wrapper.class' <<< "$JAR_CONTENTS")"

step "2/3 jlink: a runtime image with the engine's modules"
rm -rf target/jre
"$JAVA_HOME/bin/jlink" --add-modules "$MODULES" --strip-debug --no-header-files --no-man-pages \
    --output target/jre
if "$IMAGE_JAVA" --list-modules | grep -Ex 'java\.compiler|jdk\.compiler' > /dev/null; then
  echo "the image still carries a compiler — the demo would prove nothing"; exit 3
fi
echo "image: $("$IMAGE_JAVA" -version 2>&1 | head -n 1), $(du -sh target/jre | cut -f1), no compiler module"

step "3/3 the demo, from a working directory nothing can write to"
rm -rf "$RUN" "$DATA"
mkdir -p "$RUN" "$DATA"
chmod a-w "$RUN"
[ -w "$RUN" ] && echo "(running as root: the write bit stops no one — the read-only-cwd leg proves little here)"
trap 'chmod -R u+w "$ROOT/$RUN" "$ROOT/$DATA" 2>/dev/null' EXIT
status=0
run_demo read-only-cwd || status=1

if command -v bwrap > /dev/null 2>&1 \
    && bwrap --ro-bind / / --dev /dev --proc /proc --tmpfs /tmp --unshare-net --die-with-parent \
          -- /bin/true 2> /dev/null; then
  run_demo read-only-root bwrap --ro-bind / / --dev /dev --proc /proc --tmpfs /tmp \
      --bind "$ROOT/$DATA" "$ROOT/$DATA" --unshare-net --die-with-parent -- || status=1
else
  echo "(bubblewrap is not installed here — the read-only-root leg is skipped; see Dockerfile)"
fi

printf '\n'
if [ $status -eq 0 ]; then
  echo "DONE: the demo ran on a runtime without the compiler, nothing compiled at runtime."
else
  echo "FAILED: see target/jre-demo-*.log"; exit 1
fi
