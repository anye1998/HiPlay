#!/usr/bin/env python3
"""Fetch the pinned HiChain engine sources into the hicar-auth module.

The engine is third-party C (Apache-2.0) and is never committed, for the same reason the native
build output is not: `git log` on this repository should stay about this repository. This script is
the single definition of what the APK is built against, so a reviewer can reproduce it exactly.

    python3 scripts/fetch-hichain-sources.py            # fetch what is missing
    python3 scripts/fetch-hichain-sources.py --verify    # report only, change nothing
    python3 scripts/fetch-hichain-sources.py --clean     # delete the fetched tree

Pins:
  deviceauth  openharmony/security_deviceauth                      @ 552edb5
  huks        openharmony/security_huks                            @ 89f0842
  securec     openharmony/third_party_bounds_checking_function    @ ebef58f
  mbedtls     Mbed-TLS/mbedtls v2.28.9 (sha256-pinned archive)
  cjson       DaveGamble/cJSON v1.7.19 (deviceauth_lite's JSON layer)

Only the subtrees the engine needs are copied; the rest of each repository is not.
"""
from __future__ import annotations

import argparse
import hashlib
import shutil
import subprocess
import sys
import tarfile
import tempfile
import urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
TARGET = ROOT / "hicar-auth" / "src" / "main" / "cpp" / "third_party"

# name -> (repository, commit, subtrees to keep)
GIT_SOURCES: dict[str, tuple[str, str, tuple[str, ...]]] = {
    "deviceauth": (
        "https://github.com/openharmony/security_deviceauth.git",
        "552edb521d0bd7c17f2dab2791e7cdc3de284405",
        ("frameworks/deviceauth_lite", "interfaces/innerkits/deviceauth_lite"),
    ),
    "huks": (
        "https://github.com/openharmony/security_huks.git",
        "89f0842ef2600a0d9b5351ef11e1f8acab8ff439",
        ("frameworks/huks_lite", "interfaces/innerkits/huks_lite"),
    ),
    "securec": (
        "https://github.com/openharmony/third_party_bounds_checking_function.git",
        "ebef58f44419d0694e984d25acac231e737d60ff",
        ("include", "src"),
    ),
}

# cJSON is a hard dependency of deviceauth_lite's JSON layer (jsonutil.c includes cJSON.h).
CJSON_TAG = "v1.7.19"
CJSON_SHA256 = "7fa616e3046edfa7a28a32d5f9eacfd23f92900fe1f8ccd988c1662f30454562"
CJSON_URL = f"https://codeload.github.com/DaveGamble/cJSON/tar.gz/refs/tags/{CJSON_TAG}"

MBEDTLS_TAG = "v2.28.9"
MBEDTLS_SHA256 = "e4dbcf86a4fb31506482888560f02b161e0ecfb82fee0643abcfc86abee5817e"
MBEDTLS_URL = f"https://codeload.github.com/Mbed-TLS/mbedtls/tar.gz/refs/tags/{MBEDTLS_TAG}"
MBEDTLS_KEEP = ("library", "include", "LICENSE")


def run(args: list[str], cwd: Path | None = None) -> None:
    subprocess.run(args, cwd=cwd, check=True, stdout=subprocess.DEVNULL, stderr=subprocess.PIPE)


def git_source(name: str, repository: str, commit: str, subtrees: tuple[str, ...]) -> None:
    marker = TARGET / name / ".commit"
    if marker.is_file() and marker.read_text().strip() == commit:
        print(f"{name}: already at {commit[:8]}")
        return
    with tempfile.TemporaryDirectory(prefix=f"fetch-{name}-") as temporary:
        checkout = Path(temporary) / name
        print(f"{name}: fetching {commit[:8]} …")
        run(["git", "init", "-q", str(checkout)])
        run(["git", "-C", str(checkout), "remote", "add", "origin", repository])
        try:
            run(["git", "-C", str(checkout), "fetch", "-q", "--depth", "1", "origin", commit])
            run(["git", "-C", str(checkout), "checkout", "-q", "--detach", "FETCH_HEAD"])
        except subprocess.CalledProcessError as failure:
            detail = failure.stderr.decode(errors="replace").strip().splitlines()
            raise SystemExit(
                f"{name}: cannot fetch {commit} from {repository}\n  " + (detail[-1] if detail else "no output")
            )
        found = subprocess.check_output(["git", "-C", str(checkout), "rev-parse", "HEAD"], text=True).strip()
        if found != commit:
            raise SystemExit(f"{name}: fetched {found}, expected {commit}")
        destination = TARGET / name
        if destination.exists():
            shutil.rmtree(destination)
        for subtree in subtrees:
            source = checkout / subtree
            if not source.is_dir():
                raise SystemExit(f"{name}: upstream has no {subtree}")
            shutil.copytree(source, destination / subtree)
        (destination / ".commit").write_text(commit + "\n")
        licence = next(
            (checkout / candidate for candidate in ("LICENSE", "LICENSE.txt", "COPYING") if (checkout / candidate).is_file()),
            None,
        )
        if licence is not None:
            shutil.copy2(licence, destination / "LICENSE")
        print(f"{name}: {commit[:8]} ready")


