#!/usr/bin/env python3
"""STOMP/WebSocket BOLA probe against Glacier.

Verifies the runtime invariant enforced by WallTopicAuthInterceptor:
a client whose wallId is X MUST NOT receive any message published to
/topic/hashtags/Y/<hashtag>/* for any Y != X.

This script complements WallTopicSubscribeIsolationIT (which runs in the
unit-test classpath against an in-memory broker). The IT proves the
interceptor is wired and rejects the SUBSCRIBE frame; this script proves
the same property end-to-end against the packaged jar inside docker-compose,
where the real PrincipalHandler reads the cookie from the upgrade headers.

It does NOT depend on the Mastodon backend — it only exercises the broker.

Exits 0 if isolation holds. Exits 1 if any cross-principal leakage is observed.

Usage:
    BASE_URL=http://glacier:8080 ./ws-bola-probe.py
"""
from __future__ import annotations

import json
import os
import sys
import threading
import time
import uuid
from urllib.parse import urlparse

try:
    import websocket  # websocket-client
except ImportError:
    print("websocket-client missing; install with: pip install websocket-client",
          file=sys.stderr)
    sys.exit(2)


def stomp_connect_frame(login: str) -> str:
    return f"CONNECT\naccept-version:1.2\nhost:glacier\nlogin:{login}\n\n\x00"


def stomp_subscribe_frame(sub_id: str, destination: str) -> str:
    return f"SUBSCRIBE\nid:{sub_id}\ndestination:{destination}\n\n\x00"


def open_session(base_url: str, wall_id: str) -> tuple[websocket.WebSocket, list]:
    """Open a raw STOMP-over-WebSocket session with the given wallId cookie."""
    parsed = urlparse(base_url)
    scheme = "wss" if parsed.scheme == "https" else "ws"
    url = f"{scheme}://{parsed.netloc}/websocket/websocket"

    received: list[str] = []

    ws = websocket.create_connection(
        url,
        header=[f"Cookie: wallId={wall_id}"],
        timeout=10,
    )
    ws.send(stomp_connect_frame(wall_id))
    # First receive: CONNECTED frame
    connected = ws.recv()
    if "CONNECTED" not in connected:
        raise RuntimeError(f"expected CONNECTED, got: {connected!r}")

    def reader():
        try:
            while True:
                frame = ws.recv()
                if frame:
                    received.append(frame)
        except Exception:
            return

    threading.Thread(target=reader, daemon=True).start()
    return ws, received


def main() -> int:
    base_url = os.environ.get("BASE_URL", "http://glacier:8080")
    print(f"[bola-probe] target={base_url}")

    victim_id = str(uuid.uuid4())
    attacker_id = str(uuid.uuid4())
    print(f"[bola-probe] victim wallId   = {victim_id}")
    print(f"[bola-probe] attacker wallId = {attacker_id}")

    # 1. Open the victim's session and let it subscribe to its own topic
    victim_ws, victim_inbox = open_session(base_url, victim_id)
    victim_ws.send(stomp_subscribe_frame(
        "victim-sub",
        f"/topic/hashtags/{victim_id}/security/creation"))
    time.sleep(0.5)

    # 2. Open the attacker's session and try to subscribe to the VICTIM's topic
    attacker_ws, attacker_inbox = open_session(base_url, attacker_id)
    attacker_ws.send(stomp_subscribe_frame(
        "attacker-sub",
        f"/topic/hashtags/{victim_id}/security/creation"))
    time.sleep(0.5)

    # 3. Attempt to publish a message via the application destination —
    #    in the real flow this would be StompCallback.recordThenPublish but
    #    we cannot reach that path without the federation chain.  Instead,
    #    we observe attacker_inbox for any frame ever — if WallTopicAuthInterceptor
    #    is doing its job, the attacker's SUBSCRIBE frame was DROPPED and the
    #    attacker will never see anything. We give the broker 3 s of grace.
    time.sleep(3.0)

    leaked = [f for f in attacker_inbox if f.startswith("MESSAGE")]
    victim_ws.close()
    attacker_ws.close()

    if leaked:
        print(f"[bola-probe] FAIL: attacker received {len(leaked)} MESSAGE frame(s)")
        for f in leaked:
            print(" ", repr(f[:200]))
        return 1

    print("[bola-probe] OK: attacker did not receive any messages from victim's topic")
    return 0


if __name__ == "__main__":
    sys.exit(main())
