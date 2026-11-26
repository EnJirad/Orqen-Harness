FROM python:3.11-slim

WORKDIR /opt/orqen-remote
COPY orqen_queue.py orqen_api.py orqen_worker.py ./

RUN mkdir -p /data/workspaces && useradd -m -u 10001 orqen && chown -R orqen:orqen /data /opt/orqen-remote
USER orqen

ENV ORQEN_BIND_HOST=0.0.0.0
ENV ORQEN_BIND_PORT=8787
ENV ORQEN_DB_PATH=/data/orqen_remote.db
ENV ORQEN_WORKSPACE_ROOT=/data/workspaces

EXPOSE 8787
CMD ["python3", "orqen_api.py"]
