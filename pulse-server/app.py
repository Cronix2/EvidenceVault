"""EvidenceVault Pulse append-only encrypted evidence ingestion server.

Requires HTTPS termination in front of the service. PULSE_UPLOAD_TOKEN is upload-only;
this API has no modification or delete route.
"""
import base64
import hashlib
import hmac
import os
from pathlib import Path
import re
import sqlite3
import tempfile
from fastapi import FastAPI, HTTPException, Header, Request
from fastapi.responses import JSONResponse

ROOT = Path(os.environ.get("PULSE_DATA_DIR", "/data")).resolve()
TOKEN = os.environ.get("PULSE_UPLOAD_TOKEN", "")
MAX_BYTES = int(os.environ.get("PULSE_MAX_BYTES", str(2 * 1024**3)))
if len(TOKEN) < 32:
    raise RuntimeError("PULSE_UPLOAD_TOKEN must be a strong random secret of at least 32 characters")
ROOT.mkdir(parents=True, exist_ok=True)
BLOBS = ROOT / "objects"
BLOBS.mkdir(parents=True, exist_ok=True)
DB = ROOT / "index.sqlite3"

def connect():
    db = sqlite3.connect(DB, timeout=30)
    db.execute("PRAGMA journal_mode=WAL")
    db.execute("""CREATE TABLE IF NOT EXISTS versions (
        id INTEGER PRIMARY KEY AUTOINCREMENT,
        device_id TEXT NOT NULL,
        filename TEXT NOT NULL,
        sha256 TEXT NOT NULL,
        bytes INTEGER NOT NULL,
        created_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ','now')),
        UNIQUE(device_id, filename, sha256)
    )""")
    return db

with connect() as db:
    db.commit()

app = FastAPI(title="EvidenceVault Pulse", docs_url=None, redoc_url=None)

@app.get("/health")
async def health():
    return {"status": "ok"}

@app.post("/v1/evidence")
async def ingest(
    request: Request,
    authorization: str = Header(default=""),
    x_evidence_name: str = Header(default=""),
    x_evidence_sha256: str = Header(default=""),
    x_device_id: str = Header(default=""),
):
    expected = "Bearer " + TOKEN
    if not hmac.compare_digest(authorization, expected):
        raise HTTPException(401, "Invalid upload credential")
    if not re.fullmatch(r"[a-f0-9]{64}", x_evidence_sha256):
        raise HTTPException(422, "Invalid content digest")
    if not re.fullmatch(r"[a-zA-Z0-9-]{8,64}", x_device_id):
        raise HTTPException(422, "Invalid device identifier")
    try:
        name = base64.urlsafe_b64decode(x_evidence_name + "==").decode("utf-8")
    except Exception:
        raise HTTPException(422, "Invalid file name")
    if name != os.path.basename(name) or not (
        name.endswith(".m4a.enc") or name.endswith(".mp4.enc") or
        name in ("manifest.json.enc", "index.json.enc")
    ):
        raise HTTPException(422, "Unsupported encrypted evidence type")

    # The uploader never receives rights to replace or delete objects.
    # Content address avoids collisions; file creation is exclusive.
    tmp = None
    size = 0
    sha = hashlib.sha256()
    try:
        with tempfile.NamedTemporaryFile(dir=ROOT, prefix="incoming-", delete=False) as out:
            tmp = Path(out.name)
            async for chunk in request.stream():
                size += len(chunk)
                if size > MAX_BYTES:
                    raise HTTPException(413, "Upload exceeds server limit")
                sha.update(chunk)
                out.write(chunk)
        if sha.hexdigest() != x_evidence_sha256:
            raise HTTPException(422, "Digest mismatch")
        dest = BLOBS / (x_evidence_sha256 + ".blob")
        if not dest.exists():
            try:
                os.link(tmp, dest)  # atomic exclusive create, never overwrite
            except FileExistsError:
                pass
        with connect() as db:
            cursor = db.execute(
                "INSERT OR IGNORE INTO versions (device_id, filename, sha256, bytes) VALUES (?, ?, ?, ?)",
                (x_device_id, name, x_evidence_sha256, size)
            )
            inserted = cursor.rowcount == 1
            db.commit()
            row = db.execute(
                "SELECT id, created_at FROM versions WHERE device_id=? AND filename=? AND sha256=?",
                (x_device_id, name, x_evidence_sha256)
            ).fetchone()
        return JSONResponse(
            {"version_id": row[0], "received_at": row[1], "sha256": x_evidence_sha256, "bytes": size},
            status_code=201 if inserted else 200
        )
    finally:
        if tmp is not None:
            tmp.unlink(missing_ok=True)
