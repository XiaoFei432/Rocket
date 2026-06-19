#!/usr/bin/env python3
"""Generate wsk annotation arguments for Rocket inference actions."""

from __future__ import annotations

import argparse
import json
from pathlib import Path


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--config", required=True, help="JSON file with Rocket action metadata")
    parser.add_argument("--action", required=True, help="action key inside the config")
    parser.add_argument("--print-json", action="store_true", help="print annotations as JSON instead of wsk args")
    args = parser.parse_args()

    data = json.loads(Path(args.config).read_text(encoding="utf-8"))
    action = data["actions"][args.action]
    annotations = {
        "rocket.enabled": True,
        "rocket.library": action["library"],
        "rocket.baseModel": action["base_model"],
        "rocket.frontModel": action.get("front_model", action["base_model"]),
        "rocket.backModel": action.get("back_model", args.action),
        "rocket.coldStartMs": int(action.get("cold_start_ms", 2000)),
        "rocket.libraryImportMs": int(action.get("library_import_ms", 800)),
        "rocket.frontLoadMs": int(action.get("front_load_ms", 600)),
        "rocket.backLoadMs": int(action.get("back_load_ms", 900)),
        "rocket.libraryMemoryMb": int(action.get("library_memory_mb", 300)),
        "rocket.frontMemoryMb": int(action.get("front_memory_mb", 300)),
        "rocket.backMemoryMb": int(action.get("back_memory_mb", 512)),
    }
    if "group" in action:
        annotations["rocket.group"] = action["group"]

    if args.print_json:
        print(json.dumps(annotations, indent=2, ensure_ascii=False))
    else:
        print(" ".join(f"--annotation {json.dumps(k)} {json.dumps(v)}" for k, v in annotations.items()))


if __name__ == "__main__":
    main()

