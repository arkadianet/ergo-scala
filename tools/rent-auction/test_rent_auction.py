import copy
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

import rent_auction as ra


def box(box_id, value=10, tree="00", created=1):
    return {"boxId": box_id, "value": value, "ergoTree": tree,
            "creationHeight": created, "assets": [], "additionalRegisters": {}}


def block(height, block_id, parent, spent=(), outputs=()):
    return {"header": {"height": height, "id": block_id, "parentId": parent},
            "blockTransactions": {"transactions": [
                {"inputs": [{"boxId": x} for x in spent], "outputs": list(outputs)}]}}


class FakeNode:
    def __init__(self, blocks, genesis=()):
        self.blocks = blocks
        self.genesis = list(genesis)

    def canonical_id(self, height):
        return self.blocks[height - 1]["header"]["id"]

    def request(self, path):
        if path == "/utxo/genesis":
            return self.genesis
        if path == "/info":
            return {"fullHeight": len(self.blocks)}
        return copy.deepcopy(next(b for b in self.blocks
                                  if b["header"]["id"] == path.split("/")[-1]))


class IndexTests(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.path = str(Path(self.directory.name) / "index.db")
        self.index = ra.Index(self.path)

    def tearDown(self):
        self.index.close()
        self.directory.cleanup()

    def test_reorg_restores_spent_boxes_and_removes_orphan_outputs(self):
        first = block(1, "a", "genesis", outputs=[box("source")])
        second = block(2, "b", "a", ["source"], [box("auction", tree="auction")])
        node = FakeNode([first, second])
        self.index.sync(node)
        self.assertEqual([b["boxId"] for b in self.index.boxes()], ["auction"])
        node.blocks[1] = block(2, "c", "a", outputs=[box("other")])
        self.index.sync(node)
        self.assertEqual([b["boxId"] for b in self.index.boxes()], ["other", "source"])
        self.assertEqual(self.index.tip(), (2, "c"))

    def test_same_block_auction_successors_undo_to_original_state(self):
        first = block(1, "a", "genesis", outputs=[box("source")])
        self.index.apply(first["header"], first["blockTransactions"]["transactions"])
        txs = [{"inputs": [{"boxId": "source"}], "outputs": [box("first-bid")]},
               {"inputs": [{"boxId": "first-bid"}], "outputs": [box("second-bid")]}]
        self.index.apply({"height": 2, "id": "b", "parentId": "a"}, txs)
        self.index.rollback()
        self.assertEqual([b["boxId"] for b in self.index.boxes()], ["source"])

    def test_failed_block_application_is_atomic(self):
        malformed = block(1, "a", "genesis", outputs=[box("valid"), {"boxId": "bad"}])
        with self.assertRaises(KeyError):
            self.index.apply(malformed["header"], malformed["blockTransactions"]["transactions"])
        self.assertIsNone(self.index.tip())
        self.assertEqual(self.index.boxes(), [])

    def test_resume_reopen_and_canonical_chain_shortening(self):
        node = FakeNode([block(1, "a", "genesis", outputs=[box("source")]),
                         block(2, "b", "a", ["source"], [box("next")])])
        self.index.sync(node)
        self.index.close()
        self.index = ra.Index(self.path)
        self.assertEqual(self.index.tip(), (2, "b"))
        node.blocks.pop()
        self.index.sync(node)
        self.assertEqual(self.index.tip(), (1, "a"))
        self.assertEqual(self.index.boxes()[0]["boxId"], "source")

    def test_wrong_parent_and_partial_start_are_rejected(self):
        with self.assertRaises(ValueError):
            self.index.apply({"height": 10, "id": "x", "parentId": "y"}, [])
        self.index.apply({"height": 1, "id": "a", "parentId": "genesis"}, [])
        with self.assertRaises(ValueError):
            self.index.apply({"height": 2, "id": "b", "parentId": "wrong"}, [])

    def test_sync_rechecks_canonical_id_before_committing(self):
        node = FakeNode([block(1, "a", "genesis", outputs=[box("source")])])
        with patch.object(node, "canonical_id", side_effect=["a", "b"]):
            with self.assertRaises(RuntimeError):
                self.index.sync(node)
        self.assertIsNone(self.index.tip())

    def test_genesis_boxes_survive_reorg_to_empty_chain(self):
        initial = box("genesis-box", created=0)
        node = FakeNode([block(1, "a", "genesis", ["genesis-box"], [box("next")])],
                        [initial])
        self.index.sync(node)
        self.assertEqual(self.index.boxes(), [box("next")])
        node.blocks = []
        self.index.sync(node)
        self.assertEqual(self.index.boxes(), [initial])

    def test_switching_networks_does_not_destroy_existing_index(self):
        first = FakeNode([block(1, "a", "genesis", outputs=[box("next")])], [box("g")])
        self.index.sync(first)
        with self.assertRaises(ValueError):
            self.index.sync(FakeNode([], [box("different-genesis")]))
        self.assertEqual(self.index.tip(), (1, "a"))


class OperatorTests(unittest.TestCase):
    def test_api_key_requires_tls_for_remote_node(self):
        with self.assertRaises(ValueError):
            ra.Node("http://remote.example", "secret")
        ra.Node("http://127.0.0.1:9053", "secret")
        ra.Node("https://remote.example", "secret")

    def test_wallet_cannot_change_transaction_id(self):
        node = ra.Node("http://127.0.0.1")
        plan = {"inputBoxes": [box("a")], "transactionId": "expected", "signingRequest": {}}
        with patch.object(node, "box", return_value=box("a")), \
                patch.object(node, "request", return_value={"id": "unexpected"}):
            with self.assertRaises(ValueError):
                ra.sign_plan(node, plan)

    def test_changed_input_stops_before_wallet_signing(self):
        node = ra.Node("http://127.0.0.1")
        plan = {"inputBoxes": [box("a")], "transactionId": "expected", "signingRequest": {}}
        with patch.object(node, "box", return_value=box("a", value=99)), \
                patch.object(node, "request") as request:
            with self.assertRaises(ValueError):
                ra.sign_plan(node, plan)
            request.assert_not_called()

    def test_atomic_json_write_preserves_large_token_amounts(self):
        with tempfile.TemporaryDirectory() as directory:
            target = Path(directory) / "plan.json"
            expected = {"amount": 9223372036854775807}
            ra.write_json(target, expected)
            self.assertEqual(ra.read_json(target), expected)
            self.assertFalse(target.with_suffix(".json.tmp").exists())


if __name__ == "__main__":
    unittest.main()