def mbedtls() -> None:
    marker = TARGET / "mbedtls" / ".archive-sha256"
    if marker.is_file() and marker.read_text().strip() == MBEDTLS_SHA256:
        print("mbedtls: already at " + MBEDTLS_TAG)
        return
    with tempfile.TemporaryDirectory(prefix="fetch-mbedtls-") as temporary:
        archive = Path(temporary) / "mbedtls.tar.gz"
        print(f"mbedtls: downloading {MBEDTLS_TAG} …")
        with urllib.request.urlopen(MBEDTLS_URL, timeout=240) as response, archive.open("wb") as out:
            shutil.copyfileobj(response, out)
        digest = hashlib.sha256(archive.read_bytes()).hexdigest()
        if digest != MBEDTLS_SHA256:
            raise SystemExit(f"mbedtls: archive sha256 {digest} does not match the pin; refusing to extract")
        with tarfile.open(archive) as tar:
            tar.extractall(temporary, filter="data")
        source = Path(temporary) / f"mbedtls-{MBEDTLS_TAG.lstrip('v')}"
        if not source.is_dir():
            raise SystemExit("mbedtls: unexpected archive layout")
        destination = TARGET / "mbedtls"
        if destination.exists():
            shutil.rmtree(destination)
        destination.mkdir(parents=True)
        for name in MBEDTLS_KEEP:
            if (source / name).is_dir():
                shutil.copytree(source / name, destination / name)
            else:
                shutil.copy2(source / name, destination / name)
        (destination / ".archive-sha256").write_text(MBEDTLS_SHA256 + "\n")
        print(f"mbedtls: {MBEDTLS_TAG} ready")


def cjson() -> None:
    marker = TARGET / "cjson" / ".archive-sha256"
    if marker.is_file() and marker.read_text().strip() == CJSON_SHA256:
        print("cjson: already at " + CJSON_TAG)
        return
    with tempfile.TemporaryDirectory(prefix="fetch-cjson-") as temporary:
        archive = Path(temporary) / "cjson.tar.gz"
        print(f"cjson: downloading {CJSON_TAG} …")
        with urllib.request.urlopen(CJSON_URL, timeout=240) as response, archive.open("wb") as out:
            shutil.copyfileobj(response, out)
        digest = hashlib.sha256(archive.read_bytes()).hexdigest()
        if digest != CJSON_SHA256:
            raise SystemExit(f"cjson: archive sha256 {digest} does not match the pin; refusing to extract")
        with tarfile.open(archive) as tar:
            tar.extractall(temporary, filter="data")
        source = Path(temporary) / f"cJSON-{CJSON_TAG.lstrip('v')}"
        if not (source / "cJSON.c").is_file():
            raise SystemExit("cjson: unexpected archive layout")
        destination = TARGET / "cjson"
        if destination.exists():
            shutil.rmtree(destination)
        destination.mkdir(parents=True)
        for name in ("cJSON.c", "cJSON.h", "LICENSE"):
            shutil.copy2(source / name, destination / name)
        (destination / ".archive-sha256").write_text(CJSON_SHA256 + "\n")
        print(f"cjson: {CJSON_TAG} ready")


def verify() -> int:
    problems = []
    for name, (_, commit, _) in GIT_SOURCES.items():
        marker = TARGET / name / ".commit"
        if not marker.is_file():
            problems.append(f"{name}: not fetched")
        elif marker.read_text().strip() != commit:
            problems.append(f"{name}: marker is not {commit}")
    for name, digest in (("mbedtls", MBEDTLS_SHA256), ("cjson", CJSON_SHA256)):
        marker = TARGET / name / ".archive-sha256"
        if not marker.is_file():
            problems.append(f"{name}: not fetched")
        elif marker.read_text().strip() != digest:
            problems.append(f"{name}: marker is not the pinned archive")
    for problem in problems:
        print(problem, file=sys.stderr)
    print("HiChain sources: " + ("ok" if not problems else f"{len(problems)} problem(s)"))
    return 0 if not problems else 1


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--verify", action="store_true", help="check the fetched tree without changing it")
    parser.add_argument("--clean", action="store_true", help="delete the fetched tree")
    arguments = parser.parse_args()

    if arguments.clean:
        if TARGET.exists():
            shutil.rmtree(TARGET)
            print(f"removed {TARGET}")
        return 0
    if arguments.verify:
        return verify()

    TARGET.mkdir(parents=True, exist_ok=True)
    for name, (repository, commit, subtrees) in GIT_SOURCES.items():
        git_source(name, repository, commit, subtrees)
    mbedtls()
    cjson()
    print(f"HiChain sources ready in {TARGET}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
