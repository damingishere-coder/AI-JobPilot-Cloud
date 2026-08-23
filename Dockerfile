ARG PLAYWRIGHT_VERSION=1.51.0

FROM mcr.microsoft.com/playwright/java:v${PLAYWRIGHT_VERSION}-noble AS backend-dev
WORKDIR /workspace

ENV TZ=Asia/Shanghai \
    LANG=C.UTF-8 \
    LC_ALL=C.UTF-8 \
    PLAYWRIGHT_BROWSERS_PATH=/ms-playwright

RUN apt-get update \
    && apt-get install -y --no-install-recommends \
        fonts-noto-cjk \
        xauth \
        xvfb \
    && rm -rf /var/lib/apt/lists/*

EXPOSE 8888

FROM mcr.microsoft.com/playwright:v${PLAYWRIGHT_VERSION}-noble AS frontend-dev
WORKDIR /workspace/front

ENV TZ=Asia/Shanghai \
    LANG=C.UTF-8 \
    LC_ALL=C.UTF-8 \
    NEXT_TELEMETRY_DISABLED=1 \
    CHOKIDAR_USEPOLLING=true \
    WATCHPACK_POLLING=true

RUN corepack enable && corepack prepare pnpm@10.20.0 --activate

EXPOSE 6866

FROM node:24.15.0-bookworm-slim AS frontend-build
WORKDIR /workspace/front
ENV NEXT_TELEMETRY_DISABLED=1 \
    CLOUD_LOGIN_REQUIRED=true
RUN corepack enable && corepack prepare pnpm@10.20.0 --activate
COPY front/package.json front/pnpm-lock.yaml front/pnpm-workspace.yaml ./
RUN pnpm install --frozen-lockfile
COPY front/ ./
RUN pnpm build && test -d out

FROM node:24.15.0-bookworm-slim AS web-runtime
WORKDIR /app
ENV NODE_ENV=production \
    PORT=6866 \
    HOSTNAME=0.0.0.0
COPY --from=frontend-build --chown=node:node /workspace/front/out ./out
COPY --chown=node:node front/start-prod.mjs front/server.config.js ./
USER node
EXPOSE 6866
CMD ["node", "start-prod.mjs"]

FROM mcr.microsoft.com/openjdk/jdk:21-ubuntu AS backend-build
WORKDIR /workspace
COPY gradlew settings.gradle build.gradle.kts ./
COPY gradle ./gradle
RUN chmod +x ./gradlew
COPY src ./src
COPY --from=frontend-build /workspace/front/out ./src/main/resources/dist
COPY docker/health/HealthProbe.java /tmp/health-probe/HealthProbe.java
RUN ./gradlew --no-daemon clean bootJar -x test \
    && javac --release 21 -d /tmp/health-probe/classes /tmp/health-probe/HealthProbe.java \
    && jar --create --file /tmp/health-probe.jar --main-class HealthProbe -C /tmp/health-probe/classes . \
    && mkdir -p /tmp/private-storage \
    && touch /tmp/private-storage/.volume-initialized

# Microsoft Java 21 distroless 运行层：无 shell、包管理器、Playwright、Chromium 或 Xvfb。
FROM mcr.microsoft.com/openjdk/jdk:21-distroless AS runtime
WORKDIR /app

ENV TZ=Asia/Shanghai \
    LANG=C.UTF-8 \
    LC_ALL=C.UTF-8 \
    JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -Dfile.encoding=UTF-8"

COPY --from=backend-build --chown=10001:10001 /workspace/build/libs/*.jar /app/app.jar
COPY --from=backend-build --chown=10001:10001 /tmp/health-probe.jar /app/health-probe.jar
COPY --from=backend-build --chown=10001:10001 /tmp/private-storage/ /var/lib/ai-jobpilot/private/
USER 10001:10001
EXPOSE 8888 8889
CMD ["-jar", "/app/app.jar"]
