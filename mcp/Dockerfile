# syntax=docker/dockerfile:1.7

# ---------- Build stage ----------
FROM node:22-alpine AS build
WORKDIR /build

COPY package.json package-lock.json* ./
RUN npm ci

COPY tsconfig.json ./
COPY src ./src
RUN npm run build && npm prune --omit=dev

# ---------- Runtime stage ----------
FROM node:22-alpine AS runtime
WORKDIR /app

# Non-root user mirroring the SDA image's posture.
RUN addgroup -S -g 1001 mcp && \
    adduser -S -u 1001 -G mcp -h /app -s /sbin/nologin mcp

COPY --from=build --chown=mcp:mcp /build/node_modules ./node_modules
COPY --from=build --chown=mcp:mcp /build/dist ./dist
COPY --from=build --chown=mcp:mcp /build/package.json ./package.json

USER mcp

ENV SDA_BASE_URL="http://sda:8080"

# stdio MCP servers are spawned per-session by the client (e.g. `docker exec -i ... node dist/index.js`).
# This container has no daemon to run on its own — it stays alive so clients have something to exec into.
CMD ["tail", "-f", "/dev/null"]
