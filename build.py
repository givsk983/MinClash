import argparse
from pathlib import Path
import gzip
import hashlib
import os
import shutil
import struct
import subprocess
import urllib.request
import zipfile


ROOT = Path(__file__).resolve().parent
BUILD = ROOT / "build"
DOWNLOADS = BUILD / "downloads"
DIST = ROOT / "dist"
MIHOMO = "v1.19.33"
SNAKEYAML = "2.5"
ANDROID_CORE = f"mihomo-android-arm64-v8-{MIHOMO}.gz"
YAML_JAR = f"snakeyaml-{SNAKEYAML}.jar"
RELEASE_CERT_SHA256 = "8c7b06c0539fc7cab859a13f82836465de2359d2a4dd45ccac5804c35e5f5ef1"


def sha256(path):
    with path.open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def download(name, url, expected):
    path = DOWNLOADS / name
    if not path.exists():
        print(f"Downloading {name}", flush=True)
        partial = path.with_suffix(path.suffix + ".partial")
        with urllib.request.urlopen(url, timeout=90) as source, partial.open("wb") as target:
            shutil.copyfileobj(source, target)
        if sha256(partial) != expected:
            partial.unlink()
            raise RuntimeError(f"SHA-256 mismatch for {name}")
        partial.replace(path)
    elif sha256(path) != expected:
        raise RuntimeError(f"SHA-256 mismatch for {path}. Replace the cached file with the official download.")
    return path


def execute(*args):
    subprocess.run([str(arg) for arg in args], cwd=ROOT, check=True)


def jdk_tool(name):
    if home := os.environ.get("JAVA_HOME"):
        return Path(home) / "bin" / (name + (".exe" if os.name == "nt" else ""))
    if executable := shutil.which(name):
        return executable
    raise RuntimeError("Set JAVA_HOME to a JDK 17 or newer.")


def build_apk(yaml_jar, apache_license, module_archive, keystore, release):
    properties = dict(line.split("=", 1) for line in
                      (ROOT / "module/module.prop").read_text(encoding="utf-8").splitlines() if "=" in line)
    if not properties.get("version") or int(properties.get("versionCode", "0")) <= 0 or int(properties.get("revision", "0")) <= 0:
        raise RuntimeError("Module version, versionCode and functional revision must be configured.")
    sdk_path = os.environ.get("ANDROID_HOME") or os.environ.get("ANDROID_SDK_ROOT")
    if not sdk_path and os.name == "nt":
        sdk_path = Path(os.environ["LOCALAPPDATA"]) / "Android" / "Sdk"
    if not sdk_path:
        raise RuntimeError("Set ANDROID_HOME to your Android SDK.")
    sdk = Path(sdk_path)
    android_jar = sdk / "platforms" / "android-36" / "android.jar"
    tools = sdk / "build-tools" / "36.0.0"
    if not android_jar.is_file() or not tools.is_dir():
        raise RuntimeError("Install Android SDK Platform 36 and Build Tools 36.0.0.")

    def sdk_tool(name):
        return tools / (name + (".exe" if os.name == "nt" else ""))

    java = jdk_tool("java")
    classes = BUILD / "classes"
    classes.mkdir(exist_ok=True)
    resources = BUILD / "res.zip"
    execute(sdk_tool("aapt2"), "compile", "--dir", ROOT / "app/res", "-o", resources)
    base = BUILD / "unsigned.apk"
    generated = BUILD / "generated"
    execute(sdk_tool("aapt2"), "link", "-I", android_jar, "--manifest",
            ROOT / "app/AndroidManifest.xml", "--java", generated, "-o", base, resources)
    sources = sorted((ROOT / "app/src").rglob("*.java")) + sorted(generated.rglob("R.java"))
    execute(jdk_tool("javac"), "-encoding", "UTF-8", "--release", "17", "-classpath",
            os.pathsep.join(map(str, (android_jar, yaml_jar))), "-d", classes, *sources)

    # Keep only the Java 8 classes from SnakeYAML's multi-release JAR.
    dex_input = BUILD / "dex-input.jar"
    with zipfile.ZipFile(dex_input, "w", zipfile.ZIP_DEFLATED) as target:
        for compiled in sorted(classes.rglob("*.class")):
            target.write(compiled, compiled.relative_to(classes).as_posix())
        with zipfile.ZipFile(yaml_jar) as dependency:
            for name in dependency.namelist():
                if name.startswith("org/yaml/snakeyaml/") and name.endswith(".class"):
                    target.writestr(name, dependency.read(name))

    dex = BUILD / "dex.zip"
    execute(java, "-cp", tools / "lib/d8.jar", "com.android.tools.r8.D8", "--min-api", "28",
            "--lib", android_jar, "--output", dex, dex_input)
    with zipfile.ZipFile(dex) as compiled, zipfile.ZipFile(base, "a") as target:
        for name in compiled.namelist():
            target.writestr(name, compiled.read(name))
        target.write(apache_license, "assets/licenses/snakeyaml-LICENSE.txt", zipfile.ZIP_DEFLATED)
        target.write(module_archive, "assets/minclash-module.zip", zipfile.ZIP_STORED)
        target.writestr("assets/minclash-module.sha256", sha256(module_archive) + "\n")
        target.writestr("assets/minclash-module.properties", "".join(
            key + "=" + properties[key] + "\n" for key in ("version", "versionCode", "revision")))

    aligned = BUILD / "aligned.apk"
    execute(sdk_tool("zipalign"), "-f", "4", base, aligned)
    if not keystore.exists():
        execute(jdk_tool("keytool"), "-genkeypair", "-noprompt", "-keystore", keystore,
                "-storepass", "android", "-keypass", "android", "-alias", "minclashdebug", "-keyalg", "RSA",
                "-keysize", "2048", "-validity", "10000", "-dname", "CN=MinClash Local Debug")
    signer = tools / "lib/apksigner.jar"
    apk = BUILD / "signed.apk"
    execute(java, "-jar", signer, "sign", "--ks", keystore, "--ks-pass", "pass:android",
            "--ks-key-alias", "minclashdebug", "--v2-signing-enabled", "true",
            "--v4-signing-enabled", "false", "--out", apk, aligned)
    verified = subprocess.check_output(
        [str(java), "-jar", str(signer), "verify", "--verbose", "--print-certs", str(apk)], text=True)
    print(verified, end="")
    if release and f"Signer #1 certificate SHA-256 digest: {RELEASE_CERT_SHA256}" not in verified.splitlines():
        raise RuntimeError("The signing certificate differs from the published MinClash APK.")
    apk.replace(DIST / "MinClash.apk")


