"""Compare notification event-log lines with Room identity rows.

The report contains package, id, tag, and postTime only.
"""

from __future__ import annotations

import argparse
import csv
import sqlite3
import sys
from pathlib import Path


def parse_events(text: str, packages: list[str]) -> list[dict]:
    wanted = [package for package in packages if package]
    rows = []
    for line in text.splitlines():
        if "notification_enqueue" in line:
            kind = "enqueue"
        elif "notification_canceled" in line:
            kind = "cancel"
        else:
            continue
        package = next((item for item in wanted if item in line), None)
        if package is None and wanted:
            continue
        if package is None:
            continue
        notification_id, tag = _id_and_tag(line, package)
        rows.append(
            {
                "kind": kind,
                "package": package,
                "id": notification_id,
                "tag": tag,
            }
        )
    return rows


def _id_and_tag(line: str, package: str) -> tuple[str | None, str | None]:
    index = line.find(package)
    rest = line[index + len(package) :]
    if not rest.startswith(","):
        return None, None
    parts = rest[1:].split(",", 2)
    if len(parts) < 2:
        return None, None
    notification_id = parts[0].strip()
    tag = parts[1].strip().rstrip("]")
    if not notification_id.lstrip("-").isdigit():
        return None, None
    if tag in {"", "null", "NULL"}:
        tag = None
    return notification_id, tag


def room_matches(event: dict, row: dict) -> bool:
    if event["package"] != row["packageName"]:
        return False
    key = row["notificationKey"]
    tag = event["tag"]
    notification_id = event["id"]
    if tag and row.get("notificationTag") == tag:
        return True
    if notification_id is None:
        return False
    token = f"|{notification_id}|{tag}" if tag else f"|{notification_id}|null"
    return token in key


def compare(events: list[dict], room_rows: list[dict]) -> str:
    missing = []
    matched = 0
    cancels_without_room = 0
    for event in events:
        hit = any(room_matches(event, row) for row in room_rows)
        if event["kind"] == "enqueue" and hit:
            matched += 1
        elif event["kind"] == "enqueue":
            missing.append(event)
        elif not hit:
            cancels_without_room += 1
    lines = [
        f"enqueue_matched={matched}",
        f"enqueue_missing_from_room={len(missing)}",
        f"cancel_without_room={cancels_without_room}",
        f"room_rows={len(room_rows)}",
        "missing_package_id_tag:",
    ]
    for event in missing:
        lines.append(f"{event['package']},{event['id'] or ''},{event['tag'] or ''}")
    lines.append(
        "History is a manual second check. This file does not prove a locked-screen soak."
    )
    return "\n".join(lines) + "\n"


def export_room(database: Path, csv_path: Path) -> None:
    connection = sqlite3.connect(f"file:{database}?mode=ro", uri=True)
    try:
        cursor = connection.execute(
            """
            SELECT packageName, notificationKey, notificationTag, postTimeEpochMillis
            FROM captured_notifications
            ORDER BY postTimeEpochMillis, notificationKey
            """
        )
        with csv_path.open("w", newline="", encoding="utf-8") as handle:
            writer = csv.writer(handle)
            writer.writerow(
                ["packageName", "notificationKey", "notificationTag", "postTimeEpochMillis"]
            )
            for package_name, key, tag, post_time in cursor:
                writer.writerow([package_name, key, tag or "", post_time])
    finally:
        connection.close()


def read_room_csv(path: Path) -> list[dict]:
    with path.open(newline="", encoding="utf-8") as handle:
        return list(csv.DictReader(handle))


def main(argv: list[str]) -> int:
    parser = argparse.ArgumentParser()
    sub = parser.add_subparsers(dest="command", required=True)
    export = sub.add_parser("export")
    export.add_argument("--database", type=Path, required=True)
    export.add_argument("--csv", type=Path, required=True)
    compare_cmd = sub.add_parser("compare")
    compare_cmd.add_argument("--events", type=Path, required=True)
    compare_cmd.add_argument("--room", type=Path, required=True)
    compare_cmd.add_argument("--report", type=Path, required=True)
    compare_cmd.add_argument("--packages", nargs="*", default=["us.bnn.newsapp"])
    args = parser.parse_args(argv)
    if args.command == "export":
        export_room(args.database, args.csv)
        return 0
    events = parse_events(args.events.read_text(encoding="utf-8", errors="replace"), args.packages)
    report = compare(events, read_room_csv(args.room))
    args.report.write_text(report, encoding="utf-8")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
