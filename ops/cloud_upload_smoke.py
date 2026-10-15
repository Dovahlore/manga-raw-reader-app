import io
import hashlib
import time
import sys
import zipfile
from pathlib import Path

import requests


ROOT = Path(__file__).resolve().parents[1]


def first_api_token():
    for line in (ROOT / "app.env").read_text(encoding="utf-8").splitlines():
        if line.startswith("MIT_API_TOKEN="):
            value = line.split("=", 1)[1].strip()
            return value.split(",", 1)[0].strip()
    return ""


def main():
    base = "http://127.0.0.1:8020"
    token = first_api_token()
    headers = {"X-API-Token": token}

    health = requests.get(f"{base}/v1/health", headers=headers, timeout=10)
    assert health.status_code == 200, health.text

    buffer = io.BytesIO()
    nonce = str(time.time_ns()).encode()
    with zipfile.ZipFile(buffer, "w") as archive:
        archive.writestr("pages/001.png", b"\x89PNG\r\n\x1a\n smoke" + nonce)
    book_zip = buffer.getvalue()
    book_hash = hashlib.sha256(book_zip).hexdigest()
    now_ms = int(time.time() * 1000)

    invalid = requests.post(
        f"{base}/v1/cloud/books",
        headers=headers,
        files={"file": ("book.zip", book_zip, "application/zip")},
        data={"hash": "invalid", "title": "MIT-SMOKE-INVALID"},
        timeout=30,
    )
    assert invalid.status_code == 400, invalid.text

    invalid_zip = requests.post(
        f"{base}/v1/cloud/books",
        headers=headers,
        files={"file": ("book.zip", b"not-a-zip", "application/zip")},
        data={"hash": book_hash, "title": "MIT-SMOKE-BADZIP"},
        timeout=30,
    )
    assert invalid_zip.status_code == 400, invalid_zip.text

    created = requests.post(
        f"{base}/v1/cloud/books",
        headers=headers,
        files={"file": ("book.zip", book_zip, "application/zip")},
        data={
            "hash": book_hash,
            "title": "MIT-SMOKE-OK",
            "folder": "MIT-SMOKE",
            "mode": "manga",
            "page_count": 1,
            "title_changed_at": now_ms,
            "mode_changed_at": now_ms,
            "folder_changed_at": now_ms,
        },
        timeout=30,
    )
    assert created.status_code == 200, created.text
    cloud_id = created.json()["book_id"]

    try:
        downloaded = requests.get(f"{base}/v1/cloud/books/{cloud_id}/download", headers=headers, timeout=30)
        cover = requests.get(f"{base}/v1/cloud/books/{cloud_id}/cover", headers=headers, timeout=30)
        long_folder = requests.post(
            f"{base}/v1/cloud/folders",
            headers=headers,
            json={"name": "x" * 81},
            timeout=30,
        )
        assert downloaded.status_code == 200 and downloaded.content == book_zip
        assert cover.status_code == 200
        assert long_folder.status_code == 400
        stale = requests.post(
            f"{base}/v1/sync",
            headers=headers,
            json={"books": [{
                "id": cloud_id, "title": "MIT-SMOKE-STALE", "mode": "normal", "page_count": 1,
                "order_dir": "ltr", "hash": book_hash, "title_changed_at": now_ms - 1000,
                "mode_changed_at": now_ms - 1000,
            }], "progress": [], "folder_moves": [], "deletes": [], "cancels": [],
            "cloud_deletes": [], "book_ids": [cloud_id]},
            timeout=30,
        )
        assert stale.status_code == 200, stale.text
        stale_book = next(x for x in stale.json()["cloud_books"] if x["hash"] == book_hash)
        assert stale_book["title"] == "MIT-SMOKE-OK" and stale_book["mode"] == "manga"

        newer_title = requests.post(
            f"{base}/v1/sync",
            headers=headers,
            json={"books": [{
                "id": cloud_id, "title": "MIT-SMOKE-NEW-TITLE", "mode": "normal", "page_count": 1,
                "order_dir": "ltr", "hash": book_hash, "title_changed_at": now_ms + 1000,
                "mode_changed_at": now_ms - 1000,
            }], "progress": [], "folder_moves": [], "deletes": [], "cancels": [],
            "cloud_deletes": [], "book_ids": [cloud_id]},
            timeout=30,
        )
        assert newer_title.status_code == 200, newer_title.text
        split_book = next(x for x in newer_title.json()["cloud_books"] if x["hash"] == book_hash)
        assert split_book["title"] == "MIT-SMOKE-NEW-TITLE" and split_book["mode"] == "manga"

        newer_mode = requests.post(
            f"{base}/v1/sync",
            headers=headers,
            json={"books": [{
                "id": cloud_id, "title": "MIT-SMOKE-OLD", "mode": "normal", "page_count": 1,
                "order_dir": "ltr", "hash": book_hash, "title_changed_at": now_ms - 1000,
                "mode_changed_at": now_ms + 2000,
            }], "progress": [], "folder_moves": [], "deletes": [], "cancels": [],
            "cloud_deletes": [], "book_ids": [cloud_id]},
            timeout=30,
        )
        assert newer_mode.status_code == 200, newer_mode.text
        mode_book = next(x for x in newer_mode.json()["cloud_books"] if x["hash"] == book_hash)
        assert mode_book["title"] == "MIT-SMOKE-NEW-TITLE" and mode_book["mode"] == "normal"

        stale_direct = requests.post(
            f"{base}/v1/books",
            headers=headers,
            json={"id": cloud_id, "title": "MIT-SMOKE-STALE", "mode": "manga", "page_count": 1,
                  "order_dir": "rtl", "hash": book_hash, "title_changed_at": now_ms - 1000,
                  "mode_changed_at": now_ms - 1000},
            timeout=30,
        )
        assert stale_direct.status_code == 200, stale_direct.text
        direct_list = requests.get(f"{base}/v1/cloud/books", headers=headers, timeout=30)
        assert direct_list.status_code == 200, direct_list.text
        direct_book = next(x for x in direct_list.json()["books"] if x["hash"] == book_hash)
        assert direct_book["title"] == "MIT-SMOKE-NEW-TITLE" and direct_book["mode"] == "normal", direct_book

        moved_at = int(time.time() * 1000)
        synced = requests.post(
            f"{base}/v1/sync",
            headers=headers,
            json={
                "books": [],
                "progress": [],
                "folder_moves": [{
                    "book_id": book_hash,
                    "folder": "MIT-SMOKE-MOVED",
                    "changed_at": moved_at,
                }],
                "deletes": [],
                "cancels": [],
                "cloud_deletes": [],
                "book_ids": [cloud_id],
            },
            timeout=30,
        )
        assert synced.status_code == 200, synced.text
        remote_folders = {
            item["hash"]: (item.get("folder"), item.get("folder_changed_at"))
            for item in synced.json().get("book_folders", [])
        }
        assert remote_folders.get(book_hash, (None,))[0] == "MIT-SMOKE-MOVED"
    finally:
        cleanup = requests.delete(f"{base}/v1/cloud/books/{cloud_id}", headers=headers, timeout=30)
        assert cleanup.status_code == 200, cleanup.text
        folders = requests.get(f"{base}/v1/cloud/folders", headers=headers, timeout=30)
        assert folders.status_code == 200, folders.text
        for folder in folders.json().get("folders", []):
            if folder["name"] in {"MIT-SMOKE", "MIT-SMOKE-MOVED"}:
                deleted = requests.delete(
                    f"{base}/v1/cloud/folders/{folder['id']}", headers=headers, timeout=30)
                assert deleted.status_code == 200, deleted.text

    print("cloud upload smoke: all checks passed")


if __name__ == "__main__":
    try:
        main()
    except AssertionError as exc:
        print(f"cloud upload smoke failed: {exc}", file=sys.stderr)
        raise
