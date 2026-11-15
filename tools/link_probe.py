"""
PalGate device-linking probe (Phase 0 spike for GateDelay).

Answers the open questions behind "the QR is on the same phone" risk:
  1. timeout : How long does the server hold GET /un/secondary/init/{uuid} open with no scan?
  2. resume  : If our waiting request dies (as when the app is backgrounded on iOS),
               does re-requesting the SAME uuid after the scan still return the token?
  3. link    : Plain linking (QR shown on this PC = "second screen" flow). Prints credentials
               you can later import into GateDelay manually.

Usage (Windows, from the project folder):
    pip install requests qrcode pillow
    python tools/link_probe.py timeout
    python tools/link_probe.py resume
    python tools/link_probe.py link

Notes:
  * Each successful scan uses one of your account's ~2 Linked Device slots. Remove test links in
    the PalGate app (menu -> Device Linking) afterwards if needed.
  * The uuid is effectively a secret until linking completes: whoever polls it receives your
    token. That is why the QR is rendered locally and never sent to an online QR service.
"""

import json
import os
import sys
import tempfile
import threading
import time
import uuid

import qrcode
import requests

BASE_URL = "https://api1.pal-es.com/v1/bt/"
HEADERS = {"User-Agent": "okhttp/4.9.3"}


def show_qr(link_id: str) -> None:
    payload = f'{{"id": "{link_id}"}}'
    img = qrcode.make(payload, error_correction=qrcode.constants.ERROR_CORRECT_L, box_size=12, border=4)
    path = os.path.join(tempfile.gettempdir(), f"palgate_link_{link_id[:8]}.png")
    img.save(path)
    print(f"QR saved to {path} - opening it. Scan with PalGate app: menu -> Device Linking -> Link a device")
    if sys.platform == "win32":
        os.startfile(path)  # noqa: S606 - local image viewer


def init_request(link_id: str, timeout: float | None):
    return requests.get(f"{BASE_URL}un/secondary/init/{link_id}", headers=HEADERS, timeout=timeout)


def describe(resp: requests.Response) -> str:
    try:
        data = resp.json()
    except ValueError:
        return f"HTTP {resp.status_code}, non-JSON body: {resp.text[:200]!r}"
    user = data.get("user") or {}
    if "token" in user:
        user = {**user, "token": user["token"][:4] + "..." + user["token"][-4:]}
    return f"HTTP {resp.status_code}, keys={sorted(data)}, status={data.get('status')}, err={data.get('err')}, user={user}, secondary={data.get('secondary')}"


def cmd_timeout() -> None:
    link_id = str(uuid.uuid4())
    print("Do NOT scan anything. Measuring how long the server holds the request...")
    t0 = time.monotonic()
    try:
        resp = init_request(link_id, timeout=15 * 60)
        print(f"Server answered after {time.monotonic() - t0:.1f}s: {describe(resp)}")
    except requests.RequestException as exc:
        print(f"Connection ended after {time.monotonic() - t0:.1f}s: {exc!r}")


def cmd_resume() -> None:
    link_id = str(uuid.uuid4())
    show_qr(link_id)

    print("\nStep 1: opening a waiting request and dropping it after 5 s (simulates app suspension)...")
    try:
        resp = init_request(link_id, timeout=5)
        print(f"Unexpected early answer: {describe(resp)}")
    except requests.RequestException:
        print("Request dropped, as intended.")

    input("\nStep 2: NOW scan the QR with the PalGate app. Press Enter here once the app says it is linked...")

    print("Step 3: re-requesting the same uuid...")
    t0 = time.monotonic()
    try:
        resp = init_request(link_id, timeout=60)
        print(f"Answer after {time.monotonic() - t0:.1f}s: {describe(resp)}")
        print("\nRESULT:", "RESUME WORKS - token returned" if (resp.ok and (resp.json().get('user') or {}).get('token'))
              else "resume did NOT return a token")
    except requests.RequestException as exc:
        print(f"No answer within 60 s ({exc!r}).\nRESULT: resume does NOT work (token was delivered to the dropped request or lost)")
        print("Remove this test link in the PalGate app (Device Linking list).")


def cmd_link() -> None:
    link_id = str(uuid.uuid4())
    show_qr(link_id)
    print("Waiting for scan...")
    # Keep re-polling in case the server times the request out before you scan.
    while True:
        try:
            resp = init_request(link_id, timeout=10 * 60)
        except requests.RequestException as exc:
            print(f"(request ended: {exc!r}; re-polling)")
            continue
        data = resp.json()
        if resp.ok and (data.get("user") or {}).get("token"):
            creds = {"phone": data["user"]["id"], "token": data["user"]["token"], "tokenType": data["secondary"]}
            print("\nLinked. Credentials for GateDelay manual import (keep them private!):")
            print(json.dumps(creds, indent=2))
            return
        print(f"Not linked yet: {describe(resp)}; re-polling")
        time.sleep(1)


if __name__ == "__main__":
    commands = {"timeout": cmd_timeout, "resume": cmd_resume, "link": cmd_link}
    if len(sys.argv) != 2 or sys.argv[1] not in commands:
        print(__doc__)
        sys.exit(1)
    commands[sys.argv[1]]()
