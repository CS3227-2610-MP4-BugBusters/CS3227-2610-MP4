FROM node:22-bookworm-slim AS frontend
WORKDIR /workspace/src/frontend/shared/markdown
COPY src/frontend/shared/markdown/package.json src/frontend/shared/markdown/package-lock.json ./
RUN npm ci
COPY src/frontend/shared/markdown/ ./
WORKDIR /workspace/src/frontend/maintainer
COPY src/frontend/maintainer/package.json src/frontend/maintainer/package-lock.json ./
RUN npm ci
COPY src/frontend/maintainer/ ./
COPY src/frontend/shared/ ../shared/
RUN npm run build

FROM node:22-bookworm-slim AS developer-frontend
WORKDIR /workspace/src/frontend/shared/markdown
COPY src/frontend/shared/markdown/package.json src/frontend/shared/markdown/package-lock.json ./
RUN npm ci
COPY src/frontend/shared/markdown/ ./
WORKDIR /workspace/src/frontend/developer
COPY src/frontend/developer/package.json src/frontend/developer/package-lock.json ./
RUN npm ci
COPY src/frontend/developer/ ./
COPY src/frontend/shared/ ../shared/
RUN npm run build

FROM node:22-bookworm-slim AS reporter-frontend
WORKDIR /workspace/src/frontend/shared/markdown
COPY src/frontend/shared/markdown/package.json src/frontend/shared/markdown/package-lock.json ./
RUN npm ci
COPY src/frontend/shared/markdown/ ./
WORKDIR /workspace/src/frontend/reporter
COPY src/frontend/reporter/package.json src/frontend/reporter/package-lock.json ./
RUN npm ci
COPY src/frontend/reporter/ ./
RUN npm run build

FROM eclipse-temurin:17-jdk-jammy AS package
WORKDIR /workspace/src/backend/auth
COPY src/backend/auth/ ./
COPY src/frontend/shared/role-workspace.css /workspace/src/frontend/shared/role-workspace.css
COPY src/backend/platform/ ../platform/
COPY src/backend/maintainer/ ../maintainer/
COPY src/backend/socllm/ ../socllm/
COPY src/backend/developer/src/ ../developer/src/
COPY src/backend/reporter/src/ ../reporter/src/
COPY src/backend/discussion/src/ ../discussion/src/
COPY src/backend/developer/migrations/ ../developer/migrations/
COPY src/backend/shared/migrations/ ../shared/migrations/
COPY src/backend/platform/deployment/package.gradle ../platform/deployment/package.gradle
COPY --from=frontend /workspace/src/frontend/maintainer/dist /workspace/frontend-dist
COPY --from=developer-frontend /workspace/src/frontend/developer/dist /workspace/developer-frontend-dist
COPY --from=reporter-frontend /workspace/src/frontend/reporter/dist /workspace/reporter-frontend-dist
ARG RENDER_GIT_COMMIT
ARG GITHUB_SHA
RUN chmod +x ./gradlew \
    && ./gradlew --no-daemon -I ../platform/deployment/package.gradle authDistribution \
    -PbuildRevision="${RENDER_GIT_COMMIT:-${GITHUB_SHA:-unknown}}" \
    -PfrontendDist=/workspace/frontend-dist \
    -PdeveloperFrontendDist=/workspace/developer-frontend-dist \
    -PreporterFrontendDist=/workspace/reporter-frontend-dist

FROM eclipse-temurin:17-jre-jammy AS runtime
WORKDIR /app
ENV SERVER_ADDRESS=0.0.0.0
ENV SERVER_PORT=10000
COPY --from=package /workspace/src/backend/auth/build/auth-distribution/classes/ ./classes/
COPY --from=package /workspace/src/backend/auth/build/auth-distribution/lib/ ./lib/
COPY src/backend/platform/deployment/start.sh ./start.sh
RUN sed -i 's/\r$//' /app/start.sh \
    && chmod 0555 /app/start.sh \
    && chown -R 10001:10001 /app
USER 10001:10001
EXPOSE 10000
ENTRYPOINT ["/app/start.sh"]
