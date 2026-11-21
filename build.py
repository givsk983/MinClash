import argparse
from dataclasses import dataclass
from io import BytesIO
from pathlib import Path
import hashlib
import os
import re
import shutil
import struct
import subprocess
import urllib.request
import zipfile
from build_core import build_mihomo


ROOT = Path(__file__).resolve().parent
BUILD = ROOT / "build"
DOWNLOADS = BUILD / "downloads"
DIST = ROOT / "dist"
SNAKEYAML = "2.5"
YAML_JAR = f"snakeyaml-{SNAKEYAML}.jar"
RELEASE_CERT_SHA256 = "8c7b06c0539fc7cab859a13f82836465de2359d2a4dd45ccac5804c35e5f5ef1"


@dataclass(frozen=True)
class ModuleArchive:
    data: bytes
    digest: str
    properties: str

    def add_to(self, apk):
        apk.writestr("assets/minclash-module.zip", self.data, zipfile.ZIP_STORED)
        apk.writestr("assets/minclash-module.sha256", self.digest + "\n")
        apk.writestr("assets/minclash-module.properties", self.properties)


def read_module_archive(path, expected_sha256=None):
    # Keep the verified bytes so packaging cannot read a different archive later.
    data = path.read_bytes()
    digest = hashlib.sha256(data).hexdigest()
    if expected_sha256 is not None and digest != expected_sha256.lower():
        raise RuntimeError(f"SHA-256 mismatch for {path}: expected {expected_sha256.lower()}, got {digest}")

    try:
        with zipfile.ZipFile(BytesIO(data)) as archive:
            if archive.namelist().count("module.prop") != 1:
                raise RuntimeError(f"Expected exactly one module.prop in {path}.")
            properties_text = archive.read("module.prop").decode("utf-8")
    except (zipfile.BadZipFile, UnicodeDecodeError) as error:
        raise RuntimeError(f"Invalid module ZIP or module.prop: {path}") from error

    metadata_keys = ("version", "versionCode", "revision")
    properties = {}
    for line in properties_text.splitlines():
        key, _, value = line.partition("=")
        if key in ("id", *metadata_keys):
            if key in properties:
                raise RuntimeError(f"Duplicate {key} in {path} module.prop.")
            properties[key] = value
    if properties.get("id") != "minclash":
        raise RuntimeError(f"Expected a MinClash module ZIP: {path}")
    version = properties.get("version", "")
    if not re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9._+\-]*", version):
        raise RuntimeError(f"Invalid module version in {path}.")
    if version in ("0.0.1", "0.0.2"):
        properties.setdefault("revision", "1")
    for key in ("versionCode", "revision"):
        value = properties.get(key, "")
        if not re.fullmatch(r"[0-9]{1,10}", value) or not 0 < int(value) <= 2_147_483_647:
            raise RuntimeError(f"Invalid module {key} in {path}.")
    return ModuleArchive(data, digest, "".join(f"{key}={properties[key]}\n" for key in metadata_keys))


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


def reset_compilation_directory(name):
    directory = (BUILD / name).resolve()
    if (name not in ("classes", "generated") or directory.parent != BUILD.resolve()
            or not directory.is_relative_to(ROOT.resolve())):
        raise RuntimeError("Compilation output must stay directly inside the build directory.")
    if directory.exists():
        shutil.rmtree(directory)
    directory.mkdir()
    return directory


def build_apk(yaml_jar, apache_license, module_archive, keystore, release):
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
    classes = reset_compilation_directory("classes")
    generated = reset_compilation_directory("generated")
    resources = BUILD / "res.zip"
    execute(sdk_tool("aapt2"), "compile", "--dir", ROOT / "app/res", "-o", resources)
    base = BUILD / "unsigned.apk"
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
        module_archive.add_to(target)

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


def ndk_clang():
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
    return clang


def build_socket_request():
    clang = ndk_clang()
    binary = BUILD / "socket-request"
    execute(clang, "--target=aarch64-linux-android28", "-O2", "-Wall", "-Wextra",
            "-Wl,-z,max-page-size=16384", "-s", ROOT / "module-src/socket-request.c", "-o", binary)
    return binary


def build_module(core, license_path):
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
    parser.add_argument("--module-zip", type=Path, help="Build only the APK using an existing MinClash module ZIP.")
    parser.add_argument("--module-sha256", help="Expected SHA-256 of the ZIP supplied with --module-zip.")
    options = parser.parse_args()
    if (options.module_zip is None) != (options.module_sha256 is None):
        parser.error("--module-zip and --module-sha256 must be supplied together.")
    if options.module_sha256 is not None and not re.fullmatch(r"[0-9a-fA-F]{64}", options.module_sha256):
        parser.error("--module-sha256 must contain 64 hexadecimal digits.")
    keystore = Path(os.environ.get("MINCLASH_KEYSTORE", BUILD / "debug.keystore")).resolve()
    if options.release and not keystore.is_file():
        raise RuntimeError("Restore the MinClash signing keystore or set MINCLASH_KEYSTORE before building a release.")
    module_archive = None
    if options.module_zip is not None:
        module_archive = read_module_archive(options.module_zip, options.module_sha256)
    DOWNLOADS.mkdir(parents=True, exist_ok=True)
    DIST.mkdir(exist_ok=True)
    yaml_jar = download(YAML_JAR, f"https://repo.maven.apache.org/maven2/org/yaml/snakeyaml/{SNAKEYAML}/{YAML_JAR}",
                        "e6682acf1ace77508ef13649cbf4f8d09d2cf5457bdb61d25ffb6ac0233d78dd")
    apache_license = download("apache-2.0-LICENSE", "https://www.apache.org/licenses/LICENSE-2.0.txt",
                              "cfc7749b96f63bd31c3c42b5c471bf756814053e847c10f3eb003417bc523d30")
    module_path = None
    if module_archive is None:
        core, mihomo_license, core_source = build_mihomo(download, ndk_clang())
        module_path = build_module(core, mihomo_license)
        module_archive = read_module_archive(module_path)
    build_apk(yaml_jar, apache_license, module_archive, keystore, options.release)
    if module_path is not None:
        (DIST / module_path.name).write_bytes(module_archive.data)
        shutil.copyfile(core_source, DIST / core_source.name)
    apk = DIST / "MinClash.apk"
    print(f"APK (module included): {apk}\nSHA-256: {sha256(apk)}")


if __name__ == "__main__":
    main()
