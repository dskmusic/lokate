FROM python:3.12-slim

WORKDIR /app

COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt

COPY app ./app
COPY web_inicial ./web_inicial

RUN mkdir -p /data
VOLUME ["/data"]

EXPOSE 8082

# UN SOLO worker, a proposito: el seguimiento en vivo y los relojes del servidor viven en
# dicts en memoria del proceso (_live_until y _last_ping en routers/locations.py, _alerted en
# silence.py). Con --workers 2 cada peticion cae en un proceso distinto y el tiempo real se
# rompe a medias y en silencio. Si algun dia hace falta escalar, eso va antes a Redis o a la BD.
# --proxy-headers: nginx (VPS) termina el HTTPS y reenvía como HTTP plano al contenedor,
# pero ya manda X-Forwarded-Proto/X-Forwarded-For — sin esto uvicorn genera enlaces http://
# en vez de https:// (formulario del admin, CSS...) y el navegador los bloquea/avisa.
CMD ["uvicorn", "app.main:app", "--host", "0.0.0.0", "--port", "8082", "--proxy-headers", "--forwarded-allow-ips=*"]
