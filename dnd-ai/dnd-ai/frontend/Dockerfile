# ── Build stage ──────────────────────────────────────
FROM node:22-alpine AS build
WORKDIR /app

COPY package.json package-lock.json ./
RUN npm ci

COPY . .

ARG BACKEND_URL=http://localhost:8080
ENV BACKEND_URL=${BACKEND_URL}

ARG NEXT_PUBLIC_KEYCLOAK_URL=http://localhost:8180
ENV NEXT_PUBLIC_KEYCLOAK_URL=${NEXT_PUBLIC_KEYCLOAK_URL}

RUN npm run build

# ── Runtime stage ────────────────────────────────────
FROM node:22-alpine
WORKDIR /app

COPY --from=build /app/.next/standalone ./
COPY --from=build /app/.next/static ./.next/static
COPY --from=build /app/public ./public

EXPOSE 3000
ENV HOSTNAME=0.0.0.0
CMD ["node", "server.js"]
