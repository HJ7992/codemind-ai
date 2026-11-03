FROM qdrant/qdrant:v1.15.4
USER root
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/* \
    && chown -R 1000:1000 /qdrant
USER 1000:1000
