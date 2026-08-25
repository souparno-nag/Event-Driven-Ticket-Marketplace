# Build context is the REPOSITORY ROOT, not this directory:
#   docker build -f order-service/Dockerfile -t order-service .
# order-service is one module in a Maven reactor and needs the parent pom and common-events above
# it to build at all -- a build context scoped to order-service/ alone could never see either one.
# .dockerignore at the repository root already documents this and keeps that wider context small.

FROM eclipse-temurin:21-jdk AS build
WORKDIR /workspace

# Poms and the wrapper first, source afterward -- so an ordinary code change invalidates only the
# later COPY/RUN layers, not the dependency-resolution step, which is the slow one to redo.
COPY mvnw mvnw.cmd pom.xml ./
COPY .mvn .mvn
COPY common-events/pom.xml common-events/pom.xml
COPY order-service/pom.xml order-service/pom.xml
RUN ./mvnw -q -pl order-service -am -Dmaven.test.skip=true dependency:go-offline

COPY common-events/src common-events/src
COPY order-service/src order-service/src

# package, not verify: this step ships an already-tested artifact rather than re-proving
# correctness inside the image build. order-service's own integration tests start Postgres and
# Kafka containers of their own (Testcontainers) -- doing that again here would mean Docker
# containers building a Docker image that starts more Docker containers, for no test this artifact
# hasn't already passed in CI or on this machine before the build ever reached this Dockerfile.
#
# -Dmaven.test.skip=true, not -DskipTests: the former also skips COMPILING test sources, not just
# running them. That distinction matters here specifically -- test-scope dependencies (Testcontainers,
# Awaitility) would otherwise still need to be resolved from Maven Central to compile test classes
# nothing in this image ever runs, and that resolution is genuinely slow to fetch from inside a
# build container, for artifacts a production image has no other reason to touch at all.
RUN ./mvnw -q -pl order-service -am -Dmaven.test.skip=true package

FROM eclipse-temurin:21-jre AS runtime
WORKDIR /app

# The wildcard is deliberate: it survives a version bump in order-service/pom.xml without this
# file needing an edit to match it.
COPY --from=build /workspace/order-service/target/order-service-*.jar app.jar

EXPOSE 8081

ENTRYPOINT ["java", "-jar", "app.jar"]
