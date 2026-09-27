"""Hermetic submission checks; Scala commands are represented by captured summaries."""
from contextlib import redirect_stdout
import io
import json
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import patch
import zipfile

import package
import verify


class RepositoryTests(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.root = Path(self.directory.name)
        self.git("init", "-q")

    def git(self, *args):
        return subprocess.check_output(["git", "-c", "core.hooksPath=/dev/null", *args], cwd=self.root)

    def write(self, name, text):
        path = self.root / name
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(text, encoding="utf-8")
        return path

    def baseline(self):
        self.git("add", ".")
        self.git("-c", "user.name=Test", "-c", "user.email=test@example.invalid",
                 "-c", "commit.gpgsign=false", "commit", "-qm", "baseline")
        return self.git("rev-parse", "HEAD").decode().strip()


class PackageTests(RepositoryTests):
    def test_package_untracked_inputs_refused_with_paths(self):
        names = ["tools/rent-auction/wallet.json", "docs/rent-auction/credential.txt",
                 "tools/rent-auction/signed.sqlite"]
        for name in names:
            self.write(name, "local data")
        with patch.object(package, "ROOT", self.root), patch("sys.argv", ["package.py"]):
            with self.assertRaises(RuntimeError) as error:
                package.main()
        for name in names:
            self.assertIn(name, str(error.exception))
        self.assertFalse((self.root / "dist").exists())

    def test_package_ignored_inputs_excluded_and_explicit_files_included(self):
        self.write(".gitignore", "*.secret\n")
        self.write("tools/rent-auction/tracked.py", "pass\n")
        self.baseline()
        self.write("tools/rent-auction/wallet.secret", "private")
        explicit = "src/test/Explicit.scala"
        self.write(explicit, "// explicit\n")
        with patch.object(package, "NEW_FILES", [explicit]):
            package.reject_untracked(self.root)
            self.assertEqual(package.packaged_files(self.root),
                             [".gitignore", explicit, "tools/rent-auction/tracked.py"])


class FingerprintTests(RepositoryTests):
    def setUp(self):
        super().setUp()
        self.doc = "docs/rent-auction/README.md"
        self.write(self.doc, "Specification\n")
        self.write("docs/rent-auction/verification.json", "{}\n")
        for name in verify.REGENERATED_VECTORS:
            self.write(name, '{"old": true}\n')
        self.base = self.baseline()
        for module in (package, verify):
            context = patch.object(module, "ROOT", self.root)
            context.start()
            self.addCleanup(context.stop)
        context = patch.object(package, "NEW_FILES", [])
        context.start()
        self.addCleanup(context.stop)

    def run_verification(self, change_doc=False):
        def run(command, cwd, log):
            if log.name == "node.log":
                for name in ("collect-request.json", "collect-plan.json"):
                    self.write("target/rent-auction-vectors/" + name, '{"fresh": true}\n')
                self.write("target/scala-2.12/ergo-test.jar", "jar bytes")
                return "[info] Tests: succeeded 1, failed 0\n" * 4
            if log.name == "python.log":
                if change_doc:
                    self.write(self.doc, "Changed during verification\n")
                return "Ran 2 tests in 0.01s\nOK\n"
            if log.name == "cli-manifest.log":
                self.write("docs/rent-auction/vectors/mainnet-contracts.json", '{"fresh": true}\n')
            if log.name == "cli-prepare.log":
                log.with_name("collect-plan.json").write_text('{"fresh": true}\n')
            return ""

        with patch("sys.argv", ["verify.py", "--assemble"]), \
                patch.object(verify, "launcher", return_value=["mock-sbt"]), \
                patch.object(verify, "run", side_effect=run), redirect_stdout(io.StringIO()):
            verify.main()
        return json.loads((self.root / "docs/rent-auction/verification.json").read_text())

    def test_fingerprint_regenerated_vectors_tolerated_and_recorded(self):
        initial = verify.source_fingerprint()
        report = self.run_verification()
        self.assertNotEqual(report["sourceFingerprintSha256"], initial)
        self.assertEqual(report["sourceFingerprintSha256"], verify.source_fingerprint())
        for name in verify.REGENERATED_VECTORS:
            self.assertEqual(json.loads((self.root / name).read_text()), {"fresh": True})

    def test_fingerprint_document_changed_during_verification_rejected(self):
        with self.assertRaisesRegex(RuntimeError, "Source changed during verification"):
            self.run_verification(change_doc=True)

    def test_package_document_or_vector_changed_after_verification_rejected(self):
        report = self.run_verification()
        report["lithosTestsPassed"] = 1
        self.write("docs/rent-auction/verification.json", json.dumps(report))
        for name in [self.doc, *sorted(verify.REGENERATED_VECTORS)]:
            with self.subTest(name=name):
                original = (self.root / name).read_text()
                self.write(name, original + " \n")
                with patch.object(package, "BASE", self.base), patch("sys.argv", ["package.py"]):
                    with self.assertRaisesRegex(RuntimeError, "Source changed after verification"):
                        package.main()
                self.write(name, original)

    def test_fingerprint_report_rewritten_does_not_change_digest(self):
        initial = verify.source_fingerprint()
        self.write("docs/rent-auction/verification.json", '{"report": "new"}\n')
        self.assertEqual(verify.source_fingerprint(), initial)

    def test_package_verified_sources_and_vectors_archived(self):
        report = self.run_verification()
        report["lithosTestsPassed"] = 1
        self.write("docs/rent-auction/verification.json", json.dumps(report))
        with patch.object(package, "BASE", self.base), patch("sys.argv", ["package.py"]), \
                redirect_stdout(io.StringIO()):
            package.main()
        with zipfile.ZipFile(self.root / "dist/rent-auction-submission.zip") as archive:
            submitted = {name.removeprefix("source/") for name in archive.namelist()
                         if name.startswith("source/")}
            self.assertEqual(submitted, set(package.packaged_files(self.root)))
            for name in verify.REGENERATED_VECTORS:
                self.assertEqual(json.loads(archive.read("source/" + name)), {"fresh": True})


class LithosTests(RepositoryTests):
    def setUp(self):
        super().setUp()
        self.write("existing.scala", "baseline\n")
        self.write("deleted.scala", "delete me\n")
        self.write("unchanged.scala", "unchanged\n")
        self.write(".gitignore", "*.ignored\n")
        self.base = self.baseline()
        self.write("existing.scala", "submitted\n")
        (self.root / "deleted.scala").unlink()
        diff = self.git("diff", "--binary")
        diff += ("diff --git a/added.scala b/added.scala\nnew file mode 100644\n"
                 "--- /dev/null\n+++ b/added.scala\n@@ -0,0 +1 @@\n+added\n").encode()
        self.write("added.scala", "added\n")
        self.patch_dir = tempfile.TemporaryDirectory()
        self.addCleanup(self.patch_dir.cleanup)
        self.patch_path = Path(self.patch_dir.name) / "submitted.patch"
        self.patch_path.write_bytes(diff)

    def check_tree(self):
        with patch.object(verify, "LITHOS_BASE", self.base):
            verify.verify_lithos_tree(self.root, self.patch_path)

    def test_lithos_exact_patch_and_build_output_accepted_without_changes(self):
        for name in ("target/classes/new.class", "project/target/file", ".bsp/file", ".idea/file",
                     "local.ignored"):
            self.write(name, "build output")
        before = self.git("status", "--porcelain", "--untracked-files=all")
        self.check_tree()
        self.assertEqual(self.git("status", "--porcelain", "--untracked-files=all"), before)
        self.assertEqual((self.root / "existing.scala").read_text(), "submitted\n")

    def test_lithos_changed_and_extra_paths_rejected_with_paths(self):
        self.write("existing.scala", "different\n")
        self.write("added.scala", "different added\n")
        self.write("unchanged.scala", "extra edit\n")
        self.write("extra file.scala", "unsubmitted\n")
        with self.assertRaises(RuntimeError) as error:
            self.check_tree()
        for name in ("existing.scala", "added.scala", "unchanged.scala", "extra file.scala"):
            self.assertIn(name, str(error.exception))

    def test_lithos_missing_patch_changes_rejected(self):
        self.write("existing.scala", "baseline\n")
        self.write("deleted.scala", "delete me\n")
        (self.root / "added.scala").unlink()
        with self.assertRaises(RuntimeError) as error:
            self.check_tree()
        for name in ("existing.scala", "deleted.scala", "added.scala"):
            self.assertIn(name, str(error.exception))

    def test_lithos_staged_extra_path_rejected(self):
        self.write("staged.scala", "unsubmitted\n")
        self.git("add", "staged.scala")
        with self.assertRaisesRegex(RuntimeError, "staged.scala"):
            self.check_tree()

    def test_lithos_patch_rename_with_original_retained_rejected(self):
        rename = ("diff --git a/unchanged.scala b/renamed.scala\n"
                  "similarity index 100%\nrename from unchanged.scala\nrename to renamed.scala\n")
        self.patch_path.write_bytes(self.patch_path.read_bytes() + rename.encode())
        self.write("renamed.scala", "unchanged\n")
        with self.assertRaisesRegex(RuntimeError, "unchanged.scala"):
            self.check_tree()
        (self.root / "unchanged.scala").unlink()
        self.check_tree()

    def test_lithos_wrong_baseline_rejected(self):
        with patch.object(verify, "LITHOS_BASE", "0" * 40):
            with self.assertRaisesRegex(RuntimeError, "documented baseline"):
                verify.verify_lithos_tree(self.root, self.patch_path)


class TestCountTests(unittest.TestCase):
    def test_scala_each_selected_group_requires_positive_count(self):
        commands = ["set Test / parallelExecution := false", "ergoCore/testOnly *RentAuction*Spec",
                    "testOnly *RentAuction*Spec", "ergoWallet/testOnly *ErgoProvingInterpreterSpec",
                    "testOnly *CandidateGeneratorSpec", "assembly"]
        groups = commands[1:-1]
        for i, group in enumerate(groups):
            with self.subTest(group=group):
                counts = [1] * len(groups)
                counts[i] = 0
                text = "\n".join(f"Tests: succeeded {n}, failed 0" for n in counts)
                with self.assertRaises(RuntimeError) as error:
                    verify.scala_test_count(text, commands)
                self.assertIn(group, str(error.exception))
        self.assertEqual(verify.scala_test_count("Tests: succeeded 2\n" * 4, commands), 8)

    def test_scala_missing_or_extra_summaries_rejected(self):
        commands = ["ergoCore/testOnly *FirstSpec", "testOnly *SecondSpec"]
        with self.assertRaisesRegex(RuntimeError, "SecondSpec"):
            verify.scala_test_count("Tests: succeeded 2\n", commands)
        with self.assertRaisesRegex(RuntimeError, "Unexpected test summaries"):
            verify.scala_test_count("Tests: succeeded 2\n" * 3, commands)

    def test_python_zero_or_missing_summary_rejected(self):
        for text in ("Ran 0 tests in 0s\nOK\n", "OK\n"):
            with self.subTest(text=text), self.assertRaisesRegex(RuntimeError, "Python"):
                verify.python_test_count(text)
        self.assertEqual(verify.python_test_count("Ran 3 tests in 0s\nOK\n"), 3)

    def test_lithos_zero_or_missing_summary_rejected(self):
        group = "testOnly transactions.rent.AuctionRentSourceSpec"
        for text in ("Tests: succeeded 0, failed 0", "No tests to run"):
            with self.subTest(text=text), self.assertRaisesRegex(RuntimeError, "AuctionRentSourceSpec"):
                verify.scala_test_count(text, [group])


if __name__ == "__main__":
    unittest.main()
