# Central backend + web app in one image.
#   docker build -t fuelmarking-central .
# Stage 1 builds the Angular app, stage 2 builds the Spring Boot jar with the web app embedded, stage 3 is the runtime.

FROM node:20-alpine AS web
WORKDIR /web
COPY web/package.json web/package-lock.json ./
RUN npm ci
COPY web/ ./
RUN npx ng build --configuration production

FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /app
COPY backend/pom.xml backend/pom.xml
RUN mvn -q -f backend/pom.xml -DskipTests dependency:go-offline
COPY backend/src backend/src
COPY --from=web /web/dist/web/browser backend/src/main/resources/static
RUN mvn -q -f backend/pom.xml -DskipTests package

FROM eclipse-temurin:21-jre
RUN useradd --system --uid 10001 --no-create-home app
WORKDIR /app
COPY --from=build /app/backend/target/fuel-marking-central-1.0-SNAPSHOT.jar app.jar
USER app
EXPOSE 8080
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "/app/app.jar"]
