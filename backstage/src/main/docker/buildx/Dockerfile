FROM python:3.12-slim AS f2-builder

ENV TZ=Asia/Shanghai

# 在固定版本 Python 环境中安装 f2，避免在 Python 3.14 下触发 pydantic-core 源码编译
RUN python3 -m venv /opt/venv && \
    /opt/venv/bin/pip install --upgrade pip && \
    /opt/venv/bin/pip install --no-cache-dir f2==0.0.1.7 && \
    /opt/venv/bin/python3 -c "import importlib.metadata; print('f2=' + importlib.metadata.version('f2'))"


FROM eclipse-temurin:17-jre

ENV TZ=Asia/Shanghai
ARG YT_DLP_VERSION=2026.07.04
ARG YT_DLP_DOWNLOAD_BASE=https://github.com/yt-dlp/yt-dlp/releases

# 安装运行时依赖（ffmpeg + 基础工具）
RUN apt-get update && \
    apt-get install -y --no-install-recommends \
        ffmpeg \
        wget \
        curl \
        ca-certificates && \
    apt-get clean && \
    rm -rf /var/lib/apt/lists/*

# 从 f2-builder 复制 Python 运行时与虚拟环境
COPY --from=f2-builder /usr/local /usr/local
COPY --from=f2-builder /opt/venv /opt/venv

# 添加 Python 与虚拟环境路径到环境变量中
ENV PATH="/opt/venv/bin:/usr/local/bin:$PATH"

# 安装 yt-dlp（latest 使用稳定 latest 下载地址，固定版本使用指定 tag）
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

# 准备目录结构
VOLUME ["/tmp", "/app"]
COPY db /home/app/db/
COPY script /home/app/script/
RUN mkdir -p /app/resources

ADD StreamVault-0.0.1-SNAPSHOT.jar app.jar

# 启动命令
ENTRYPOINT ["java", "-XX:+UseContainerSupport", "-Djava.security.egd=file:/dev/./urandom", "-jar", "/app.jar", "--spring.profiles.active=docker"]
