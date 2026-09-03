# Stage 1: Build
FROM node:20.12.1-alpine AS build
WORKDIR /app
COPY package*.json ./
RUN npm ci
COPY . .
RUN npm run build

# Stage 2: Serve with Nginx
FROM nginx:alpine
COPY --from=build /app/dist/url-shortener-ui/browser /usr/share/nginx/html
COPY nginx.conf /etc/nginx/conf.d/default.conf
# Compose DNS is unavailable while this image is being built. Use loopback only
# for syntax validation, then restore the real backend service name in the image.
RUN cp /etc/nginx/conf.d/default.conf /tmp/default.conf \
    && sed -i 's/backend:8080/127.0.0.1:8080/g' /etc/nginx/conf.d/default.conf \
    && nginx -t \
    && mv /tmp/default.conf /etc/nginx/conf.d/default.conf
EXPOSE 80
HEALTHCHECK --interval=10s --timeout=5s --retries=5 \
    CMD wget --quiet --tries=1 --spider http://127.0.0.1/ || exit 1
CMD ["nginx", "-g", "daemon off;"]
