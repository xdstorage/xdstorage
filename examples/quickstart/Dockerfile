# syntax=docker/dockerfile:1
# The quickstart demo on a runtime WITHOUT the Java compiler, in a container whose root
# filesystem is read-only (docs/tasks/2.2.0/08; README § Run it on a JRE).
#
#   cd examples/quickstart
#   docker build -t xdstorage-quickstart-jre .
#   docker run --rm --read-only --tmpfs /tmp -v xdstorage-demo:/data xdstorage-quickstart-jre
#
# Stage 1 builds on a JDK: compile, generate the wrappers at build time (the xdstorage-codegen
# Maven plugin, bound by the `jre` profile), collect the runtime libraries, and link a runtime
# image with the engine's modules — java.compiler is not among them. Stage 2 has no JDK, no
# Maven, no compiler: the linked runtime, the application jar with its wrappers, the libraries.
#
# The plugin must be resolvable in stage 1: from Maven Central once it is published with the
# engine release, or from a repository you mount into the build
# (--mount=type=bind,source=$HOME/.m2/repository,target=/root/.m2/repository).
# The same steps run without Docker in run-on-jre.sh.

ARG XDSTORAGE_VERSION=2.2.0

FROM maven:3.9-eclipse-temurin-21 AS build
ARG XDSTORAGE_VERSION
WORKDIR /build
COPY pom.xml .
RUN mvn -q -B org.apache.maven.plugins:maven-dependency-plugin:3.6.1:go-offline \
        -Dxdstorage.version=$XDSTORAGE_VERSION
COPY src src
RUN mvn -q -B -Pjre package -DskipTests -Dxdstorage.version=$XDSTORAGE_VERSION
# What the engine and its dependencies need at runtime — the list run-on-jre.sh verified.
RUN jlink --add-modules java.base,java.logging,java.management,java.naming,java.transaction.xa,java.xml,java.sql,jdk.unsupported \
        --strip-debug --no-header-files --no-man-pages --output /jre

FROM debian:bookworm-slim
RUN useradd --system --uid 10001 app && mkdir -p /data /app && chown app:app /data
COPY --from=build /jre /opt/jre
COPY --from=build /build/target/xdstorage-quickstart-1.0.0.jar /opt/app/app.jar
COPY --from=build /build/target/jre-app/lib /opt/app/lib
USER app
# The working directory belongs to root and the container runs --read-only: ./gencode could
# not be created here even if the engine tried.
WORKDIR /app
VOLUME /data
ENTRYPOINT ["/opt/jre/bin/java", "-cp", "/opt/app/app.jar:/opt/app/lib/*", "quickstart.Quickstart", "/data/db"]
