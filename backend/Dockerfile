# Runtime-only image: the jar is built on the host by scripts/build.sh (ADR-0008 keeps disk use low).
FROM eclipse-temurin:21.0.7_6-jre-alpine
RUN addgroup -S bytevault && adduser -S -G bytevault -H bytevault
WORKDIR /app
COPY target/bytevault-backend.jar app.jar
USER bytevault
EXPOSE 8090
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=70 -XX:+ExitOnOutOfMemoryError"
HEALTHCHECK --interval=10s --timeout=3s --start-period=40s --retries=5 \
  CMD wget -q -O /dev/null http://127.0.0.1:8090/actuator/health/liveness || exit 1
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
