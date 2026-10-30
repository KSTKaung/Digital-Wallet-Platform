# syntax=docker/dockerfile:1
# Frontend image: build the SPA with Node, serve it with unprivileged nginx on 5173.
# nginx also proxies /api to the backend so the browser talks to one origin.

FROM node:24-alpine3.24 AS build
WORKDIR /app
COPY package.json package-lock.json ./
RUN npm ci --no-audit --no-fund
COPY . .
RUN npm run build

FROM nginxinc/nginx-unprivileged:1.31.7-alpine
COPY nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=build /app/dist /usr/share/nginx/html
EXPOSE 5173
