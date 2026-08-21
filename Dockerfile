# ── Build stage ──────────────────────────────────────────────
FROM eclipse-temurin:25-jdk AS builder
WORKDIR /app

# Cache Maven dependencies first (layer optimization)
COPY pom.xml mvnw ./
COPY .mvn .mvn
RUN chmod +x mvnw && ./mvnw dependency:resolve -q

# Copy source and build
COPY src src
RUN ./mvnw package -DskipTests -q

# ── Run stage ─────────────────────────────────────────────────
FROM eclipse-temurin:25-jre
WORKDIR /app

# Non-root user for security
RUN groupadd --system apidrift && useradd --system --gid apidrift apidrift
USER apidrift

COPY --from=builder /app/target/*.jar app.jar

EXPOSE 8080

# Health checks handled by the platform (Render, Fly.io, K8s)
# Container-level HEALTHCHECK omitted — JRE base image lacks curl/wget

ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -XX:MaxRAMPercentage=75.0 -XX:+UseG1GC -XX:+ExitOnOutOfMemoryError -Djava.security.egd=file:/dev/./urandom -jar app.jar"]
