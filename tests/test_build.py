import contextlib
import hashlib
import io
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
import zipfile

import build


class ModuleBuildCheck(unittest.TestCase):
    def setUp(self):
        build_directory = build.ROOT / "build"
        temporary = tempfile.TemporaryDirectory(dir=build_directory if build_directory.is_dir() else None)
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name)
        self.module = self.root / "MinClash-module.zip"

    def make_module(self, props="id=minclash\nversion=0.1.2\nversionCode=21\nrevision=11\n"):
        with zipfile.ZipFile(self.module, "w") as archive:
            archive.writestr("module.prop", props)
            archive.writestr("control.sh", "#!/system/bin/sh\n")
        return self.module.read_bytes()

    def test_app_only_embeds_verified_bytes_and_archive_metadata(self):
        data = self.make_module()
        digest = hashlib.sha256(data).hexdigest()
        output = self.root / "dist"

        def fake_build_apk(yaml_jar, license_path, module_archive, keystore, release):
            self.module.write_bytes(b"changed after verification")
            with zipfile.ZipFile(output / "MinClash.apk", "w") as apk:
                module_archive.add_to(apk)

        with (
            patch.multiple(build, DOWNLOADS=self.root / "downloads", DIST=output),
            patch.object(build, "download"),
            patch.object(build, "build_apk", side_effect=fake_build_apk),
            patch.object(build, "ndk_clang", side_effect=AssertionError("App-only build asked for NDK")),
            patch.object(build, "build_mihomo", side_effect=AssertionError("App-only build rebuilt Mihomo")),
            patch.object(build, "build_module", side_effect=AssertionError("App-only build repackaged module")),
            patch("sys.argv", ["build.py", "--module-zip", str(self.module), "--module-sha256", digest.upper()]),
            contextlib.redirect_stdout(io.StringIO()),
        ):
            build.main()

        self.assertEqual([entry.name for entry in output.iterdir()], ["MinClash.apk"])
        with zipfile.ZipFile(output / "MinClash.apk") as apk:
            self.assertEqual(apk.read("assets/minclash-module.zip"), data)
            self.assertEqual(apk.read("assets/minclash-module.sha256"), (digest + "\n").encode())
            self.assertEqual(apk.read("assets/minclash-module.properties"), b"version=0.1.2\nversionCode=21\nrevision=11\n")

    def test_rejects_incomplete_malformed_or_mismatched_checksum_before_build(self):
        self.make_module()
        output = self.root / "dist"
        for args in (
            ["--module-zip", str(self.module)],
            ["--module-sha256", "0" * 64],
            ["--module-zip", str(self.module), "--module-sha256", "x" * 64],
        ):
            with self.subTest(args=args), patch("sys.argv", ["build.py", *args]), contextlib.redirect_stderr(io.StringIO()):
                with self.assertRaises(SystemExit) as error:
                    build.main()
                self.assertEqual(error.exception.code, 2)
        with (
            patch.multiple(build, DOWNLOADS=self.root / "downloads", DIST=output),
            patch.object(build, "download", side_effect=AssertionError("Bad digest led to downloads")),
            patch("sys.argv", ["build.py", "--module-zip", str(self.module), "--module-sha256", "0" * 64]),
            self.assertRaisesRegex(RuntimeError, "SHA-256 mismatch"),
        ):
            build.main()
        self.assertFalse(output.exists())

    def test_legacy_revision_and_invalid_module_metadata(self):
        self.make_module("id=minclash\r\nversion=0.0.1\r\nversionCode=10\r\n")
        self.assertEqual(build.read_module_archive(self.module).properties, "version=0.0.1\nversionCode=10\nrevision=1\n")
        for props in (
            "id=other\nversion=0.1.2\nversionCode=21\nrevision=11\n",
            "id=minclash\nversion=0.1.2\nversionCode=21\n",
            "id=minclash\nversion=0.1.2\nversionCode=21\nrevision=11\nrevision=12\n",
        ):
            with self.subTest(props=props):
                self.make_module(props)
                with self.assertRaises(RuntimeError):
                    build.read_module_archive(self.module)

    def test_default_build_emits_mihomo_source_and_same_module_used_for_apk(self):
        data = self.make_module()
        source = self.root / "mihomo-source.tar.gz"
        source.write_bytes(b"core source")
        output = self.root / "dist"

        def fake_build_apk(yaml_jar, license_path, module_archive, keystore, release):
            with zipfile.ZipFile(output / "MinClash.apk", "w") as apk:
                module_archive.add_to(apk)

        with (
            patch.multiple(build, DOWNLOADS=self.root / "downloads", DIST=output),
            patch.object(build, "download"),
            patch.object(build, "build_apk", side_effect=fake_build_apk),
            patch.object(build, "ndk_clang"),
            patch.object(build, "build_mihomo", return_value=(None, None, source)) as core_builder,
            patch.object(build, "build_module", return_value=self.module) as module_builder,
            patch("sys.argv", ["build.py"]),
            contextlib.redirect_stdout(io.StringIO()),
        ):
            build.main()

        core_builder.assert_called_once()
        module_builder.assert_called_once()
        self.assertEqual((output / source.name).read_bytes(), source.read_bytes())
        self.assertEqual((output / self.module.name).read_bytes(), data)
        with zipfile.ZipFile(output / "MinClash.apk") as apk:
            self.assertEqual(apk.read("assets/minclash-module.zip"), data)


if __name__ == "__main__":
    unittest.main()
