FROM python:3.12-slim AS f2-builder

ENV TZ=Asia/Shanghai

RUN python3 -m venv /opt/venv && \
    /opt/venv/bin/pip install --upgrade pip && \
    /opt/venv/bin/pip install --no-cache-dir f2 "psycopg[binary]"

FROM eclipse-temurin:17-jre

ENV TZ=Asia/Shanghai
ENV SPRING_PROFILES_ACTIVE=docker
ARG YT_DLP_VERSION=2026.03.17
ARG YT_DLP_DOWNLOAD_BASE=https://github.com/yt-dlp/yt-dlp/releases

RUN apt-get update && \
    apt-get install -y --no-install-recommends \
        ffmpeg \
        wget \
        curl \
        ca-certificates && \
    apt-get clean && \
    rm -rf /var/lib/apt/lists/*

COPY --from=f2-builder /usr/local /usr/local
COPY --from=f2-builder /opt/venv /opt/venv

ENV PATH="/opt/venv/bin:/usr/local/bin:$PATH"

RUN set -eux; \
    if [ "${YT_DLP_VERSION}" = "latest" ]; then \
      URL="${YT_DLP_DOWNLOAD_BASE}/latest/download/yt-dlp"; \
    else \
      URL="${YT_DLP_DOWNLOAD_BASE}/download/${YT_DLP_VERSION}/yt-dlp"; \
    fi; \
    wget -O /usr/local/bin/yt-dlp "$URL"; \
    chmod a+rx /usr/local/bin/yt-dlp
ENV YT_DLP_PATH=/usr/local/bin/yt-dlp

COPY --from=denoland/deno:bin-2.6.9 /deno /usr/local/bin/deno

VOLUME ["/tmp", "/app"]
COPY backstage/src/main/docker/buildx/db /home/app/db/
COPY backstage/src/main/docker/buildx/script /home/app/script/
RUN mkdir -p /app/resources

COPY backstage/target/StreamVault-0.0.1-SNAPSHOT.jar /app.jar

ENTRYPOINT ["java", "-XX:+UseContainerSupport", "-Djava.security.egd=file:/dev/./urandom", "-jar", "/app.jar"]
