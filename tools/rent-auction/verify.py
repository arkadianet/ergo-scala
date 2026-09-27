#!/usr/bin/env python3
"""Run the submission's selected Scala/Python tests and record reproducible evidence."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import tempfile
from datetime import datetime, timezone

from package import packaged_files

ROOT = Path(__file__).resolve().parents[2]
LITHOS_BASE = "88bb1822022bf9521c281314c060a8943521d0f6"
REGENERATED_VECTORS = {
    "docs/rent-auction/vectors/collect-request.json",
    "docs/rent-auction/vectors/collect-plan.json",
    "docs/rent-auction/vectors/mainnet-contracts.json",
}


def launcher(path):
    candidates = [path, os.environ.get("SBT_LAUNCH_JAR"),
                  "C:/Program Files (x86)/sbt/bin/sbt-launch.jar"]
    jar = next((p for p in candidates if p and Path(p).is_file()), None)
    if jar:
        return ["java", "-Dfile.encoding=UTF-8", "-Dsbt.boot.server.forcestart=true",
                "-Dsbt.server.autostart=false", "-Xmx2G", "-Xss8M", "-jar", jar]
    command = shutil.which("sbt")
    if command:
        return [command, "-batch"]
    raise RuntimeError("Install sbt or set SBT_LAUNCH_JAR to sbt-launch.jar")


def source_fingerprint(exclude=()):
    digest = hashlib.sha256()
    for name in packaged_files(ROOT):
        if name == "docs/rent-auction/verification.json" or name in exclude:
            continue
        path = ROOT / name
        if path.is_file() and path.suffix in (
                ".scala", ".es", ".sbt", ".conf", ".py", ".patch", ".properties", ".md", ".json"):
            digest.update(name.encode("utf-8") + b"\0" + path.read_bytes() + b"\0")
    return digest.hexdigest()


def scala_test_count(text, commands):
    groups = [command for command in commands if "testOnly" in command]
    counts = list(map(int, re.findall(r"Tests: succeeded (\d+)", text)))
    for i, group in enumerate(groups):
        if i >= len(counts) or counts[i] == 0:
            raise RuntimeError(f"No tests succeeded for {group}")
    if len(counts) != len(groups):
        raise RuntimeError("Unexpected test summaries for " + "; ".join(groups))
    return sum(counts)


def python_test_count(text):
    summary = re.search(r"^Ran (\d+) tests?\b", text, re.MULTILINE)
    if not summary or int(summary.group(1)) == 0:
        raise RuntimeError("No tests ran for Python unittest tools/rent-auction")
    return int(summary.group(1))


def verify_lithos_tree(checkout, patch):
    head = subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=checkout,
                                   text=True).strip()
    if head != LITHOS_BASE:
        raise RuntimeError("Lithos checkout must be at the documented baseline")
    status = subprocess.check_output(
        ["git", "--no-optional-locks", "status", "--porcelain", "-z", "--untracked-files=all"],
        cwd=checkout)
    paths = set()
    records = iter(status.decode().split("\0"))
    for record in records:
        if record:
            paths.add(record[3:])
            if "R" in record[:2] or "C" in record[:2]:
                paths.add(next(records))
    # Build output and the Play application log that running the Lithos tests writes.
    paths = {name for name in paths
             if not {"target", ".bsp", ".idea"}.intersection(Path(name).parts)
             and Path(name).parts[0] != "logs"}
    with tempfile.TemporaryDirectory(prefix="rent-auction-lithos-") as directory:
        expected = Path(directory)
        archive = subprocess.check_output(["git", "archive", LITHOS_BASE], cwd=checkout)
        subprocess.run(["tar", "-x", "-C", directory], input=archive, check=True)
        baseline_paths = {path.relative_to(expected).as_posix() for path in expected.rglob("*")
                          if path.is_file() or path.is_symlink()}
        patch_data = patch.read_bytes()
        stats = subprocess.check_output(["git", "apply", "--numstat", "-z"],
                                        input=patch_data, cwd=expected)
        paths.update(record.split("\t", 2)[2] for record in stats.decode().split("\0") if record)
        subprocess.run(["git", "apply", "-"], input=patch_data, cwd=expected, check=True)
        paths.update(name for name in baseline_paths
                     if not (expected / name).exists() and not (expected / name).is_symlink())

        def contents(path):
            if path.is_symlink():
                return ("symlink", os.readlink(path))
            if path.is_file():
                return ("file", path.read_bytes())
            return ("directory",) if path.exists() else None

        mismatches = [name for name in sorted(paths)
                      if contents(checkout / name) != contents(expected / name)]
        if mismatches:
            raise RuntimeError("Lithos tree differs from the submitted patch:\n" +
                               "\n".join(mismatches))


def run(command, cwd, log):
    print(f"Running in {cwd}; log: {log}", flush=True)
    with log.open("w", encoding="utf-8") as output:
        result = subprocess.run(command, cwd=cwd, stdout=output,
                                stderr=subprocess.STDOUT)
    text = log.read_text(encoding="utf-8", errors="replace")
    summaries = [line for line in text.splitlines()
                 if re.search(r"Tests:|Total number|FAILED|^Ran \d+ tests|^OK$", line)]
    print("\n".join(summaries[-12:]), flush=True)
    if result.returncode:
        raise RuntimeError(f"Verification failed ({result.returncode}); inspect {log}")
    return text


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--sbt-launcher")
    parser.add_argument("--lithos", type=Path,
                        help="Pinned Lithos checkout with this package's patch applied")
    parser.add_argument("--assemble", action="store_true")
    args = parser.parse_args()
    logs = ROOT / "target/rent-auction-verification"
    logs.mkdir(parents=True, exist_ok=True)
    regenerated = REGENERATED_VECTORS if args.assemble else ()
    initial_fingerprint = source_fingerprint(exclude=regenerated)
    commands = [
        "set Test / parallelExecution := false",
        "ergoCore/testOnly *RentAuction*Spec org.ergoplatform.reemission.ReemissionRulesSpec",
        "testOnly *RentAuction*Spec org.ergoplatform.modifiers.mempool.ExpirationSpecification",
        "ergoWallet/testOnly *ErgoProvingInterpreterSpec",
        "testOnly *CandidateGeneratorSpec *CandidateGeneratorPropSpec *ErgoWalletServiceSpec",
    ]
    if args.assemble:
        commands.append("assembly")
    sbt = launcher(args.sbt_launcher)
    generated_vectors = ROOT / "target/rent-auction-vectors"
    if generated_vectors.exists():
        shutil.rmtree(generated_vectors)
    node_text = run(sbt + commands, ROOT, logs / "node.log")
    scala_count = scala_test_count(node_text, commands)
    vector_names = ("collect-request.json", "collect-plan.json")
    missing_vectors = [name for name in vector_names if not (generated_vectors / name).is_file()]
    if missing_vectors:
        raise RuntimeError("RentAuctionCliSpec did not generate fresh verification vectors in "
                           f"{generated_vectors}: missing {', '.join(missing_vectors)}")
    python_text = run([sys.executable, "-m", "unittest", "discover", "-s",
                       "tools/rent-auction", "-v"], ROOT, logs / "python.log")
    report = {
        "createdUtc": datetime.now(timezone.utc).isoformat(),
        "nodeBaseline": "5528ef569a41ebccbc8658212e6ee3c97d990b96",
        "scalaTestsPassed": scala_count,
        "scalaTestsIgnored": sum(map(int, re.findall(r"ignored (\d+)", node_text))),
        "pythonTestsPassed": python_test_count(python_text),
        "lithosTestsPassed": None,
        "lithosBaseline": LITHOS_BASE,
        "assemblyBuilt": args.assemble,
        "commands": commands,
        "limitations": ["Synthetic UTXO snapshots and fake PoW",
                        "No mainnet activation or real-funds tests",
                        "No live Lithos pool/collateral-lender deployment",
                        "No independent security audit"],
    }
    if args.lithos:
        checkout = args.lithos.resolve()
        verify_lithos_tree(checkout, ROOT / "tools/rent-auction/lithos/lithos-rent-auction.patch")
        tests = "testOnly transactions.rent.AuctionRentSourceSpec " + \
                "transactions.rent.StorageRentSourceSpec transactions.rent.StorageRentBuilderSpec"
        text = run(sbt + ['set Test / scalacOptions ++= Seq("-encoding", "UTF-8")', tests],
                   checkout, logs / "lithos.log")
        report["lithosTestsPassed"] = scala_test_count(text, [tests])
    if args.assemble:
        jar = max((ROOT / "target/scala-2.12").glob("ergo-*.jar"),
                  key=lambda p: p.stat().st_mtime)
        report["jar"] = jar.name
        report["jarSha256"] = hashlib.sha256(jar.read_bytes()).hexdigest()
        vectors = ROOT / "docs/rent-auction/vectors"
        vectors.mkdir(parents=True, exist_ok=True)
        cli = ["java", "-cp", str(jar), "org.ergoplatform.tools.RentAuctionCli"]
        run(cli + ["manifest", "mainnet", str(vectors / "mainnet-contracts.json")],
            ROOT, logs / "cli-manifest.log")
        for name in vector_names:
            shutil.copy2(generated_vectors / name, vectors / name)
        run(cli + ["prepare", "mainnet", str(logs / "collect-plan.json"),
                   str(vectors / "collect-request.json")], ROOT, logs / "cli-prepare.log")
        if json.loads((logs / "collect-plan.json").read_text()) != \
                json.loads((vectors / "collect-plan.json").read_text()):
            raise RuntimeError("Standalone JAR plan differs from the Scala test vector")
        report["standaloneCliMatchesScalaVector"] = True
    destination = ROOT / "docs/rent-auction/verification.json"
    if source_fingerprint(exclude=regenerated) != initial_fingerprint:
        raise RuntimeError("Source changed during verification; rerun on a stable checkout")
    report["sourceFingerprintSha256"] = source_fingerprint()
    destination.write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
    print(f"Verified. Evidence: {destination}")


if __name__ == "__main__":
    try:
        main()
    except (RuntimeError, OSError, subprocess.SubprocessError) as error:
        print(error, file=sys.stderr)
        sys.exit(1)
