# ── Build stage ──────────────────────────────────────────────
FROM eclipse-temurin:21-jdk AS builder
WORKDIR /app

# Cache Maven dependencies first (layer optimization)
COPY pom.xml mvnw ./
COPY .mvn .mvn
RUN chmod +x mvnw && ./mvnw dependency:resolve -q

# Copy source and build
COPY src src
RUN ./mvnw package -DskipTests -q

# ── Run stage ─────────────────────────────────────────────────
FROM eclipse-temurin:21-jre
WORKDIR /app

# Non-root user for security
RUN groupadd --system apidrift && useradd --system --gid apidrift apidrift
USER apidrift

COPY --from=builder /app/target/*.jar app.jar

EXPOSE 8080

HEALTHCHECK --interval=30s --timeout=5s --start-period=30s --retries=3 \
  CMD curl -sf http://localhost:8080/actuator/health || exit 1

ENTRYPOINT ["java", "-jar", "app.jar"]
