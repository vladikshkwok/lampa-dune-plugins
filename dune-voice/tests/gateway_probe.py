"""Read-only negative tests against an installed TV gateway (no commands run).

For lab use, forward localhost:18766 to the Dune's port 18766 with SSH/ADB.
Never print a pairing code, bearer token or user transcript.
"""
import http.client
import json
import socket
import unittest


class GatewayTest(unittest.TestCase):
    def call(self, path, body, headers=None, method="POST"):
        connection = http.client.HTTPConnection("127.0.0.1", 18766, timeout=6)
        try:
            connection.request(method, path, body.encode("utf-8"), headers or {"Content-Type": "application/json"})
            response = connection.getresponse()
            value = json.loads(response.read())
            self.assertFalse(value["ok"])
            return response.status
        finally:
            connection.close()

    def test_unauthenticated_command_rejected(self):
        self.assertEqual(self.call("/command", '{"id":"00000000-0000-0000","text":"домой"}'), 403)

    def test_wrong_token_rejected(self):
        self.assertEqual(self.call("/command", '{"text":"пауза"}', {"Authorization": "Bearer wrong"}), 403)

    def test_no_pairing_window(self):
        self.assertEqual(self.call("/pair", '{"code":"00000000"}'), 403)

    def test_browser_origin_rejected(self):
        self.assertEqual(self.call("/pair", '{"code":"00000000"}', {"Origin": "https://example.com"}), 403)

    def test_oversized_body_rejected(self):
        self.assertEqual(self.call("/command", "x" * 2049), 400)

    def test_unknown_path_rejected(self):
        self.assertEqual(self.call("/anything", "{}"), 404)

    def test_get_cannot_execute(self):
        self.assertEqual(self.call("/command", "{}", method="GET"), 404)

    def test_malformed_json_rejected(self):
        self.assertEqual(self.call("/command", "xx"), 400)

    def test_duplicate_authorization_rejected(self):
        with socket.create_connection(("127.0.0.1", 18766), timeout=6) as connection:
            connection.sendall(b"POST /command HTTP/1.1\r\nHost: local\r\nContent-Length: 2\r\nAuthorization: a\r\nAuthorization: b\r\n\r\n{}")
            self.assertEqual(connection.recv(100), b"")


if __name__ == "__main__":
    unittest.main()
