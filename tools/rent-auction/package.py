#!/usr/bin/env python3
"""Create a complete source archive, applicable node patch, and optional verified JAR."""
import argparse
import hashlib
import json
from pathlib import Path
import subprocess
import sys
import zipfile

ROOT = Path(__file__).resolve().parents[2]
BASE = "5528ef569a41ebccbc8658212e6ee3c97d990b96"
NEW_ROOTS = [
    "docs/rent-auction", "tools/rent-auction",
    "ergo-core/src/main/resources/rent-auction",
    "ergo-core/src/main/scala/org/ergoplatform/modifiers/mempool/rentauction",
    "ergo-core/src/test/scala/org/ergoplatform/modifiers/mempool/rentauction",
    "ergo-core/src/test/resources/rent-auction",
]
NEW_FILES = [
    "src/main/scala/org/ergoplatform/tools/RentAuctionCli.scala",
    "src/test/scala/org/ergoplatform/tools/RentAuctionCliSpec.scala",
    "src/test/scala/org/ergoplatform/nodeView/state/RentAuctionStateSpec.scala",
    "src/test/scala/org/ergoplatform/modifiers/mempool/RentAuctionBlockSpec.scala",
]


def git(*args):
    return subprocess.check_output(["git", *args], cwd=ROOT)


def new_file_patch(name):
    text = (ROOT / name).read_text(encoding="utf-8")
    lines = text.splitlines()
    result = [f"diff --git a/{name} b/{name}", "new file mode 100644",
              "--- /dev/null", f"+++ b/{name}", f"@@ -0,0 +1,{len(lines)} @@"]
    result.extend("+" + line for line in lines)
    if not text.endswith("\n"):
        result.append("\\ No newline at end of file")
    return ("\n".join(result) + "\n").encode("utf-8")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--with-jar", action="store_true")
    args = parser.parse_args()
    if subprocess.run(["git", "merge-base", "--is-ancestor", BASE, "HEAD"],
                      cwd=ROOT, check=False).returncode:
        raise RuntimeError("Packaging requires a checkout descended from the pinned baseline")
    report_path = ROOT / "docs/rent-auction/verification.json"
    report = json.loads(report_path.read_text(encoding="utf-8"))
    if not report.get("standaloneCliMatchesScalaVector") or not report.get("lithosTestsPassed"):
        raise RuntimeError("Run verify.py --assemble --lithos PATH before packaging")
    from verify import source_fingerprint
    if report.get("sourceFingerprintSha256") != source_fingerprint():
        raise RuntimeError("Source changed after verification; rerun verify.py")
    tracked = set(git("ls-files", "-z").decode().split("\0")) - {""}
    owned = set(NEW_FILES)
    for root in NEW_ROOTS:
        for path in (ROOT / root).rglob("*"):
            if path.is_file() and "__pycache__" not in path.parts and \
                    path.suffix not in (".sqlite", ".db", ".pyc"):
                owned.add(path.relative_to(ROOT).as_posix())
    files = sorted(tracked | owned)
    patch = git("diff", "--binary", BASE, "--")
    patch += b"".join(new_file_patch(name) for name in sorted(owned - tracked))
    destination = ROOT / "dist"
    destination.mkdir(exist_ok=True)
    node_patch = destination / "rent-auction-node.patch"
    node_patch.write_bytes(patch)
    archive = destination / "rent-auction-submission.zip"
    checksums = {}
    with zipfile.ZipFile(archive, "w", zipfile.ZIP_DEFLATED, compresslevel=6) as bundle:
        start = ("# Rent-auction submission\n\n"
                 "Start with [the package guide](source/docs/rent-auction/README.md).\n\n"
                 "`source/` contains the complete reference-node source checkout and proposal.\n"
                 "`rent-auction-node.patch` applies to node baseline " + BASE + ".\n"
                 "The separate Lithos patch and its pinned revision are under "
                 "`source/tools/rent-auction/lithos/`.\n"
                 "No mainnet activation is configured.\n")
        bundle.writestr("START-HERE.md", start)
        for name in files:
            path = ROOT / name
            if path.is_file():
                data = path.read_bytes()
                entry = "source/" + name
                bundle.writestr(entry, data)
                checksums[entry] = hashlib.sha256(data).hexdigest()
        bundle.write(node_patch, "rent-auction-node.patch")
        checksums["rent-auction-node.patch"] = hashlib.sha256(patch).hexdigest()
        if args.with_jar:
            jar = ROOT / "target/scala-2.12" / report["jar"]
            data = jar.read_bytes()
            digest = hashlib.sha256(data).hexdigest()
            if digest != report["jarSha256"]:
                raise RuntimeError("Assembled JAR differs from the verified artifact")
            entry = "bin/ergo-rent-auction-reference.jar"
            bundle.writestr(entry, data)
            checksums[entry] = digest
        bundle.writestr("SHA256SUMS", "".join(
            f"{digest}  {name}\n" for name, digest in sorted(checksums.items())))
    checksum_path = archive.with_suffix(".zip.sha256")
    checksum_path.write_text(hashlib.sha256(archive.read_bytes()).hexdigest() +
                             "  " + archive.name + "\n", encoding="ascii")
    print(f"Package: {archive}\nPatch: {node_patch}\nChecksums: {checksum_path}")


if __name__ == "__main__":
    try:
        main()
    except (RuntimeError, OSError, subprocess.SubprocessError) as error:
        print(error, file=sys.stderr)
        sys.exit(1)
