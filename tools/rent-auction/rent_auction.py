#!/usr/bin/env python3
"""Rent-auction operator tools. Python 3.10+, standard library only."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import sqlite3
import subprocess
import sys
import tempfile
from urllib.error import HTTPError
from urllib.request import Request, urlopen
from urllib.parse import urlparse


def read_json(path):
    return json.loads(Path(path).read_text(encoding="utf-8"))


def write_json(path, value):
    destination = Path(path)
    destination.parent.mkdir(parents=True, exist_ok=True)
    temporary = destination.with_suffix(destination.suffix + ".tmp")
    temporary.write_text(json.dumps(value, indent=2) + "\n", encoding="utf-8")
    temporary.replace(destination)


class Node:
    def __init__(self, url, api_key=None):
        parsed = urlparse(url)
        if parsed.scheme not in ("http", "https") or not parsed.hostname:
            raise ValueError("Node must be an HTTP(S) URL")
        if parsed.username or parsed.password:
            raise ValueError("Use ERGO_API_KEY, not credentials in URLs")
        if parsed.scheme == "http" and parsed.hostname not in (
                "localhost", "127.0.0.1", "::1") and api_key:
            raise ValueError("Use HTTPS when sending an API key to a remote node")
        self.url, self.api_key = url.rstrip("/"), api_key

    def request(self, path, data=None):
        headers = {"Accept": "application/json"}
        if self.api_key:
            headers["api_key"] = self.api_key
        if data is not None:
            headers["Content-Type"] = "application/json"
        request = Request(self.url + path,
                          data=None if data is None else json.dumps(data).encode(),
                          headers=headers)
        try:
            with urlopen(request, timeout=30) as response:
                raw = response.read(16 * 1024 * 1024 + 1)
                if len(raw) > 16 * 1024 * 1024:
                    raise ValueError("Node response exceeds 16 MiB")
                return json.loads(raw) if raw else None
        except HTTPError as error:
            detail = error.read(4096).decode(errors="replace")
            raise RuntimeError(f"Node {path}: HTTP {error.code}: {detail}") from error

    def canonical_id(self, height):
        # Ergo's HeadersProcessor puts the best-header-chain id first.
        ids = self.request(f"/blocks/at/{height}")
        if not ids:
            raise ValueError(f"No canonical header available at height {height}")
        return ids[0]

    def box(self, box_id):
        if not isinstance(box_id, str) or not re.fullmatch(r"[0-9a-fA-F]{64}", box_id):
            raise ValueError("Box id must contain exactly 32 hexadecimal bytes")
        return self.request(f"/utxo/withPool/byId/{box_id}")


class Index:
    """Confirmed UTXO index with transactional undo, including same-block spends."""
    def __init__(self, path):
        self.db = sqlite3.connect(path)
        self.db.executescript("""
          PRAGMA journal_mode=WAL;
          CREATE TABLE IF NOT EXISTS blocks(
            height INTEGER PRIMARY KEY, id TEXT UNIQUE NOT NULL, parent TEXT NOT NULL);
          CREATE TABLE IF NOT EXISTS boxes(
            id TEXT PRIMARY KEY, tree TEXT NOT NULL, created INTEGER NOT NULL,
            json TEXT NOT NULL);
          CREATE INDEX IF NOT EXISTS boxes_tree ON boxes(tree);
          CREATE INDEX IF NOT EXISTS boxes_created ON boxes(created);
          CREATE TABLE IF NOT EXISTS undo(
            height INTEGER NOT NULL, id TEXT NOT NULL, previous TEXT,
            PRIMARY KEY(height,id));
          CREATE TABLE IF NOT EXISTS metadata(key TEXT PRIMARY KEY, value TEXT NOT NULL);
        """)

    def close(self):
        self.db.close()

    def tip(self):
        return self.db.execute(
            "SELECT height,id FROM blocks ORDER BY height DESC LIMIT 1").fetchone()

    def boxes(self, tree=None, created_before=None):
        query, values = "SELECT json FROM boxes WHERE 1=1", []
        if tree is not None:
            query += " AND tree=?"
            values.append(tree)
        if created_before is not None:
            query += " AND created<=?"
            values.append(created_before)
        return [json.loads(row[0]) for row in self.db.execute(query + " ORDER BY id", values)]

    def _put(self, box):
        self.db.execute("INSERT OR REPLACE INTO boxes VALUES(?,?,?,?)",
                        (box["boxId"], box["ergoTree"], box["creationHeight"],
                         json.dumps(box, separators=(",", ":"))))

    def apply(self, header, transactions):
        height, block_id = header["height"], header["id"]
        tip = self.tip()
        if tip and (height != tip[0] + 1 or header["parentId"] != tip[1]):
            raise ValueError("Block does not extend indexed chain")
        if not tip and height != 1:
            raise ValueError("A complete index must start at block 1")
        with self.db:
            self.db.execute("INSERT INTO blocks VALUES(?,?,?)",
                            (height, block_id, header["parentId"]))
            for tx in transactions:
                for box_id in [i["boxId"] for i in tx["inputs"]] + [
                        o["boxId"] for o in tx["outputs"]]:
                    old = self.db.execute("SELECT json FROM boxes WHERE id=?",
                                          (box_id,)).fetchone()
                    self.db.execute("INSERT OR IGNORE INTO undo VALUES(?,?,?)",
                                    (height, box_id, old[0] if old else None))
                for entry in tx["inputs"]:
                    self.db.execute("DELETE FROM boxes WHERE id=?", (entry["boxId"],))
                for box in tx["outputs"]:
                    self._put(box)

    def rollback(self):
        tip = self.tip()
        if not tip:
            return
        with self.db:
            changes = list(self.db.execute(
                "SELECT id,previous FROM undo WHERE height=?", (tip[0],)))
            for box_id, old in changes:
                self.db.execute("DELETE FROM boxes WHERE id=?", (box_id,))
                if old is not None:
                    self._put(json.loads(old))
            self.db.execute("DELETE FROM undo WHERE height=?", (tip[0],))
            self.db.execute("DELETE FROM blocks WHERE height=?", (tip[0],))

    def sync(self, node, through=None):
        genesis = node.request("/utxo/genesis")
        fingerprint = hashlib.sha256("|".join(sorted(
            box["boxId"] for box in genesis)).encode()).hexdigest()
        known = self.db.execute(
            "SELECT value FROM metadata WHERE key='genesis'").fetchone()
        if known and known[0] != fingerprint:
            raise ValueError("Index belongs to a different chain; use a separate database")
        if not known:
            if self.tip():
                raise ValueError("Old index has no genesis metadata; rebuild in a new file")
            with self.db:
                for box in genesis:
                    self._put(box)
                self.db.execute("INSERT INTO metadata VALUES('genesis',?)", (fingerprint,))
        full_height = node.request("/info")["fullHeight"]
        while self.tip():
            height, block_id = self.tip()
            if height <= full_height and node.canonical_id(height) == block_id:
                break
            self.rollback()
        target = min(full_height, through if through is not None else full_height)
        next_height = self.tip()[0] + 1 if self.tip() else 1
        for height in range(next_height, target + 1):
            block_id = node.canonical_id(height)
            block = node.request(f"/blocks/{block_id}")
            header = block["header"]
            if header["id"] != block_id or header["height"] != height:
                raise ValueError("Inconsistent block returned by node")
            if node.canonical_id(height) != block_id:
                raise RuntimeError("Chain reorganized during sync; retry sync")
            self.apply(header, block["blockTransactions"]["transactions"])
        return self.tip()


class Builder:
    def __init__(self, jar, network):
        self.jar = str(Path(jar).resolve())
        self.network = network

    def run(self, command, request=None):
        with tempfile.TemporaryDirectory(prefix="ergo-rent-") as directory:
            output = Path(directory) / "result.json"
            args = ["java", "-cp", self.jar, "org.ergoplatform.tools.RentAuctionCli",
                    command, self.network, str(output)]
            if request is not None:
                source = Path(directory) / "request.json"
                write_json(source, request)
                args.append(str(source))
            result = subprocess.run(args, capture_output=True, text=True, timeout=120)
            if result.returncode:
                raise RuntimeError("Scala builder failed: " + result.stderr[-4000:] +
                                   result.stdout[-4000:])
            return read_json(output)


def current_parameters(node):
    info = node.request("/info")
    parameters = info["parameters"]
    return info["fullHeight"] + 1, {
        "storageFeeFactor": parameters["storageFeeFactor"],
        "minValuePerByte": parameters["minValuePerByte"],
    }


def sign_plan(node, plan):
    # Resolve again immediately before signing; never trust a stale index alone.
    for box in plan["inputBoxes"]:
        live = node.box(box["boxId"])
        if any(live.get(key) != box.get(key) for key in (
                "boxId", "value", "ergoTree", "creationHeight", "assets",
                "additionalRegisters", "transactionId", "index")):
            raise ValueError("Input changed or was spent; rebuild the transaction")
    signed = node.request("/wallet/transaction/sign", plan["signingRequest"])
    if signed["id"] != plan["transactionId"]:
        raise ValueError("Wallet changed the transaction being signed")
    return signed


def make_request(node, action, fields):
    height, parameters = current_parameters(node)
    request = dict(fields, action=action, height=height, parameters=parameters)
    for name in ("auction", "reserve", "sponsor"):
        if request.get(name) is not None:
            value = request[name]
            request[name] = node.box(value if isinstance(value, str) else value["boxId"])
    for name in ("sources", "funding", "deposits"):
        if name in request:
            request[name] = [node.box(b if isinstance(b, str) else b["boxId"])
                             for b in request[name]]
    return request


def settle_due(node, index, builder, closer, output_dir, execute=False):
    manifest = builder.run("manifest")
    height, parameters = current_parameters(node)
    boxes = index.boxes(tree=manifest["auction"]["ergoTree"])
    rows = []
    for offset in range(0, len(boxes), 100):
        rows.extend(builder.run("prepare", {"action": "inspect", "height": height,
            "parameters": parameters, "boxes": boxes[offset:offset + 100]})["boxes"])
    results = []
    for row in rows:
        if row["deadline"] is None or row["deadline"] > height:
            continue
        try:
            request = make_request(node, "settle", {
                "auction": row["boxId"], "closer": closer, "funding": []})
            plan = builder.run("prepare", request)
            path = Path(output_dir) / (row["boxId"] + ".json")
            write_json(path, plan)
            if execute:
                signed = sign_plan(node, plan)
                node.request("/transactions/check", signed)
                node.request("/transactions", signed)
            results.append({"boxId": row["boxId"], "plan": str(path), "sent": execute})
        except (RuntimeError, ValueError) as error:
            results.append({"boxId": row["boxId"], "error": str(error)})
    return results


def merge_due(node, index, builder, output_dir, execute=False):
    manifest = builder.run("manifest")
    reserves = [b for b in index.boxes(tree=manifest["reserve"]["ergoTree"])
                if b.get("assets") and b["assets"][0]["tokenId"] == manifest["reserveNft"]
                and b["assets"][0]["amount"] == 1]
    if len(reserves) != 1:
        raise ValueError("Index must contain exactly one authentic reserve; sync first")
    deposits = index.boxes(tree=manifest["deposit"]["ergoTree"])
    reserve = reserves[0]
    results = []
    for offset in range(0, len(deposits), 10):
        batch = deposits[offset:offset + 10]
        request = make_request(node, "merge", {
            "reserve": reserve["boxId"], "deposits": [b["boxId"] for b in batch]})
        plan = builder.run("prepare", request)
        path = Path(output_dir) / (plan["transactionId"] + ".json")
        write_json(path, plan)
        if execute:
            signed = sign_plan(node, plan)
            node.request("/transactions/check", signed)
            node.request("/transactions", signed)
            reserve = plan["outputBoxes"][0]
        results.append({"plan": str(path), "deposits": len(batch), "sent": execute})
        if not execute:
            # Subsequent batches depend on the first reserve successor existing.
            break
    return results


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--node", default="http://127.0.0.1:9053")
    parser.add_argument("--db", default="rent-auction.sqlite")
    parser.add_argument("--jar", help="Assembled node JAR including the Scala builders")
    parser.add_argument("--network", default="mainnet",
                        help="mainnet, testnet, devnet60, or config:/absolute/path.conf")
    sub = parser.add_subparsers(dest="command", required=True)
    sync = sub.add_parser("sync", help="Index confirmed boxes; resume/reconcile reorgs")
    sync.add_argument("--through", type=int)
    listing = sub.add_parser("list", help="List indexed boxes or aged rent candidates")
    listing.add_argument("--tree")
    listing.add_argument("--created-before", type=int)
    sub.add_parser("rent-candidates", help="Classify indexed aged boxes with Scala rules")
    sub.add_parser("manifest")
    prepare = sub.add_parser("prepare", help="Build from JSON; box ids resolved from node")
    prepare.add_argument("request")
    prepare.add_argument("output")
    sign = sub.add_parser("sign", help="Ask the local node wallet to sign a prepared plan")
    sign.add_argument("plan")
    sign.add_argument("output")
    send = sub.add_parser("broadcast", help="Check then broadcast an ordinary auction tx")
    send.add_argument("signed")
    candidate = sub.add_parser("candidate", help="Submit producer-only rent packages")
    candidate.add_argument("signed", nargs="+")
    candidate.add_argument("--miner-pk", help="Lithos lender/custom header public key")
    queue = sub.add_parser("lithos-queue", help="Offer one prepared rent batch to Lithos")
    queue.add_argument("plan")
    queue.add_argument("signed")
    queue.add_argument("directory")
    worker = sub.add_parser("settle-due", help="Build due settlements and no-bid burns")
    worker.add_argument("--closer", required=True, help="Closer's ErgoTree hex")
    worker.add_argument("--output-dir", default="settlements")
    worker.add_argument("--execute", action="store_true", help="Sign/check/broadcast plans")
    merger = sub.add_parser("merge-due", help="Merge confirmed auction proceeds in batches")
    merger.add_argument("--output-dir", default="merges")
    merger.add_argument("--execute", action="store_true", help="Sign/check/broadcast plans")
    args = parser.parse_args(argv)
    node = Node(args.node, os.environ.get("ERGO_API_KEY"))
    builder = Builder(args.jar, args.network) if args.jar else None
    result = None
    if args.command in ("sync", "list", "rent-candidates", "settle-due", "merge-due"):
        index = Index(args.db)
        try:
            if args.command == "sync":
                result = {"tip": index.sync(node, args.through)}
            elif args.command == "list":
                result = {"tip": index.tip(),
                          "boxes": index.boxes(args.tree, args.created_before)}
            else:
                if builder is None:
                    raise ValueError("--jar is required")
                index.sync(node)
                if args.command == "rent-candidates":
                    manifest = builder.run("manifest")
                    height, parameters = current_parameters(node)
                    boxes = index.boxes(created_before=height - manifest["storagePeriod"])
                    rows = []
                    for offset in range(0, len(boxes), 100):
                        rows.extend(builder.run("prepare", {"action": "inspect",
                            "height": height, "parameters": parameters,
                            "boxes": boxes[offset:offset + 100]})["boxes"])
                    result = {"height": height, "boxes": rows}
                elif args.command == "merge-due":
                    result = merge_due(node, index, builder, args.output_dir, args.execute)
                else:
                    result = settle_due(node, index, builder, args.closer,
                                        args.output_dir, args.execute)
        finally:
            index.close()
    elif args.command == "manifest":
        if builder is None:
            raise ValueError("--jar is required")
        result = builder.run("manifest")
    elif args.command == "prepare":
        if builder is None:
            raise ValueError("--jar is required")
        fields = read_json(args.request)
        request = make_request(node, fields.pop("action"), fields)
        result = builder.run("prepare", request)
        write_json(args.output, result)
        result = {"plan": args.output, "transactionId": result["transactionId"]}
    elif args.command == "sign":
        result = sign_plan(node, read_json(args.plan))
        write_json(args.output, result)
        result = {"signed": args.output, "transactionId": result["id"]}
    elif args.command == "broadcast":
        signed = read_json(args.signed)
        node.request("/transactions/check", signed)
        result = node.request("/transactions", signed)
    elif args.command == "lithos-queue":
        plan, signed = read_json(args.plan), read_json(args.signed)
        height, _ = current_parameters(node)
        if plan["height"] != height or signed["id"] != plan["transactionId"]:
            raise ValueError("Stale height or different signed transaction; rebuild")
        for box in plan["inputBoxes"]:
            node.box(box["boxId"])
        destination = Path(args.directory) / f"{height}.json"
        write_json(destination, {"height": height, "transaction": signed})
        result = {"queueFile": str(destination), "height": height}
    elif args.command == "candidate":
        txs = [read_json(path) for path in args.signed]
        if args.miner_pk:
            result = node.request("/mining/candidateWithTxsAndPk",
                                  {"txs": txs, "pk": args.miner_pk})
        else:
            result = node.request("/mining/candidateWithTxs", txs)
    print(json.dumps(result, indent=2))


if __name__ == "__main__":
    try:
        main()
    except (ValueError, RuntimeError, OSError, KeyError, subprocess.TimeoutExpired) as exc:
        print(f"rent-auction: {exc}", file=sys.stderr)
        sys.exit(1)
