FROM node:24-bookworm-slim AS build
WORKDIR /app
COPY package*.json ./
RUN npm ci --no-audit --no-fund
COPY web ./web
COPY vite.config.ts ./
RUN npm run build

FROM node:24-bookworm-slim
ENV NODE_ENV=production HOST=0.0.0.0 PORT=8787 DATA_DIR=/app/data
WORKDIR /app
COPY --from=build /app/dist ./dist
COPY server ./server
COPY package.json ./
RUN mkdir /app/data && chown node:node /app/data
EXPOSE 8787
CMD ["node", "server/container.mjs"]
