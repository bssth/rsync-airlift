#!/usr/bin/env python3
"""Build a signed APT tree. Requires Linux dpkg-deb, apt-ftparchive and GnuPG."""
import argparse
import gzip
import hashlib
from pathlib import Path
import re
import shutil
import subprocess


def run(*command, cwd=None):
    return subprocess.check_output(command, cwd=cwd)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--packages", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--sign-key", required=True, help="full signing key fingerprint")
    parser.add_argument("--passphrase-file", type=Path, required=True)
    args = parser.parse_args()
    if not re.fullmatch(r"[A-Fa-f0-9]{40}|[A-Fa-f0-9]{64}", args.sign_key):
        parser.error("provide the full GPG fingerprint")
    packages = sorted(args.packages.glob("*.deb"))
    if not packages:
        parser.error("no .deb packages found")
    output = args.output.resolve()
    if output.exists() and any(output.iterdir()):
        parser.error("output must be empty (existing files are never removed)")
    pool = output / "pool/main/r/rsync-airlift"
    pool.mkdir(parents=True, exist_ok=True)
    for package in packages:
        name = run("dpkg-deb", "-f", str(package), "Package").decode().strip()
        arch = run("dpkg-deb", "-f", str(package), "Architecture").decode().strip()
        if name != "rsync-airlift" or arch != "amd64":
            parser.error(f"unexpected package identity: {package.name}")
        shutil.copyfile(package, pool / package.name)
    suite = output / "dists/stable"
    index = suite / "main/binary-amd64"
    index.mkdir(parents=True)
    content = run("apt-ftparchive", "packages", "pool", cwd=output)
    (index / "Packages").write_bytes(content)
    (index / "Packages.gz").write_bytes(gzip.compress(content, mtime=0))
    # Content-addressed indexes avoid stale-index races during a deployment.
    for path in (index / "Packages", index / "Packages.gz"):
        by_hash = index / "by-hash/SHA256" / hashlib.sha256(path.read_bytes()).hexdigest()
        by_hash.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(path, by_hash)
    release = run("apt-ftparchive",
                  "-o", "APT::FTPArchive::Release::Origin=Rsync Airlift",
                  "-o", "APT::FTPArchive::Release::Label=Rsync Airlift",
                  "-o", "APT::FTPArchive::Release::Suite=stable",
                  "-o", "APT::FTPArchive::Release::Codename=stable",
                  "-o", "APT::FTPArchive::Release::Architectures=amd64",
                  "-o", "APT::FTPArchive::Release::Components=main",
                  "-o", "APT::FTPArchive::Release::Acquire-By-Hash=yes",
                  "release", "dists/stable", cwd=output)
    (suite / "Release").write_bytes(release)
    sign = ["gpg", "--batch", "--yes", "--pinentry-mode", "loopback",
            "--passphrase-file", str(args.passphrase_file.resolve()),
            "--local-user", args.sign_key, "--digest-algo", "SHA256"]
    subprocess.run(sign + ["--output", str(suite / "InRelease"), "--clearsign", str(suite / "Release")], check=True)
    subprocess.run(sign + ["--output", str(suite / "Release.gpg"), "--detach-sign", str(suite / "Release")], check=True)
    public = run("gpg", "--batch", "--export-options", "export-minimal", "--export", args.sign_key)
    if not public:
        parser.error("signing public key is empty")
    keyring = output / "rsync-airlift-archive-keyring.gpg"
    keyring.write_bytes(public)
    subprocess.run(["gpgv", "--keyring", str(keyring), str(suite / "InRelease")], check=True)
    subprocess.run(["gpgv", "--keyring", str(keyring), str(suite / "Release.gpg"), str(suite / "Release")], check=True)
    (output / ".nojekyll").touch()
    (output / "index.html").write_text(
        '<!doctype html><meta charset="utf-8"><title>Rsync Airlift APT</title>'
        '<h1>Rsync Airlift APT repository</h1><p>Debian/Ubuntu amd64.</p>'
        '<p>Installation and signing key setup: '
        '<a href="https://github.com/iris4iris/rsync-airlift/blob/main/docs/APT.md">instructions</a>.</p>\n', encoding="utf-8")
    print(f"Signed repository: {output}")


if __name__ == "__main__":
    main()
