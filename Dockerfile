FROM oven/bun:1.4.4-alpine AS deps
WORKDIR /app
COPY package.json bun.lock bunfig.toml ./
RUN bun install --frozen-lockfile --production --ignore-scripts

FROM oven/bun:1.4.4-alpine
WORKDIR /app
ENV NODE_ENV=production
COPY --from=deps /app/node_modules ./node_modules
COPY package.json tsconfig.json ./
COPY src ./src

# The default transport is stdio, so the image has no HTTP health check; docker-compose.yml adds one for HTTP mode.
USER bun
ENTRYPOINT ["bun", "run", "src/main.ts"]
