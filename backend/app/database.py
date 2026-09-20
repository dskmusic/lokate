import os

from sqlalchemy import create_engine, event
from sqlalchemy.orm import declarative_base, sessionmaker

DATABASE_URL = os.getenv("DATABASE_URL", "sqlite:////data/lokate.db")

# timeout: cuánto espera una escritura a que la anterior suelte la base antes de rendirse con
# "database is locked". Por defecto son 5 s; 15 da margen de sobra a una punta de pings.
connect_args = {"check_same_thread": False, "timeout": 15} if DATABASE_URL.startswith("sqlite") else {}
engine = create_engine(DATABASE_URL, connect_args=connect_args)

if DATABASE_URL.startswith("sqlite"):

    @event.listens_for(engine, "connect")
    def _sqlite_pragmas(dbapi_connection, connection_record):
        """WAL: lectores y escritor dejan de bloquearse entre sí. Con el journal por defecto,
        cada ping (dos commits) congelaba de paso las consultas del mapa y del historial, que
        es lo que se nota primero cuando hay varios móviles enviando a la vez.
        synchronous=NORMAL es lo recomendado con WAL: un fsync por checkpoint en vez de uno por
        commit, y lo único que se arriesga es perder los últimos segundos si se va la luz.
        ponytail: PRAGMA por conexión y no una migración — journal_mode se queda escrito en el
        fichero, pero synchronous y busy_timeout son por conexión y hay que ponerlos siempre."""
        cursor = dbapi_connection.cursor()
        cursor.execute("PRAGMA journal_mode=WAL")
        cursor.execute("PRAGMA synchronous=NORMAL")
        cursor.execute("PRAGMA busy_timeout=15000")
        cursor.close()
SessionLocal = sessionmaker(autocommit=False, autoflush=False, bind=engine)
Base = declarative_base()


def get_db():
    db = SessionLocal()
    try:
        yield db
    finally:
        db.close()
