FROM python:3.12.14-slim

WORKDIR /app

ENV PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1

RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/*

RUN pip install --no-cache-dir opensandbox-server==0.2.2

COPY deploy/opensandbox/opensandbox.toml /app/opensandbox.toml

EXPOSE 8080

ENTRYPOINT ["opensandbox-server", "--config", "/app/opensandbox.toml"]