# SDUI 렌더러: 빌드한 정적 파일을 nginx가 내려주고, /api는 gateway로 넘긴다.
FROM node:24.13-alpine AS build
WORKDIR /web
COPY package.json package-lock.json ./
RUN npm ci --no-audit --no-fund
COPY . .
RUN npm run build

FROM nginx:1.29-alpine
COPY nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=build /web/dist /usr/share/nginx/html
