# web-app production image. Build the bundle first: pnpm --filter @eim/web-app build
FROM nginx:1.27-alpine
COPY dist/ /usr/share/nginx/html/
EXPOSE 8080
RUN sed -i 's/listen       80;/listen       8080;/' /etc/nginx/conf.d/default.conf
