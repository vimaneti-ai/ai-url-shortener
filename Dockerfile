# Build Stage
FROM maven:3.9.9-eclipse-temurin-21 AS build
WORKDIR /app
# Copy the POM and download dependencies first (for faster caching)
COPY pom.xml .
RUN mvn dependency:go-offline -B
# Copy the source code and build
COPY src ./src
RUN mvn clean package -DskipTests

# Run Stage
FROM eclipse-temurin:21-jre-jammy
WORKDIR /app
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/*
# Copy the packaged jar from the build stage
COPY --from=build /app/target/URL-Shortener-0.0.1-SNAPSHOT.jar app.jar
# Expose the default Spring Boot port
EXPOSE 8080
HEALTHCHECK --interval=10s --timeout=5s --start-period=30s --retries=12 \
    CMD curl --fail --silent http://localhost:8080/actuator/health || exit 1
# Run the application
ENTRYPOINT ["java", "-jar", "app.jar"]
