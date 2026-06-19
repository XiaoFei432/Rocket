#!/usr/bin/env python3
"""Replay a simple CSV invocation trace against OpenWhisk."""

from __future__ import annotations

import argparse
import base64
import csv
import json
import subprocess
import time
import urllib.error
import urllib.request
from pathlib import Path


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--trace", required=True, help="CSV with columns: timestamp_ms,action,payload")
    parser.add_argument("--namespace", default="", help="optional namespace prefix")
    parser.add_argument("--wsk", default="wsk", help="path to wsk CLI")
    parser.add_argument("--apihost", help="OpenWhisk API host for REST replay, for example http://localhost:3233")
    parser.add_argument("--auth", help="OpenWhisk auth key for REST replay, formatted as uuid:secret")
    parser.add_argument("--blocking", action="store_true")
    parser.add_argument("--speedup", type=float, default=1.0)
    args = parser.parse_args()
    if bool(args.apihost) != bool(args.auth):
        parser.error("--apihost and --auth must be used together")

    rows = []
    with Path(args.trace).open(newline="", encoding="utf-8-sig") as handle:
        reader = csv.DictReader(handle)
        for row in reader:
            rows.append(row)

    rows.sort(key=lambda r: int(r["timestamp_ms"]))
    if not rows:
        return

    base_trace = int(rows[0]["timestamp_ms"])
    base_wall = time.time()
    for row in rows:
        target = base_wall + (int(row["timestamp_ms"]) - base_trace) / 1000.0 / max(args.speedup, 1e-9)
        delay = target - time.time()
        if delay > 0:
            time.sleep(delay)
        action = f"{args.namespace}/{row['action']}" if args.namespace else row["action"]
        payload = row.get("payload") or "{}"
        try:
            json.loads(payload)
        except json.JSONDecodeError:
            payload = json.dumps({"value": payload})
        if args.apihost:
            invoke_rest(args.apihost, args.auth, action, payload, args.blocking)
        else:
            cmd = [args.wsk, "action", "invoke", action, "-p", "payload", payload]
            if args.blocking:
                cmd.append("--blocking")
            subprocess.Popen(cmd)


def invoke_rest(apihost: str, auth: str, action: str, payload: str, blocking: bool) -> None:
    namespace, action_name = split_action(action)
    body = json.dumps({"payload": json.loads(payload)}).encode("utf-8")
    url = (
        f"{apihost.rstrip('/')}/api/v1/namespaces/{namespace}/actions/{action_name}"
        f"?blocking={str(blocking).lower()}&result=true"
    )
    token = base64.b64encode(auth.encode("ascii")).decode("ascii")
    request = urllib.request.Request(
        url,
        data=body,
        headers={"Authorization": f"Basic {token}", "Content-Type": "application/json", "Accept": "application/json"},
        method="POST",
    )
    try:
        with urllib.request.urlopen(request, timeout=120) as response:
            print(response.read().decode("utf-8"))
    except urllib.error.HTTPError as error:
        detail = error.read().decode("utf-8", errors="replace")
        raise RuntimeError(f"OpenWhisk invoke failed for {action}: HTTP {error.code} {detail}") from error


def split_action(action: str) -> tuple[str, str]:
    parts = [part for part in action.split("/") if part]
    if len(parts) == 1:
        return "guest", parts[0]
    if len(parts) == 2:
        return parts[0], parts[1]
    raise ValueError(f"unsupported action name: {action}")


if __name__ == "__main__":
    main()
