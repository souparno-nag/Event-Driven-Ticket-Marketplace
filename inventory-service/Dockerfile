# Build context is the REPOSITORY ROOT, not this directory:
#   docker build -f inventory-service/Dockerfile -t inventory-service .
# inventory-service is one module in a Maven reactor and needs the parent pom and common-events above
# it to build at all -- a build context scoped to inventory-service/ alone could never see either one.
# .dockerignore at the repository root already documents this and keeps that wider context small.

FROM eclipse-temurin:21-jdk AS build
WORKDIR /workspace

# Poms and the wrapper first, source afterward -- so an ordinary code change invalidates only the
# later COPY/RUN layers, not the dependency-resolution step, which is the slow one to redo.
COPY mvnw mvnw.cmd pom.xml ./
COPY .mvn .mvn
COPY common-events/pom.xml common-events/pom.xml
COPY order-service/pom.xml order-service/pom.xml
COPY inventory-service/pom.xml inventory-service/pom.xml
# EVERY module's pom.xml, not only the ones this image actually needs -- `-pl inventory-service -am`
# limits which modules Maven BUILDS, but reading the reactor at all still requires every module the
# root pom.xml declares to be present, or Maven fails before `-pl` ever gets a chance to apply.
# order-service's own pom is otherwise untouched by anything below this line.
RUN ./mvnw -q -pl inventory-service -am -Dmaven.test.skip=true dependency:go-offline

COPY common-events/src common-events/src
COPY inventory-service/src inventory-service/src

# package, not verify: this step ships an already-tested artifact rather than re-proving
# correctness inside the image build. inventory-service's own integration tests start PostgreSQL,
# Redis, and Kafka containers of their own (Testcontainers) -- doing that again here would mean
# Docker containers building a Docker image that starts more Docker containers, for no test this
# artifact hasn't already passed in CI or on this machine before the build ever reached this
# Dockerfile.
#
# -Dmaven.test.skip=true, not -DskipTests: the former also skips COMPILING test sources, not just
# running them. That distinction matters here specifically -- test-scope dependencies (Testcontainers,
# Awaitility) would otherwise still need to be resolved from Maven Central to compile test classes
# nothing in this image ever runs, and that resolution is genuinely slow to fetch from inside a
# build container, for artifacts a production image has no other reason to touch at all.
RUN ./mvnw -q -pl inventory-service -am -Dmaven.test.skip=true package

FROM eclipse-temurin:21-jre AS runtime
WORKDIR /app

# The wildcard is deliberate: it survives a version bump in inventory-service/pom.xml without this
# file needing an edit to match it.
COPY --from=build /workspace/inventory-service/target/inventory-service-*.jar app.jar

EXPOSE 8082

ENTRYPOINT ["java", "-jar", "app.jar"]
