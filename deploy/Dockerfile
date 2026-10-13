# The agent service, for the demonstration deployment. Built from the repository root:
#
#   docker build -f deploy/Dockerfile -t nkap-agent .
#
# Two stages: Maven builds the jar and collects its runtime libraries, then a JRE runs them. The base images are
# pinned by digest (the tag is only for the reader); deploy/RUNBOOK.md says how to refresh them.
#
# No key, token or salt is ever in an image layer: the service reads them from files under /run/secrets, which
# compose mounts at run time. The build context is limited by .dockerignore to pom.xml, src and demo/traces.

FROM maven:3.9.9-eclipse-temurin-21@sha256:3a4ab3276a087bf276f79cae96b1af04f53731bec53fb2e651aca79e4b10211e AS build
WORKDIR /src
COPY pom.xml ./
COPY src ./src
# The BuildKit cache mount keeps the Maven repository between builds without baking it into a layer.
RUN --mount=type=cache,target=/root/.m2 \
    mvn -B -ntp -q -DskipTests package dependency:copy-dependencies -DincludeScope=runtime -DoutputDirectory=target/lib

FROM eclipse-temurin:21.0.5_11-jre-alpine@sha256:decee204b9a1eb333c364ba4d859a6b1380eb13f0980d2acfd65c09fee53a48a

# A numeric, non-root user with no home and no login shell. /state is where the service keeps its budgets and its
# record of approved payments; compose mounts a volume there, which takes this ownership.
RUN addgroup -S -g 10001 agent \
 && adduser -S -u 10001 -G agent -H -h /nonexistent -s /sbin/nologin agent \
 && mkdir /state \
 && chown 10001:10001 /state \
 && chmod 700 /state

WORKDIR /app
COPY --from=build /src/target/nkap-agent-*.jar /app/app.jar
COPY --from=build /src/target/lib /app/lib
COPY demo/traces /app/demo/traces

USER 10001:10001

# Listening on all interfaces is for the container's own networks; no port is published (see compose.demo.yaml).
ENV NKAP_AGENT_BIND=0.0.0.0 \
    NKAP_AGENT_PORT=8088 \
    NKAP_AGENT_TRACES_DIR=/app/demo/traces \
    NKAP_AGENT_STATE_DIR=/state

EXPOSE 8088
HEALTHCHECK --interval=15s --timeout=3s --start-period=30s --retries=3 \
  CMD wget -q -O /dev/null http://127.0.0.1:8088/api/status || exit 1

ENTRYPOINT ["java", "-XX:MaxRAMPercentage=70", "-XX:+ExitOnOutOfMemoryError", "-cp", "/app/app.jar:/app/lib/*", "dev.nkap.agent.web.AgentService"]
