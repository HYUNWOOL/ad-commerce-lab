# agent(MCP 도구 서버). 의존성은 uv.lock 그대로 설치하고, 시작할 때 Alembic 마이그레이션을 먼저 올린다.
FROM python:3.13.18-slim
COPY --from=ghcr.io/astral-sh/uv:0.13.7 /uv /bin/uv
WORKDIR /app
COPY pyproject.toml uv.lock ./
RUN uv sync --frozen --no-dev --no-install-project
COPY alembic.ini ./
COPY migrations ./migrations
COPY app ./app
ENV PATH="/app/.venv/bin:$PATH" PYTHONUNBUFFERED=1
EXPOSE 8000
CMD ["sh", "-c", "alembic upgrade head && exec uvicorn --factory app.main:build --host 0.0.0.0 --port 8000"]
