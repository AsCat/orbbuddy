import unittest

from orb_adb_connect import OrbClient, OrbError, select_device


class DeviceSelectionTest(unittest.TestCase):
    def test_multiple_phones_require_explicit_target(self):
        phones = [
            {"id": "xiaomi", "online": True, "paused": False},
            {"id": "samsung", "online": True, "paused": False},
        ]
        with self.assertRaisesRegex(OrbError, "specify --device"):
            select_device(phones, None)
        self.assertEqual("samsung", select_device(phones, "samsung"))

    def test_paused_and_offline_phones_cannot_be_selected(self):
        phones = [
            {"id": "xiaomi", "online": True, "paused": True},
            {"id": "samsung", "online": False, "paused": False},
        ]
        with self.assertRaisesRegex(OrbError, "paused"):
            select_device(phones, "xiaomi")
        with self.assertRaisesRegex(OrbError, "offline"):
            select_device(phones, "samsung")

    def test_missing_accessibility_tree_is_a_clear_error(self):
        client = OrbClient("https://example.com/mcp", "test", "xiaomi")
        client.call = lambda *_: [{"type": "text", "text": '{"tree":null}'}]
        with self.assertRaisesRegex(OrbError, "screen hierarchy is unavailable"):
            client.screen()


if __name__ == "__main__":
    unittest.main()
