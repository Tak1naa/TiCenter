#!/usr/bin/env bash
set -euo pipefail

backup_root="${BACKUP_ROOT:?Set BACKUP_ROOT to an absolute directory on a separately backed-up filesystem}"
case "$backup_root" in /*) ;; *) echo "BACKUP_ROOT must be absolute" >&2; exit 2;; esac
timestamp="$(date -u +%Y%m%dT%H%M%SZ)"
target="$backup_root/exam-bank-$timestamp"
mkdir -p "$target"

docker compose exec -T mysql sh -c 'exec mysqldump --single-transaction --routines --events -uexam_bank -p"$MYSQL_PASSWORD" exam_bank' \
  | gzip -9 > "$target/mysql.sql.gz"
docker compose exec -T ocr-service python -c \
  'import urllib.request; r=urllib.request.Request("http://qdrant:6333/collections/exam_chunks/snapshots", method="POST"); print(urllib.request.urlopen(r, timeout=120).read().decode())' \
  > "$target/qdrant-snapshot.json"
snapshot_name="$(python -c 'import json,sys; print(json.load(open(sys.argv[1]))["result"]["name"])' "$target/qdrant-snapshot.json")"
docker compose cp "qdrant:/qdrant/storage/collections/exam_chunks/snapshots/$snapshot_name" "$target/$snapshot_name"
tar --create --gzip --file "$target/exam-bank-files.tar.gz" --directory ./data exam-bank
sha256sum "$target"/* > "$target/SHA256SUMS"
echo "Backup created at $target; copy it off-host and verify restoration monthly."
