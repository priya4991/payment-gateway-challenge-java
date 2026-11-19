FROM eclipse-temurin:17-jdk AS build

WORKDIR /workspace
COPY . .
RUN chmod +x ./gradlew \
    && ./gradlew bootJar --no-daemon \
    && jar_path="$(find build/libs -maxdepth 1 -type f -name '*.jar' ! -name '*-plain.jar' -print -quit)" \
    && test -n "$jar_path" \
    && cp "$jar_path" /workspace/payment-gateway.jar

FROM eclipse-temurin:17-jre

WORKDIR /app
COPY --from=build --chown=10001:10001 /workspace/payment-gateway.jar /app/payment-gateway.jar
USER 10001:10001
EXPOSE 8090
ENTRYPOINT ["java", "-jar", "/app/payment-gateway.jar"]
