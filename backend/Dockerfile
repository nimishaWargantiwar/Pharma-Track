# syntax=docker/dockerfile:1

FROM eclipse-temurin:17-jdk-jammy AS build
WORKDIR /app

# Leverage Docker layer caching for Maven wrapper and dependencies before copying source
COPY mvnw ./
COPY .mvn .mvn
COPY pom.xml ./
RUN chmod +x mvnw && ./mvnw dependency:go-offline -B

# Copy application source (includes Flyway migrations and application-prod.properties;
# local config/secrets are excluded via .dockerignore)
COPY src src
RUN ./mvnw clean package -DskipTests -B \
    && cp target/pharmatrack-backend-*.jar /app/pharmatrack-backend.jar

FROM eclipse-temurin:17-jre-jammy AS runtime
WORKDIR /app

RUN groupadd --system spring && useradd --system --gid spring spring

COPY --from=build --chown=spring:spring /app/pharmatrack-backend.jar /app/app.jar

USER spring:spring

# Activate production profile cleanly in container deployments; Render injects PORT dynamically
ENV SPRING_PROFILES_ACTIVE=prod \
    PORT=8080 \
    JAVA_OPTS="-XX:MaxRAMPercentage=75.0 -Djava.security.egd=file:/dev/urandom"

EXPOSE 8080

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]

