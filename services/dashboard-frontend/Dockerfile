# Compila React con las direcciones que utilizará el navegador.
FROM node:24-alpine AS build
WORKDIR /app
COPY package.json package-lock.json ./
RUN npm ci
COPY . .
ARG VITE_WS_URL=ws://localhost:18080/ws
ARG VITE_NOTIFICATION_ORIGIN=http://localhost:18082
ENV VITE_WS_URL=$VITE_WS_URL VITE_NOTIFICATION_ORIGIN=$VITE_NOTIFICATION_ORIGIN
RUN npm run build

# Nginx sirve los archivos estáticos sin ejecutar Node en producción.
FROM nginxinc/nginx-unprivileged:1.28-alpine
COPY nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=build /app/dist /usr/share/nginx/html
EXPOSE 8080
