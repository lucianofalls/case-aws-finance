# Imagem única dos serviços Spring Boot (multi-arquitetura: roda em Graviton/ARM64 no Fargate).
# Uso: docker build --build-arg SERVICO=autorizacao-service -t autorizacao-service servicos/
FROM eclipse-temurin:21-jre-alpine

ARG SERVICO
RUN addgroup -S app && adduser -S app -G app
WORKDIR /app

# ADOT (OpenTelemetry) Java agent: traces → collector (sidecar no ECS, Jaeger no compose).
ADD --chmod=644 https://github.com/aws-observability/aws-otel-java-instrumentation/releases/download/v1.32.6/aws-opentelemetry-agent.jar /opt/otel/aws-opentelemetry-agent.jar
COPY --chown=app:app ${SERVICO}/target/${SERVICO}.jar /app/app.jar

USER app
EXPOSE 8080
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -XX:+UseZGC -XX:+ZGenerational -javaagent:/opt/otel/aws-opentelemetry-agent.jar" \
    OTEL_METRICS_EXPORTER=none \
    OTEL_LOGS_EXPORTER=none
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
