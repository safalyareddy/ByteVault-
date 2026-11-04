# Runtime-only image: the jar is built on the host by scripts/build.sh (ADR-0008 keeps disk use low).
FROM eclipse-temurin:21.0.7_6-jre-alpine
RUN addgroup -S bytevault && adduser -S -G bytevault -H bytevault \
 && mkdir -p /var/lib/bytevault/node && chown bytevault:bytevault /var/lib/bytevault/node \
 && chmod 700 /var/lib/bytevault/node
WORKDIR /app
COPY target/bytevault-storage-node.jar app.jar
USER bytevault
VOLUME /var/lib/bytevault/node
EXPOSE 9100
ENV BV_NODE_DATA_DIR=/var/lib/bytevault/node \
    JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=70 -XX:+ExitOnOutOfMemoryError"
HEALTHCHECK --interval=10s --timeout=3s --start-period=30s --retries=5 \
  CMD wget -q -O /dev/null http://127.0.0.1:9100/internal/v1/health || exit 1
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
