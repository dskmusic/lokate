FROM python:3.12-slim

WORKDIR /app

COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt

COPY app ./app
COPY web_inicial ./web_inicial

RUN mkdir -p /data
VOLUME ["/data"]

EXPOSE 8082

# --proxy-headers: nginx (VPS) termina el HTTPS y reenvía como HTTP plano al contenedor,
# pero ya manda X-Forwarded-Proto/X-Forwarded-For — sin esto uvicorn genera enlaces http://
# en vez de https:// (formulario del admin, CSS...) y el navegador los bloquea/avisa.
CMD ["uvicorn", "app.main:app", "--host", "0.0.0.0", "--port", "8082", "--proxy-headers", "--forwarded-allow-ips=*"]
