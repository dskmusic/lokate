import os
from datetime import datetime, timedelta, timezone

import bcrypt
import jwt
from fastapi import Depends, HTTPException, Response, status
from fastapi.security import HTTPAuthorizationCredentials, HTTPBearer
from sqlalchemy.orm import Session

from . import models
from .database import get_db

SECRET_KEY = os.environ["JWT_SECRET"]
ALGORITHM = "HS256"
ACCESS_TOKEN_EXPIRE_DAYS = int(os.getenv("ACCESS_TOKEN_EXPIRE_DAYS", "30"))

# Cuanta vida le tiene que quedar a un token para que se le devuelva otro nuevo. Sin esto, a los
# 30 dias el token muere y ese movil DEJA DE REGISTRAR posiciones hasta que alguien coja ese
# telefono y reescriba la contrasena: en un movil vigilado eso son dias sin que nadie lo note
# (SessionAlert avisa en la barra de ESE movil, que es justo quien no lo esta mirando).
REFRESH_WHEN_DAYS_LEFT = int(os.getenv("REFRESH_WHEN_DAYS_LEFT", "7"))

# Cabecera por la que viaja el token nuevo. El cliente que la entiende lo guarda (ver
# data/remote/AuthInterceptor.kt); el que no, la ignora y sigue como antes.
REFRESH_HEADER = "X-Refresh-Token"

_bearer = HTTPBearer()


def hash_password(password: str) -> str:
    return bcrypt.hashpw(password.encode(), bcrypt.gensalt()).decode()


def verify_password(password: str, password_hash: str) -> bool:
    return bcrypt.checkpw(password.encode(), password_hash.encode())


def create_access_token(user_id: str) -> str:
    expire = datetime.now(timezone.utc) + timedelta(days=ACCESS_TOKEN_EXPIRE_DAYS)
    return jwt.encode({"sub": user_id, "exp": expire}, SECRET_KEY, algorithm=ALGORITHM)


def _expires_soon(exp) -> bool:
    """Le queda menos de REFRESH_WHEN_DAYS_LEFT. Un token sin "exp" (no deberia existir) no se
    renueva: no caduca, asi que no hay nada que salvar."""
    if not isinstance(exp, (int, float)):
        return False
    return exp - datetime.now(timezone.utc).timestamp() < REFRESH_WHEN_DAYS_LEFT * 86400


def get_current_user(
    response: Response,
    credentials: HTTPAuthorizationCredentials = Depends(_bearer),
    db: Session = Depends(get_db),
) -> models.User:
    unauthorized = HTTPException(status.HTTP_401_UNAUTHORIZED, "Invalid or expired token")
    try:
        payload = jwt.decode(credentials.credentials, SECRET_KEY, algorithms=[ALGORITHM])
    except jwt.PyJWTError:
        raise unauthorized
    user = db.get(models.User, payload.get("sub"))
    if user is None:
        raise unauthorized
    # Token deslizante: mientras el movil siga hablando con el servidor, su sesion no caduca
    # nunca. Solo se reemite cuando ya le queda poco, para no firmar un JWT en cada ping.
    # ponytail: por cabecera y desde la dependencia, sin endpoint /refresh ni refresh tokens ni
    # estado en la BD. Techo: FastAPI solo mezcla estas cabeceras cuando el endpoint devuelve un
    # modelo, no cuando devuelve un Response ya montado (avatares y descargas) — da igual,
    # porque el movil vigilado pasa por /location/ping constantemente, que si es de los primeros.
    if _expires_soon(payload.get("exp")):
        response.headers[REFRESH_HEADER] = create_access_token(user.id)
    return user


def get_current_user_with_group(user: models.User = Depends(get_current_user)) -> models.User:
    if user.group_id is None:
        raise HTTPException(status.HTTP_400_BAD_REQUEST, "You must join or create a family group first")
    return user


def get_current_admin_user(user: models.User = Depends(get_current_user)) -> models.User:
    if not user.is_admin:
        raise HTTPException(status.HTTP_403_FORBIDDEN, "Admin only")
    return user
