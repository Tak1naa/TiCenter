# AI 智能题库 - 前端镜像（Vite 构建 + Nginx 静态托管 + 反向代理）
FROM node:22-alpine AS build
WORKDIR /app
COPY package.json package-lock.json* ./
RUN npm ci 2>/dev/null || npm install
COPY . .
RUN npm run build

FROM nginx:alpine
COPY --from=build /app/dist /usr/share/nginx/html
COPY nginx.conf /etc/nginx/conf.d/default.conf.template
COPY nginx-limits.conf /etc/nginx/conf.d/00-limits.conf
EXPOSE 80
# 启动时用 API_SERVICE_TOKEN 替换模板占位，生成正式配置
CMD sh -c 'envsubst "\$API_SERVICE_TOKEN" < /etc/nginx/conf.d/default.conf.template > /etc/nginx/conf.d/default.conf && exec nginx -g "daemon off;"'
