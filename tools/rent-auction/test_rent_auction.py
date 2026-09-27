import copy
import argparse
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
        self.disabled_rules = []
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
    def test_disabled_rules_are_explicit_and_sorted(self):
        self.assertEqual(ra.disabled_rules("none"), [])
        self.assertEqual(ra.disabled_rules("124,123"), [123, 124])
        for value in ("", "123,", "123,123", "32768", "abc", "none,123"):
            with self.subTest(value=value), self.assertRaises(argparse.ArgumentTypeError):
                ra.disabled_rules(value)
        with patch("sys.stderr"), self.assertRaises(SystemExit) as error:
            ra.main(["manifest"])
        self.assertEqual(error.exception.code, 2)

    def test_api_key_requires_tls_for_remote_node(self):
        with self.assertRaises(ValueError):
            ra.Node("http://remote.example", [], "secret")
        ra.Node("http://127.0.0.1:9053", [], "secret")
        ra.Node("https://remote.example", [], "secret")

    def test_wallet_cannot_change_transaction_id(self):
        node = ra.Node("http://127.0.0.1", [])
        plan = {"schemaVersion": 2, "height": 101, "inputBoxes": [box("a")],
                "transactionId": "expected", "signingRequest": {"tx": {"inputs": []}}}
        with patch.object(node, "box", return_value=box("a")), \
                patch.object(node, "request", side_effect=[
                    {"fullHeight": 100, "parameters": {"storageFeeFactor": 1250000, "minValuePerByte": 360}},
                    {"id": "unexpected"}]):
            with self.assertRaises(ValueError):
                ra.sign_plan(node, plan)

    def test_changed_input_stops_before_wallet_signing(self):
        node = ra.Node("http://127.0.0.1", [])
        plan = {"schemaVersion": 2, "height": 101, "inputBoxes": [box("a")],
                "transactionId": "expected", "signingRequest": {"tx": {"inputs": []}}}
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



class WorkerNode:
    """HTTP-free boundary double; no protocol-validity claims."""
    def __init__(self, boxes=(), price=360):
        self.disabled_rules = []
        self.boxes = {b["boxId"]: b for b in boxes}
        self.height = 100
        self.parameters = {"storageFeeFactor": 1250000, "minValuePerByte": price,
                           "maxBlockSize": 524288, "maxBlockCost": 1000000}
        self.calls = []

    def box(self, box_id):
        if box_id not in self.boxes:
            raise ra.SpentInput(box_id)
        return copy.deepcopy(self.boxes[box_id])

    def request(self, path, data=None):
        self.calls.append((path, data))
        if path == "/info":
            return {"fullHeight": self.height, "parameters": dict(self.parameters)}
        if path == "/wallet/transaction/sign":
            return self.signed
        if path == "/transactions":
            for b in data["outputs"]:
                self.boxes[b["boxId"]] = b
        return "accepted"


class WorkerBuilder:
    def __init__(self, node):
        self.node, self.requests = node, []
        self.manifest = {"schemaVersion": 2, "auction": {"ergoTree": "auction"},
                         "deposit": {"ergoTree": "deposit"}, "reserve": {"ergoTree": "reserve"},
                         "reserveNft": "nft", "maxCloseLots": 32, "maxMergeDeposits": 10,
                         "mergeBudget": 1000000, "votingLength": 1024}
        self.after_build = lambda: None

    def run(self, command, request=None):
        if command == "manifest":
            return self.manifest
        self.requests.append(copy.deepcopy(request))
        if request["action"] == "inspect":
            return {"schemaVersion": 2, "boxes": [dict(b, deadline=99,
                auctionShape=b["ergoTree"] == "auction", depositShape=b["ergoTree"] == "deposit")
                for b in request["boxes"]]}
        members = request.get("auctions", request.get("deposits"))
        count = len(members)
        if request["action"] == "merge" and count == 1 and request["parameters"]["minValuePerByte"] == 10000:
            raise RuntimeError("Scala builder failed: Output is dust")
        inputs = ([request["reserve"]] if "reserve" in request else []) + members
        ins = [{"boxId": b["boxId"], "extension": {"0": "0400", "1": "0580897a", "2": "04d005"}}
               for b in inputs]
        out = box("successor-" + members[0]["boxId"], tree="reserve", created=request["height"])
        out["assets"] = [{"tokenId": "nft", "amount": 1}]
        signed = {"id": members[0]["boxId"] + str(request["height"]),
                  "inputs": [{"boxId": i["boxId"], "spendingProof": {
                      "proofBytes": "", "extension": i["extension"]}} for i in ins], "outputs": [out]}
        self.node.signed = signed
        plan = {"schemaVersion": 2, "height": request["height"], "inputBoxes": inputs,
                "transactionId": signed["id"], "emptyProofTransaction": signed,
                "additionalValidationCost": 1000 * count, "feePaid": 1000000 * count,
                "outputBoxes": [out], "signingRequest": {"tx": {"inputs": ins},
                    "inputsRaw": ["00"] * len(ins), "dataInputsRaw": []}}
        self.after_build()
        return plan


