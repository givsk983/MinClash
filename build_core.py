"""Build the pinned Mihomo source with MinClash's small TCP keepalive overlay."""
from pathlib import Path
import hashlib
import os
import shutil
import subprocess
import tarfile

ROOT = Path(__file__).resolve().parent
BUILD = ROOT / "build"
COMMIT = "3f27ef39f8c96bbfbc8b0b9bb10f5c9ef60149d8"
SOURCE_SHA256 = "ecda5f8c8a8a1d60bc088c2640c85d92c8f002c0686234a26bdf671735afe216"
VENDOR_SHA256 = "7ac4d4da0e6a29ef6ed18d2cf8f4c58d361d049ae364fd841acc883aa3a9e1cc"
VERSION = "v1.19.33-minclash.1"
GO_VERSION = "go1.26.11"


def extract_source(archive, destination, prefix):
    with tarfile.open(archive) as source:
        for member in source:
            if member.name.rstrip("/") == prefix.rstrip("/"):
                continue
            if not member.name.startswith(prefix) or not (member.isfile() or member.isdir()):
                raise RuntimeError(f"Unexpected source archive entry: {member.name}")
            target = (destination / member.name[len(prefix):]).resolve()
            if not target.is_relative_to(destination):
                raise RuntimeError("Source archive path escapes build directory.")
            if member.isdir():
                target.mkdir(parents=True, exist_ok=True)
            else:
                target.parent.mkdir(parents=True, exist_ok=True)
                with source.extractfile(member) as stream, target.open("wb") as output:
                    shutil.copyfileobj(stream, output)


def prepare_source(download):
    archive = download(f"mihomo-{COMMIT}.tar.gz",
                       f"https://codeload.github.com/MetaCubeX/mihomo/tar.gz/{COMMIT}", SOURCE_SHA256)
    vendor = download("mihomo-v1.19.33-vendor.tar.gz",
                      "https://github.com/MetaCubeX/mihomo/releases/download/v1.19.33/vendor.tar.gz", VENDOR_SHA256)
    destination = (BUILD / "mihomo-source").resolve()
    if destination.parent != BUILD.resolve() or not destination.is_relative_to(ROOT):
        raise RuntimeError("Mihomo source must remain in the project's build directory.")
    if destination.exists():
        shutil.rmtree(destination)
    destination.mkdir()
    extract_source(archive, destination, f"mihomo-{COMMIT}/")
    extract_source(vendor, destination / "vendor", "vendor/")
    for overlay in sorted((ROOT / "mihomo-patches").rglob("*.go")):
        target = destination / overlay.relative_to(ROOT / "mihomo-patches")
        target.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(overlay, target)
    shutil.copyfile(ROOT / "mihomo-patches/README.md", destination / "MINCLASH-BUILD.md")
    return destination


def go_environment():
    bundled = BUILD / "toolchains/go/bin" / ("go.exe" if os.name == "nt" else "go")
    executable = os.environ.get("MINCLASH_GO") or shutil.which("go") or (str(bundled) if bundled.is_file() else None)
    if not executable:
        raise RuntimeError(f"Install {GO_VERSION} or set MINCLASH_GO to its executable. See mihomo-patches/README.md.")
    environment = dict(os.environ, GOTOOLCHAIN="local", GOENV="off", GOWORK="off",
                       GOCACHE=str(BUILD / "go-cache"), GOMODCACHE=str(BUILD / "go-mod"), GOPATH=str(BUILD / "go"))
    version = subprocess.check_output([executable, "version"], env=environment, text=True).split()
    if len(version) < 3 or version[2] != GO_VERSION:
        raise RuntimeError(f"Expected {GO_VERSION}, received {' '.join(version)}")
    return executable, environment


def build_mihomo(download, clang):
    source = prepare_source(download)
    go, environment = go_environment()
    # Host tests verify opt-in, subscription reloads, all TCP entry points and a real socket.
    host_environment = dict(environment, CGO_ENABLED="0")
    for key in ("GOOS", "GOARCH", "GOARM", "GOAMD64"):
        host_environment.pop(key, None)
    subprocess.run([go, "test", "-mod=vendor", "./component/keepalive", "-count=1"], cwd=source, env=host_environment, check=True)
    binary = BUILD / "mihomo-arm64"
    environment.update(GOOS="android", GOARCH="arm64", CGO_ENABLED="1",
                       CC=f'"{clang}" --target=aarch64-linux-android28',
                       CGO_LDFLAGS="-Wl,-z,max-page-size=16384")
    flags = (f"-X github.com/metacubex/mihomo/constant.Version={VERSION} "
             "-X github.com/metacubex/mihomo/constant.BuildTime=2026-11-14T00:00:00Z -w -s -buildid=")
    subprocess.run([go, "build", "-mod=vendor", "-tags", "with_gvisor", "-trimpath", "-ldflags", flags,
                    "-o", str(binary), "."], cwd=source, env=environment, check=True)
    # Supply the complete patched core source together with the local artifacts.
    source_archive = BUILD / f"mihomo-{VERSION}-source.tar.gz"
    with tarfile.open(source_archive, "w:gz") as archive:
        archive.add(source, arcname=f"mihomo-{VERSION}")
    print(f"Patched Mihomo SHA-256: {hashlib.sha256(binary.read_bytes()).hexdigest()}", flush=True)
    return binary, source / "LICENSE", source_archive
