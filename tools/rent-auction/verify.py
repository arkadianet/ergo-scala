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
from datetime import datetime, timezone

ROOT = Path(__file__).resolve().parents[2]
LITHOS_BASE = "88bb1822022bf9521c281314c060a8943521d0f6"


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


def source_fingerprint():
    from package import NEW_FILES, NEW_ROOTS
    names = set(subprocess.check_output(["git", "ls-files", "-z"], cwd=ROOT)
                .decode().split("\0")) - {""}
    names.update(NEW_FILES)
    for folder in NEW_ROOTS:
        names.update(p.relative_to(ROOT).as_posix() for p in (ROOT / folder).rglob("*")
                     if p.is_file() and "__pycache__" not in p.parts)
    digest = hashlib.sha256()
    for name in sorted(names):
        path = ROOT / name
        if path.is_file() and path.suffix in (
                ".scala", ".es", ".sbt", ".conf", ".py", ".patch", ".properties"):
            digest.update(name.encode("utf-8") + b"\0" + path.read_bytes() + b"\0")
    return digest.hexdigest()


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
    initial_fingerprint = source_fingerprint()
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
    node_text = run(sbt + commands, ROOT, logs / "node.log")
    python_text = run([sys.executable, "-m", "unittest", "discover", "-s",
                       "tools/rent-auction", "-v"], ROOT, logs / "python.log")
    report = {
        "createdUtc": datetime.now(timezone.utc).isoformat(),
        "nodeBaseline": "5528ef569a41ebccbc8658212e6ee3c97d990b96",
        "scalaTestsPassed": sum(map(int, re.findall(r"Tests: succeeded (\d+)", node_text))),
        "scalaTestsIgnored": sum(map(int, re.findall(r"ignored (\d+)", node_text))),
        "pythonTestsPassed": int(re.search(r"Ran (\d+) tests", python_text).group(1)),
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
        head = subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=checkout,
                                       text=True).strip()
        if head != LITHOS_BASE:
            raise RuntimeError("Lithos checkout must be at the documented baseline")
        tests = "testOnly transactions.rent.AuctionRentSourceSpec " + \
                "transactions.rent.StorageRentSourceSpec transactions.rent.StorageRentBuilderSpec"
        text = run(sbt + ['set Test / scalacOptions ++= Seq("-encoding", "UTF-8")', tests],
                   checkout, logs / "lithos.log")
        report["lithosTestsPassed"] = sum(map(int,
            re.findall(r"Tests: succeeded (\d+)", text)))
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
        for name in ("collect-request.json", "collect-plan.json"):
            shutil.copy2(ROOT / "target/rent-auction-vectors" / name, vectors / name)
        run(cli + ["prepare", "mainnet", str(logs / "collect-plan.json"),
                   str(vectors / "collect-request.json")], ROOT, logs / "cli-prepare.log")
        if json.loads((logs / "collect-plan.json").read_text()) != \
                json.loads((vectors / "collect-plan.json").read_text()):
            raise RuntimeError("Standalone JAR plan differs from the Scala test vector")
        report["standaloneCliMatchesScalaVector"] = True
    destination = ROOT / "docs/rent-auction/verification.json"
    report["sourceFingerprintSha256"] = source_fingerprint()
    if report["sourceFingerprintSha256"] != initial_fingerprint:
        raise RuntimeError("Source changed during verification; rerun on a stable checkout")
    destination.write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
    print(f"Verified. Evidence: {destination}")


if __name__ == "__main__":
    try:
        main()
    except (RuntimeError, OSError, subprocess.SubprocessError) as error:
        print(error, file=sys.stderr)
        sys.exit(1)