class BatchTests(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.index = ra.Index(":memory:")

    def tearDown(self):
        self.index.close()
        self.directory.cleanup()

    def lots(self, count, price=360):
        boxes = [box(f"{i:064x}", tree="auction") for i in range(count)]
        for b in reversed(boxes):
            self.index._put(b)
        node = WorkerNode(boxes, price)
        return node, WorkerBuilder(node)

    def deposits(self, count, price=10000):
        boxes = [box(f"{i:064x}", tree="deposit") for i in range(count)]
        reserve = dict(box("ff" * 32, tree="reserve"), assets=[{"tokenId": "nft", "amount": 1}])
        for b in boxes + [reserve]:
            self.index._put(b)
        node = WorkerNode(boxes + [reserve], price)
        return node, WorkerBuilder(node)

    def test_close_batches_are_sorted_and_at_most_32_without_a_closer(self):
        node, builder = self.lots(40)
        result = ra.settle_due(node, self.index, builder, self.directory.name)
        self.assertEqual([len(r["auctions"]) for r in result], [32, 8])
        requests = [r for r in builder.requests if r["action"] == "close"]
        self.assertEqual([b["boxId"] for r in requests for b in r["auctions"]], sorted(node.boxes))
        self.assertTrue(all("closer" not in r and r["schemaVersion"] == 2 for r in requests))

    def test_close_builder_failure_isolates_one_lot_and_closes_the_other_39(self):
        for error_type in (RuntimeError, ValueError):
            with self.subTest(error_type=error_type):
                node, builder = self.lots(40)
                offender = f"{17:064x}"
                run = builder.run

                def build(command, request=None):
                    if request and request["action"] == "close" and any(
                            b["boxId"] == offender for b in request["auctions"]):
                        raise error_type("invalid auction")
                    return run(command, request)

                with patch.object(builder, "run", side_effect=build):
                    result = ra.settle_due(node, self.index, builder, self.directory.name, execute=True)
                self.assertEqual([r for r in result if "error" in r],
                                 [{"auctions": [offender], "error": "invalid auction"}])
                closed = [i for r in result if r.get("sent") for i in r["auctions"]]
                self.assertEqual(closed, [f"{i:064x}" for i in range(40) if i != 17])

    def test_merge_builder_failure_isolates_one_deposit_and_merges_the_other_39(self):
        for error_type in (RuntimeError, ValueError):
            with self.subTest(error_type=error_type):
                node, builder = self.deposits(40, price=360)
                offender = f"{17:064x}"
                run = builder.run

                def build(command, request=None):
                    if request and request["action"] == "merge" and any(
                            b["boxId"] == offender for b in request["deposits"]):
                        raise error_type("invalid deposit")
                    return run(command, request)

                with patch.object(builder, "run", side_effect=build):
                    result = ra.merge_due(node, self.index, builder, self.directory.name, execute=True)
                self.assertEqual([r for r in result if "error" in r],
                                 [{"deposits": [offender], "error": "invalid deposit"}])
                merged = [i for r in result if r.get("sent") for i in r["deposits"]]
                self.assertEqual(merged, [f"{i:064x}" for i in range(40) if i != 17])

    def test_workers_inspect_in_batches_and_never_admit_false_shapes(self):
        for action, shape, factory, worker in (
                ("close", "auctionShape", self.lots, ra.settle_due),
                ("merge", "depositShape", self.deposits, ra.merge_due)):
            with self.subTest(action=action):
                node, builder = factory(201, price=360)
                offender = f"{100:064x}"
                run = builder.run

                def build(command, request=None):
                    result = run(command, request)
                    if request and request["action"] == "inspect":
                        for row in result["boxes"]:
                            if row["boxId"] == offender:
                                row[shape] = False
                    return result

                with patch.object(builder, "run", side_effect=build):
                    result = worker(node, self.index, builder, self.directory.name, execute=True)
                inspections = [r for r in builder.requests if r["action"] == "inspect"]
                self.assertEqual([len(r["boxes"]) for r in inspections], [100, 100, 1])
                field = "auctions" if action == "close" else "deposits"
                admitted = [b["boxId"] for r in builder.requests if r["action"] == action
                            for b in r[field]]
                self.assertEqual(admitted, [f"{i:064x}" for i in range(201) if i != 100])
                self.assertTrue(all(r.get("sent") for r in result))

    def test_ambiguous_merge_broadcast_failure_is_not_retried(self):
        node, builder = self.deposits(40, price=360)
        request = node.request
        attempts = []

        def uncertain(path, data=None):
            if path == "/transactions":
                attempts.append(data)
                raise RuntimeError("connection lost after submission")
            return request(path, data)

        with patch.object(node, "request", side_effect=uncertain):
            result = ra.merge_due(node, self.index, builder, self.directory.name, execute=True)
        self.assertEqual(len(attempts), 1)
        self.assertIn("connection lost", result[0]["reason"])

    def test_preparation_defers_every_action_at_manifest_voting_boundary(self):
        node, builder = self.lots(1)
        builder.manifest["votingLength"] = 17
        node.height = 101
        reason = "parameters may change at voting-epoch boundary height 102; retry at the next block"
        for action in ("collect", "bid", "close", "merge", "inspect"):
            with self.subTest(action=action), self.assertRaisesRegex(ra.StalePlan, reason):
                ra.prepare_current(node, builder, action, {"collector": "collector-tree"})
        self.assertEqual(builder.requests, [])

    def test_parameters_are_available_on_either_side_of_voting_boundary(self):
        node, builder = self.lots(1)
        builder.manifest["votingLength"] = 17
        for parent_height, price, storage_fee in ((100, 360, 1250000), (102, 400, 1275000)):
            node.height = parent_height
            node.parameters.update(minValuePerByte=price, storageFeeFactor=storage_fee)
            plan = ra.prepare_current(node, builder, "close", {"auctions": sorted(node.boxes)})
            self.assertEqual(plan["height"], parent_height + 1)
            self.assertEqual(plan["votingLength"], 17)
            self.assertEqual(plan["operatorParameters"]["minValuePerByte"], price)
            self.assertEqual(plan["operatorParameters"]["storageFeeFactor"], storage_fee)
            ra.ensure_current(node, plan)

    def test_settlement_defers_before_inspection_at_voting_boundary(self):
        node, builder = self.lots(1)
        node.height = builder.manifest["votingLength"] - 1
        with self.assertRaisesRegex(ra.StalePlan, "voting-epoch boundary height 1024"):
            ra.settle_due(node, self.index, builder, self.directory.name, execute=True)
        self.assertEqual(builder.requests, [])
        self.assertFalse(any(path != "/info" for path, _ in node.calls))

    def test_rent_candidate_listing_defers_before_inspection_at_voting_boundary(self):
        node, builder = self.lots(1)
        node.height = builder.manifest["votingLength"] - 1
        with patch.object(ra, "Node", return_value=node), \
                patch.object(ra, "Builder", return_value=builder), \
                patch.object(ra.Index, "sync"), \
                self.assertRaisesRegex(ra.StalePlan, "voting-epoch boundary height 1024"):
            ra.main(["--disabled-rules", "none", "--jar", "unused.jar", "--db",
                     str(Path(self.directory.name) / "listing.sqlite"), "rent-candidates"])
        self.assertEqual(builder.requests, [])

    def test_merge_defers_without_preparing_or_signing_at_voting_boundary(self):
        node, builder = self.deposits(2)
        node.height = builder.manifest["votingLength"] - 1
        result = ra.merge_due(node, self.index, builder, self.directory.name, execute=True)
        self.assertEqual(result[0]["deferred"], [f"{i:064x}" for i in range(2)])
        self.assertIn("voting-epoch boundary height 1024", result[0]["reason"])
        self.assertEqual(builder.requests, [])
        self.assertFalse(any(path != "/info" for path, _ in node.calls))

    def test_boundary_reached_during_preparation_stops_before_signing(self):
        node, builder = self.lots(1)
        builder.manifest["votingLength"] = 102
        builder.after_build = lambda: setattr(node, "height", 101)
        result = ra.settle_due(node, self.index, builder, self.directory.name, execute=True)
        self.assertIn("voting-epoch boundary height 102", result[0]["error"])
        self.assertEqual(len([r for r in builder.requests if r["action"] == "close"]), 1)
        self.assertFalse(any(path != "/info" for path, _ in node.calls))
        self.assertEqual(list(Path(self.directory.name).glob("*.json")), [])

    def test_additional_cost_headroom_reduces_batches_deterministically(self):
        node, builder = self.lots(20)
        result = ra.settle_due(node, self.index, builder, self.directory.name, max_cost=20000)
        self.assertEqual([len(r["auctions"]) for r in result], [10, 10])

    def test_json_size_limit_reduces_batches_and_reports_unfit_singletons(self):
        node, builder = self.lots(2)
        result = ra.settle_due(node, self.index, builder, self.directory.name, max_bytes=1)
        self.assertEqual(len(result), 2)
        self.assertTrue(all("byte budget" in r["error"] for r in result))
        attempted = [len(r["auctions"]) for r in builder.requests if r["action"] == "close"]
        self.assertEqual(attempted, [2, 1, 1])

    def test_stale_height_and_price_rebuild_the_whole_batch_before_signing(self):
        node, builder = self.lots(2)
        def advance_once():
            node.height += 1
            node.parameters["minValuePerByte"] = 10000
            builder.after_build = lambda: None
        builder.after_build = advance_once
        result = ra.settle_due(node, self.index, builder, self.directory.name, execute=True)
        self.assertEqual(result[0]["sent"], True)
        requests = [r for r in builder.requests if r["action"] == "close"]
        self.assertEqual([r["height"] for r in requests], [101, 102])
        self.assertEqual([r["fee"] for r in requests], [1000000, 2000000])
        self.assertEqual(len([c for c in node.calls if c[0] == "/transactions"]), 1)
        self.assertEqual(len(list(Path(self.directory.name).glob("*.json"))), 1)

    def test_changed_disabled_rules_makes_existing_plan_stale(self):
        node, builder = self.lots(1)
        node.disabled_rules = ra.disabled_rules("none")
        plan = ra.prepare_current(node, builder, "close", {"auctions": sorted(node.boxes)})
        self.assertEqual(builder.requests[-1]["parameters"]["disabledRules"], [])
        ra.ensure_current(node, plan)
        node.disabled_rules = ra.disabled_rules("123")
        with self.assertRaisesRegex(ra.StalePlan, "parameters changed"):
            ra.sign_plan(node, plan)
        self.assertFalse(any(path == "/wallet/transaction/sign" for path, _ in node.calls))
        rebuilt = ra.prepare_current(node, builder, "close", {"auctions": sorted(node.boxes)})
        self.assertEqual(rebuilt["operatorParameters"]["disabledRules"], [123])
        ra.ensure_current(node, rebuilt)

    def test_spent_member_is_removed_and_remaining_lots_rebuilt(self):
        node, builder = self.lots(3)
        spent = sorted(node.boxes)[1]
        del node.boxes[spent]
        result = ra.settle_due(node, self.index, builder, self.directory.name)
        self.assertEqual(result[0]["boxId"], spent)
        self.assertEqual(result[1]["auctions"], sorted(node.boxes))

    def test_native_cost_check_reduces_batch_before_broadcast(self):
        node, builder = self.lots(4)
        request = node.request
        def check(path, data=None):
            if path == "/transactions/check" and len(data["inputs"]) > 2:
                raise ra.NodeError(path, 400, "Accumulated cost should not exceed maxBlockCost")
            return request(path, data)
        with patch.object(node, "request", side_effect=check):
            result = ra.settle_due(node, self.index, builder, self.directory.name, execute=True)
        self.assertEqual([len(r["auctions"]) for r in result], [2, 2])
        self.assertEqual(len([c for c in node.calls if c[0] == "/transactions"]), 2)

    def test_ambiguous_broadcast_failure_is_not_retried(self):
        node, builder = self.lots(2)
        request = node.request
        attempts = []
        def uncertain(path, data=None):
            if path == "/transactions":
                attempts.append(data)
                raise RuntimeError("connection lost after submission")
            return request(path, data)
        with patch.object(node, "request", side_effect=uncertain):
            result = ra.settle_due(node, self.index, builder, self.directory.name, execute=True)
        self.assertEqual(len(attempts), 1)
        self.assertIn("connection lost", result[0]["error"])

    def test_serialized_short_and_all_close_variables_survive_wallet_signing(self):
        node, builder = self.lots(1)
        plan = ra.prepare_current(node, builder, "close", {"auctions": sorted(node.boxes)})
        # Transport test only: opaque serialized values are never reconstructed in Python.
        plan["signingRequest"]["tx"]["inputs"][0]["extension"]["127"] = "0300"
        expected = copy.deepcopy(plan["signingRequest"])
        ra.sign_plan(node, plan)
        self.assertEqual(next(data for path, data in node.calls if path == "/wallet/transaction/sign"), expected)
        self.assertEqual(set(expected["tx"]["inputs"][0]["extension"]), {"0", "1", "2", "127"})

    def test_merge_fee_is_native_and_unfunded_remainder_is_explicit(self):
        node, builder = self.deposits(1)
        result = ra.merge_due(node, self.index, builder, self.directory.name, execute=True)
        self.assertEqual(result[0]["unfunded"], [f"{0:064x}"])
        self.assertEqual(result[0]["mergeBudget"], 1000000)
        self.assertEqual(len([c for c in node.calls if c[0] == "/transactions"]), 0)

    def test_merge_batches_avoid_an_unnecessary_unfunded_singleton(self):
        node, builder = self.deposits(11)
        result = ra.merge_due(node, self.index, builder, self.directory.name, execute=True)
        self.assertEqual([len(r["deposits"]) for r in result], [9, 2])
        self.assertEqual([r["fee"] for r in result], [9000000, 2000000])
        requests = [r for r in builder.requests if r["action"] == "merge"]
        self.assertNotEqual(requests[0]["reserve"]["boxId"], requests[1]["reserve"]["boxId"])

    def test_dry_run_defers_dependent_merges_and_never_reuses_old_reserve(self):
        node, builder = self.deposits(12)
        result = ra.merge_due(node, self.index, builder, self.directory.name)
        self.assertEqual(len(result[1]["deferred"]), 2)
        self.assertEqual(len([r for r in builder.requests if r["action"] == "merge"]), 1)

    def test_merge_successor_invalid_tree_or_nft_defers_remaining_deposits(self):
        invalid = [
            {"ergoTree": "other"},
            {"assets": []},
            {"assets": [{"tokenId": "other", "amount": 1}]},
            {"assets": [{"tokenId": "nft", "amount": 2}]},
            {"assets": [{"tokenId": "other", "amount": 1}, {"tokenId": "nft", "amount": 1}]},
        ]
        for replacement in invalid:
            with self.subTest(replacement=replacement):
                node, builder = self.deposits(12)
                run = builder.run

                def build(command, request=None):
                    plan = run(command, request)
                    if request and request["action"] == "merge":
                        plan["outputBoxes"][0].update(replacement)
                    return plan

                with patch.object(builder, "run", side_effect=build):
                    result = ra.merge_due(node, self.index, builder, self.directory.name, execute=True)
                self.assertEqual(result[1]["deferred"], [f"{10:064x}", f"{11:064x}"])
                self.assertIn("invalid reserve successor", result[1]["reason"])
                self.assertEqual(len([r for r in builder.requests if r["action"] == "merge"]), 1)
                self.assertEqual(len([c for c in node.calls if c[0] == "/transactions"]), 1)

    def test_spent_reserve_reports_producer_rebuild(self):
        node, builder = self.deposits(2)
        del node.boxes["ff" * 32]
        result = ra.merge_due(node, self.index, builder, self.directory.name, execute=True)
        self.assertIn("reserve spent", result[0]["reason"])
        self.assertFalse(any(c[0] == "/transactions" for c in node.calls))

    def test_collection_preserves_explicit_bundle_collector_and_large_amount(self):
        node, _ = self.lots(20)
        partition = [[{"tokenId": "ee" * 32, "amount": 9223372036854775807}]]
        request = ra.make_request(node, "collect", {"sources": sorted(node.boxes),
            "collector": "collector-tree", "lots": partition}, 1024)
        self.assertEqual(request["schemaVersion"], 2)
        self.assertEqual(len(request["sources"]), 20)
        self.assertEqual(request["collector"], "collector-tree")
        self.assertEqual(request["lots"], partition)
        with self.assertRaisesRegex(ValueError, "collector"):
            ra.make_request(node, "collect", {"sources": []}, 1024)
        with self.assertRaisesRegex(ValueError, "schemaVersion 2"):
            ra.make_request(node, "collect", {"schemaVersion": 1}, 1024)

    def test_wallet_cannot_strip_extensions_even_when_claiming_the_same_id(self):
        node, builder = self.lots(1)
        plan = ra.prepare_current(node, builder, "close", {"auctions": sorted(node.boxes)})
        node.signed = copy.deepcopy(node.signed)
        node.signed["inputs"][0]["spendingProof"]["extension"].pop("2")
        with self.assertRaisesRegex(ValueError, "context extensions"):
            ra.sign_plan(node, plan)


if __name__ == "__main__":
    unittest.main()
