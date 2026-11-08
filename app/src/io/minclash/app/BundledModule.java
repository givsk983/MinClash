package io.minclash.app;

import android.content.Context;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Properties;

final class BundledModule {
    static final String ASSET = "minclash-module.zip";

    record Info(String version, int versionCode, int revision) {
        boolean valid() { return !version.isEmpty() && versionCode > 0 && revision > 0; }
    }

    static Info info(Context context) throws IOException {
        Properties values = new Properties();
        try (InputStream input = context.getAssets().open("minclash-module.properties")) {
            values.load(input);
        }
        try {
            Info info = new Info(values.getProperty("version", ""),
                Integer.parseInt(values.getProperty("versionCode", "0")),
                Integer.parseInt(values.getProperty("revision", "0")));
            if (!info.valid()) throw new IOException("内置模块版本信息无效。");
            return info;
        } catch (NumberFormatException error) { throw new IOException("内置模块版本信息无效。", error); }
    }

    static void copy(Context context, OutputStream output) throws IOException {
        try (InputStream input = context.getAssets().open(ASSET)) {
            byte[] buffer = new byte[65536];
            int count;
            while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
        }
    }

    static void install(Context context) throws Exception {
        File archive = File.createTempFile("minclash-module-", ".zip", context.getCacheDir());
        try {
            try (OutputStream output = new FileOutputStream(archive)) { copy(context, output); }
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream input = new java.io.FileInputStream(archive)) {
                byte[] buffer = new byte[65536];
                int count;
                while ((count = input.read(buffer)) != -1) digest.update(buffer, 0, count);
            }
            StringBuilder actual = new StringBuilder();
            for (byte value : digest.digest()) actual.append(String.format(java.util.Locale.ROOT, "%02x", value & 255));
            byte[] checksum = new byte[128];
            String expected;
            try (InputStream input = context.getAssets().open("minclash-module.sha256")) {
                int count = input.read(checksum);
                if (count <= 0) throw new IOException("内置模块校验信息缺失。");
                expected = new String(checksum, 0, count, StandardCharsets.US_ASCII).trim();
            }
            if (!actual.toString().equals(expected)) throw new IOException("内置模块校验失败，请重新安装 App。");
            String action = "umask 077; "
                + "stage=$(mktemp -d /data/local/tmp/minclash-install.XXXXXX) || exit 1; "
                + "trap 'rm -rf \"$stage\"' EXIT; "
                + "cp " + quote(archive.getAbsolutePath()) + " \"$stage/module.zip\" || exit 1; "
                + "if [ -x /data/adb/ksu/bin/ksud ]; then "
                + "/data/adb/ksu/bin/ksud module install \"$stage/module.zip\"; "
                + "elif command -v magisk >/dev/null 2>&1; then "
                + "magisk --install-module \"$stage/module.zip\"; "
                + "else echo '未找到 KernelSU / Magisk 安装服务。可导出模块后手动安装。'; exit 1; fi";
            Module.runRoot(action, null, null, 180);
        } finally {
            archive.delete();
        }
    }

    private static String quote(String value) { return "'" + value.replace("'", "'\\''") + "'"; }
    private BundledModule() {}
}
