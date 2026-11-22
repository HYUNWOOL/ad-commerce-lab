"""Alembic 실행 환경. 모델 없이 SQL 마이그레이션만 쓰고, DB 주소는 DATABASE_URL에서 읽는다(alembic.ini는 Windows 기본 인코딩으로 읽혀 ASCII만 둔다)."""

import os

from alembic import context
from sqlalchemy import create_engine

url = os.environ["DATABASE_URL"].replace("postgresql://", "postgresql+psycopg://", 1)

with create_engine(url).connect() as connection:
    context.configure(connection=connection, target_metadata=None)
    with context.begin_transaction():
        context.run_migrations()
