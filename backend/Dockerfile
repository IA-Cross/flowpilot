# ---- Stage 1: build ----
FROM eclipse-temurin:21-jdk-alpine AS build
WORKDIR /app

# Copy Gradle wrapper + build scripts first (cached unless build files change)
COPY gradlew settings.gradle.kts build.gradle.kts ./
COPY gradle/ gradle/

# Download dependencies before copying sources (layer cache)
RUN ./gradlew dependencies --no-daemon --quiet || true

# Copy sources and build
COPY src/ src/
RUN ./gradlew bootJar --no-daemon -x test

# ---- Stage 2: runtime ----
FROM eclipse-temurin:21-jre-alpine AS runtime
WORKDIR /app

# Non-root user for security
RUN addgroup -S flowpilot && adduser -S flowpilot -G flowpilot
USER flowpilot

COPY --from=build /app/build/libs/flowpilot.jar app.jar

EXPOSE 8080

# Virtual threads are configured via application.yml (spring.threads.virtual.enabled=true)
ENTRYPOINT ["java", "-jar", "app.jar"]
