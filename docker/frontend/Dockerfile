FROM node:24.18.0-alpine AS build

WORKDIR /workspace

COPY core/core-frontend/package.json ./
RUN npm install

COPY core/core-frontend/ ./
RUN npm run build

FROM nginx:1.30.4-alpine

COPY deploy/nginx/default.conf /etc/nginx/conf.d/default.conf
COPY --from=build /workspace/dist /usr/share/nginx/html

EXPOSE 80