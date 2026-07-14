# ---- build ----
FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /app
COPY pom.xml .
RUN mvn -q dependency:go-offline
COPY src ./src
RUN mvn -q -DskipTests package

# ---- run ----
FROM eclipse-temurin:17-jre
WORKDIR /app
COPY --from=build /app/target/forgetdm-1.0.0.jar app.jar
RUN apt-get update && apt-get install -y --no-install-recommends wget && rm -rf /var/lib/apt/lists/* \
    && useradd --system --uid 10001 --home /app forgetdm && chown -R 10001:0 /app
USER 10001
EXPOSE 8088 8090
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError -Djava.security.egd=file:/dev/urandom"
HEALTHCHECK --interval=30s --timeout=5s --start-period=45s --retries=3 CMD ["wget","-qO-","http://localhost:8090/actuator/health/readiness"]
ENTRYPOINT ["java","-jar","app.jar"]
