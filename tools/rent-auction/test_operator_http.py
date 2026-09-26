"""Local HTTP boundary tests; protocol validity is covered by the Scala suites."""
from contextlib import redirect_stdout
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import io
import json
from pathlib import Path
import tempfile
import threading
import unittest
from unittest.mock import patch

import rent_auction as ra


class OperatorHttpTests(unittest.TestCase):
    def setUp(self):
        self.calls = []
        self.box_id = "ab" * 32
        self.live = {"boxId": self.box_id, "value": 9007199254740993,
                     "ergoTree": "00", "creationHeight": 1, "assets": [],
                     "additionalRegisters": {}}
        self.signed = {"id": "cd" * 32, "inputs": [{"boxId": self.box_id}],
                       "outputs": []}
        owner = self

        class Handler(BaseHTTPRequestHandler):
            def log_message(self, *args):
                pass

            def do_GET(self):
                self.respond(None)

            def do_POST(self):
                self.respond(json.loads(self.rfile.read(int(self.headers["Content-Length"]))))

            def respond(self, data):
                owner.calls.append((self.path, data, self.headers.get("api_key")))
                if self.path == "/info":
                    value = {"fullHeight": 100, "parameters": {
                        "storageFeeFactor": 1250000, "minValuePerByte": 360}}
                elif self.path.startswith("/utxo/"):
                    value = owner.live
                elif self.path == "/wallet/transaction/sign":
                    value = owner.signed
                else:
                    value = {"accepted": True}
                encoded = json.dumps(value).encode()
                self.send_response(200)
                self.send_header("Content-Type", "application/json")
                self.send_header("Content-Length", str(len(encoded)))
                self.end_headers()
                self.wfile.write(encoded)

        self.server = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True)
        self.thread.start()
        self.url = f"http://127.0.0.1:{self.server.server_port}"
        self.node = ra.Node(self.url, "test-api-key")

    def tearDown(self):
        self.server.shutdown()
        self.thread.join()
        self.server.server_close()

    def plan(self):
        return {"height": 101, "inputBoxes": [self.live],
                "transactionId": self.signed["id"],
                "signingRequest": {"tx": {"inputs": [{"boxId": self.box_id,
                    "extension": {"127": "0400"}}]}, "inputsRaw": ["00"],
                    "dataInputsRaw": []}}

    def test_live_parameters_and_boxes_replace_stale_offline_values(self):
        request = ra.make_request(self.node, "collect", {
            "height": 1, "parameters": {}, "sources": [dict(self.live, value=1)]})
        self.assertEqual(request["height"], 101)
        self.assertEqual(request["parameters"]["storageFeeFactor"], 1250000)
        self.assertEqual(request["sources"][0]["value"], 9007199254740993)

    def test_wallet_receives_extensions_and_raw_inputs_unchanged(self):
        plan = self.plan()
        self.assertEqual(ra.sign_plan(self.node, plan), self.signed)
        self.assertEqual(self.calls[-1], (
            "/wallet/transaction/sign", plan["signingRequest"], "test-api-key"))

    def test_lithos_header_key_and_transaction_array_are_preserved(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "signed.json"
            ra.write_json(path, self.signed)
            with redirect_stdout(io.StringIO()), patch.dict("os.environ", {}, clear=True):
                ra.main(["--node", self.url, "candidate", str(path),
                         "--miner-pk", "lender-key"])
        self.assertEqual(self.calls[-1][:2], ("/mining/candidateWithTxsAndPk",
            {"txs": [self.signed], "pk": "lender-key"}))

    def test_queue_rejects_stale_plan_and_publishes_atomic_height_file(self):
        with tempfile.TemporaryDirectory() as directory:
            plan_path, signed_path = Path(directory) / "plan.json", Path(directory) / "tx.json"
            plan = self.plan()
            ra.write_json(plan_path, dict(plan, height=100))
            ra.write_json(signed_path, self.signed)
            argv = ["--node", self.url, "lithos-queue", str(plan_path),
                    str(signed_path), directory]
            with self.assertRaises(ValueError):
                ra.main(argv)
            self.assertFalse((Path(directory) / "101.json").exists())
            ra.write_json(plan_path, plan)
            with redirect_stdout(io.StringIO()):
                ra.main(argv)
            self.assertEqual(ra.read_json(Path(directory) / "101.json"),
                             {"height": 101, "transaction": self.signed})

    def test_broadcast_checks_before_submission(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "tx.json"
            ra.write_json(path, self.signed)
            with redirect_stdout(io.StringIO()):
                ra.main(["--node", self.url, "broadcast", str(path)])
        self.assertEqual([c[0] for c in self.calls], ["/transactions/check", "/transactions"])


if __name__ == "__main__":
    unittest.main()
