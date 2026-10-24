"""Filesystem/command-boundary checks; real APT/GPG verification runs in Actions."""
import gzip
import hashlib
import importlib.util
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import patch

spec = importlib.util.spec_from_file_location("apt_repo", Path(__file__).with_name("build_apt_repository.py"))
repo = importlib.util.module_from_spec(spec)
spec.loader.exec_module(repo)


class RepositoryTest(unittest.TestCase):
    def invoke(self, packages, output, fingerprint="A" * 40):
        with patch.object(sys, "argv", ["builder", "--packages", str(packages), "--output", str(output),
                                       "--sign-key", fingerprint, "--passphrase-file", str(output.parent / "pass")]):
            repo.main()

    def test_layout_checksums_and_signature_commands(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            packages = root / "packages"
            packages.mkdir()
            (packages / "airlift.deb").write_bytes(b"test package")
            index = b"Package: rsync-airlift\nFilename: pool/main/r/rsync-airlift/airlift.deb\n\n"

            def external(*cmd, cwd=None):
                if cmd[0] == "dpkg-deb":
                    return b"amd64\n" if cmd[-1] == "Architecture" else b"rsync-airlift\n"
                if cmd[:2] == ("apt-ftparchive", "packages"):
                    self.assertEqual((cwd / "pool/main/r/rsync-airlift/airlift.deb").read_bytes(), b"test package")
                    return index
                if cmd[0] == "apt-ftparchive":
                    return b"Suite: stable\n"
                if cmd[0] == "gpg":
                    return b"public key only"
                raise AssertionError(cmd)

            out = root / "public"
            with patch.object(repo, "run", side_effect=external), patch.object(repo.subprocess, "run") as commands:
                self.invoke(packages, out)
            p = out / "dists/stable/main/binary-amd64/Packages"
            self.assertEqual(p.read_bytes(), index)
            self.assertEqual(gzip.decompress(p.with_name("Packages.gz").read_bytes()), index)
            for name in ("Packages", "Packages.gz"):
                data = p.with_name(name).read_bytes()
                self.assertEqual((p.parent / "by-hash/SHA256" / hashlib.sha256(data).hexdigest()).read_bytes(), data)
            self.assertEqual((out / "rsync-airlift-archive-keyring.gpg").read_bytes(), b"public key only")
            self.assertTrue((out / ".nojekyll").exists())
            self.assertEqual(len(commands.call_args_list), 4)  # two signatures and two verifications
            self.assertEqual(commands.call_args_list[-1].args[0][0], "gpgv")

    def test_invalid_fingerprint_rejected_before_external_commands(self):
        with patch.object(repo, "run") as external, self.assertRaises(SystemExit):
            self.invoke(Path("absent"), Path("unused"), "short-key-id")
        external.assert_not_called()

    def test_existing_output_is_not_overwritten(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            (root / "airlift.deb").touch()
            out = root / "public"
            out.mkdir()
            protected = out / "keep"
            protected.write_text("preserved")
            with self.assertRaises(SystemExit):
                self.invoke(root, out)
            self.assertEqual(protected.read_text(), "preserved")


if __name__ == "__main__":
    unittest.main()
