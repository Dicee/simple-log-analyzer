# TODO: multi-stage build bundling the ingestion-service jar and the desktop-app assets
FROM eclipse-temurin:21-jre
WORKDIR /app
COPY entrypoint.sh /app/entrypoint.sh
RUN chmod +x /app/entrypoint.sh
ENTRYPOINT ["/app/entrypoint.sh"]
