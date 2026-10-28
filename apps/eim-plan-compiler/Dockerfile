# Runtime image. Requires the service jar built first: mvn -f apps/pom.xml package
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
COPY target/quarkus-app/ ./quarkus-app/
EXPOSE 8084
ENTRYPOINT ["java", "-jar", "quarkus-app/quarkus-run.jar"]
