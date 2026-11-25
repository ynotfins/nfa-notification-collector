import tempfile
import unittest
from pathlib import Path

from compare_notification_oracle import compare, parse_events, room_matches


class OracleCompareTest(unittest.TestCase):
    def test_parse_keeps_package_id_and_tag_only(self):
        text = "\n".join(
            [
                "10-19 12:00:00.000 I notification_enqueue: [1,2,us.bnn.newsapp,55,incident-tag,0,secret body]",
                "10-19 12:00:01.000 I notification_canceled: [1,2,us.bnn.newsapp,9,null,0]",
                "10-19 12:00:02.000 I notification_enqueue: [1,2,com.other.app,1,tag,0]",
            ]
        )
        rows = parse_events(text, ["us.bnn.newsapp"])
        self.assertEqual(
            [
                {"kind": "enqueue", "package": "us.bnn.newsapp", "id": "55", "tag": "incident-tag"},
                {"kind": "cancel", "package": "us.bnn.newsapp", "id": "9", "tag": None},
            ],
            rows,
        )

    def test_missing_enqueue_is_reported_without_body_text(self):
        events = parse_events(
            "I notification_enqueue: [1,2,us.bnn.newsapp,55,incident-tag,0,do not copy]",
            ["us.bnn.newsapp"],
        )
        room = [
            {
                "packageName": "us.bnn.newsapp",
                "notificationKey": "0|us.bnn.newsapp|7|other",
                "notificationTag": "other",
                "postTimeEpochMillis": "1000",
            }
        ]
        report = compare(events, room)
        self.assertIn("enqueue_missing_from_room=1", report)
        self.assertIn("us.bnn.newsapp,55,incident-tag", report)
        self.assertNotIn("do not copy", report)
        self.assertTrue(
            room_matches(
                {"kind": "enqueue", "package": "us.bnn.newsapp", "id": "7", "tag": "other"},
                room[0],
            )
        )

    def test_export_writes_identity_columns(self):
        import sqlite3

        from compare_notification_oracle import export_room

        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            database = root / "collector.db"
            connection = sqlite3.connect(database)
            connection.execute(
                """
                CREATE TABLE captured_notifications (
                  packageName TEXT, notificationKey TEXT, notificationTag TEXT, postTimeEpochMillis INTEGER,
                  rawText TEXT
                )
                """
            )
            connection.execute(
                "INSERT INTO captured_notifications VALUES (?, ?, ?, ?, ?)",
                ("us.bnn.newsapp", "0|us.bnn.newsapp|5|tag", "tag", 1000, "private alert"),
            )
            connection.commit()
            connection.close()
            csv_path = root / "room-identities.csv"
            export_room(database, csv_path)
            text = csv_path.read_text(encoding="utf-8")
            self.assertIn("packageName,notificationKey,notificationTag,postTimeEpochMillis", text)
            self.assertIn("us.bnn.newsapp", text)
            self.assertNotIn("private alert", text)


if __name__ == "__main__":
    unittest.main()
