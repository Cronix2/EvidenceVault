FROM python:3.12-slim
ENV PYTHONUNBUFFERED=1
WORKDIR /srv
RUN groupadd --system pulse && useradd --system --gid pulse pulse
COPY requirements.txt /srv/requirements.txt
RUN pip install --no-cache-dir -r requirements.txt
COPY app.py /srv/app.py
RUN mkdir /data && chown pulse:pulse /data
USER pulse
EXPOSE 8000
CMD ["uvicorn", "app:app", "--host", "0.0.0.0", "--port", "8000"]
