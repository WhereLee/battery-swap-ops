# Runtime image for the swap-ops platform (batch37).
#
# Design choices and why:
#   * Runtime-only, not multi-stage: Maven already built the jar in CI/local, and the CI
#     `docker` job builds this image right after `mvn package`. A builder stage would
#     re-download the whole dependency tree inside BuildKit for no extra guarantee.
#   * Non-root user: the container never needs to write inside itself (logs go to stdout,
#     state lives in MySQL/Redis), so running as an unprivileged user costs nothing.
#   * HEALTHCHECK hits the same endpoint the systemd deployment is monitored with, so
#     `docker ps` answers "is it really up" without extra tooling.
#   * No secrets baked in: every credential arrives through environment variables, matching
#     the workspace rule that the repository carries zero plaintext keys. The platform
#     fails fast at startup when a required one is missing.
FROM eclipse-temurin:17-jre-jammy

# curl is only for HEALTHCHECK; keep the layer small.
RUN apt-get update \
 && apt-get install -y --no-install-recommends curl tzdata \
 && rm -rf /var/lib/apt/lists/* \
 && useradd --system --uid 10001 --create-home swap

WORKDIR /app
COPY swap-server/target/swap-server-1.0.0.jar /app/app.jar
RUN chown -R swap:swap /app

USER swap
EXPOSE 8400

# MaxRAMPercentage keeps the heap proportional to the container limit instead of the host's
# RAM (the 4G cloud box runs the JVM at -Xmx768m; a container should behave the same way).
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=70 -Duser.timezone=Asia/Shanghai" \
    SPRING_PROFILES_ACTIVE=""

HEALTHCHECK --interval=10s --timeout=3s --start-period=45s --retries=6 \
    CMD curl -fs http://127.0.0.1:8400/api/actuator/health || exit 1

ENTRYPOINT ["java", "-jar", "/app/app.jar"]
