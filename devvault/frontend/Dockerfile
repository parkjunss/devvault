FROM node:22-alpine AS build

WORKDIR /app
COPY package.json package-lock.json ./
RUN npm ci
COPY . .
ARG NEXT_PUBLIC_DEMO_MODE=false
ENV NEXT_PUBLIC_DEMO_MODE=$NEXT_PUBLIC_DEMO_MODE
RUN npm run build

FROM node:22-alpine

WORKDIR /app
ENV NODE_ENV=production
ENV PORT=3000
RUN addgroup -S devvault && adduser -S devvault -G devvault
COPY --from=build --chown=devvault:devvault /app/.next/standalone ./
COPY --from=build --chown=devvault:devvault /app/.next/static ./.next/static
COPY --from=build --chown=devvault:devvault /app/public ./public
USER devvault
EXPOSE 3000
CMD ["node", "server.js"]