def build_socket_request():
    ndk_path = os.environ.get("ANDROID_NDK_HOME")
    if not ndk_path:
        sdk_path = os.environ.get("ANDROID_HOME") or os.environ.get("ANDROID_SDK_ROOT")
        if not sdk_path and os.name == "nt":
            sdk_path = Path(os.environ["LOCALAPPDATA"]) / "Android" / "Sdk"
        if not sdk_path:
            raise RuntimeError("Set ANDROID_NDK_HOME to your Android NDK.")
        ndk_path = Path(sdk_path) / "ndk" / "27.0.12077973"
    prebuilt = Path(ndk_path) / "toolchains" / "llvm" / "prebuilt"
    clang_name = "clang.exe" if os.name == "nt" else "clang"
    clang = next(prebuilt.glob(f"*/bin/{clang_name}"), None)
    if clang is None:
        raise RuntimeError("Install Android NDK 27.0.12077973 or set ANDROID_NDK_HOME.")
    binary = BUILD / "socket-request"
    execute(clang, "--target=aarch64-linux-android28", "-O2", "-Wall", "-Wextra",
            "-Wl,-z,max-page-size=16384", "-s", ROOT / "module-src/socket-request.c", "-o", binary)
    return binary


def build_module(archive, license_path):
    core = BUILD / "mihomo-arm64"
    with gzip.open(archive) as source, core.open("wb") as target:
        shutil.copyfileobj(source, target)
    with core.open("rb") as source:
        elf = source.read(64)
    if elf[:6] != b"\x7fELF\x02\x01" or struct.unpack_from("<H", elf, 18)[0] != 183:
        raise RuntimeError("Expected a little-endian ARM64 ELF for the Magisk module.")

    def add(target, source, path, permissions=0o644):
        info = zipfile.ZipInfo(path)
        info.create_system = 3
        info.external_attr = (0o100000 | permissions) << 16
        info.compress_type = zipfile.ZIP_DEFLATED
        with source.open("rb") as file, target.open(info, "w") as entry:
            shutil.copyfileobj(file, entry)

    version = next(line.removeprefix("version=") for line in
                   (ROOT / "module/module.prop").read_text(encoding="utf-8").splitlines()
                   if line.startswith("version="))
    socket_request = build_socket_request()
    module_archive = BUILD / f"MinClash-Root-arm64-v{version}.zip"
    with zipfile.ZipFile(module_archive, "w") as target:
        for source in sorted((ROOT / "module").iterdir()):
            if source.suffix == ".sh" and b"\r" in source.read_bytes():
                raise RuntimeError(f"Use LF line endings in {source}")
            add(target, source, source.name, 0o755 if source.suffix == ".sh" else 0o644)
        add(target, core, "bin/mihomo", 0o755)
        add(target, socket_request, "bin/socket-request", 0o755)
        add(target, license_path, "LICENSE-mihomo.txt")
    return module_archive


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--release", action="store_true", help="Require the published APK signing certificate.")
    options = parser.parse_args()
    keystore = Path(os.environ.get("MINCLASH_KEYSTORE", BUILD / "debug.keystore")).resolve()
    if options.release and not keystore.is_file():
        raise RuntimeError("Restore the MinClash signing keystore or set MINCLASH_KEYSTORE before building a release.")
    DOWNLOADS.mkdir(parents=True, exist_ok=True)
    DIST.mkdir(exist_ok=True)
    archive = download(ANDROID_CORE, f"https://github.com/MetaCubeX/mihomo/releases/download/{MIHOMO}/{ANDROID_CORE}",
                       "2ddbd3aaa884f52b4568f406876ec81dddeef9f1bb67b90d4004d356e2c6a708")
    mihomo_license = download("mihomo-LICENSE", f"https://raw.githubusercontent.com/MetaCubeX/mihomo/{MIHOMO}/LICENSE",
                              "3972dc9744f6499f0f9b2dbf76696f2ae7ad8af9b23dde66d6af86c9dfb36986")
    yaml_jar = download(YAML_JAR, f"https://repo.maven.apache.org/maven2/org/yaml/snakeyaml/{SNAKEYAML}/{YAML_JAR}",
                        "e6682acf1ace77508ef13649cbf4f8d09d2cf5457bdb61d25ffb6ac0233d78dd")
    apache_license = download("apache-2.0-LICENSE", "https://www.apache.org/licenses/LICENSE-2.0.txt",
                              "cfc7749b96f63bd31c3c42b5c471bf756814053e847c10f3eb003417bc523d30")
    module_archive = build_module(archive, mihomo_license)
    build_apk(yaml_jar, apache_license, module_archive, keystore, options.release)
    apk = DIST / "MinClash.apk"
    print(f"APK (module included): {apk}\nSHA-256: {sha256(apk)}")


if __name__ == "__main__":
    main()
