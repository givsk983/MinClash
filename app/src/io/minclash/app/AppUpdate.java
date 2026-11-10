package io.minclash.app;

import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageInstaller;
import android.content.pm.PackageManager;
import android.os.SystemClock;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Locale;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;

final class AppUpdate {
    private static final String REPOSITORY = "/givsk983/MinClash";
    private static final String RELEASES = "https://github.com" + REPOSITORY + "/releases";
    private static final String LATEST = "https://api.github.com/repos" + REPOSITORY + "/releases/latest";
    private static final long APK_LIMIT = 64L * 1024 * 1024;
    private static final List<String> DOWNLOAD_HOSTS = Arrays.asList("github.com", "release-assets.githubusercontent.com",
        "objects.githubusercontent.com", "github-releases.githubusercontent.com");

    record Release(String version, String address, long size, String digest, String notes) implements java.io.Serializable {}
    private static final class RateLimited extends IOException {}

    static Release check(String currentVersion) throws Exception {
        ByteArrayOutputStream json = new ByteArrayOutputStream();
        try {
            try {
                transfer(new URL(LATEST), Collections.singletonList("api.github.com"), json, 512 * 1024, null);
            } catch (RateLimited error) {
                return checkReleaseRedirect(currentVersion);
            }
        } catch (IOException error) {
            throw new IOException("无法检查 App 更新，请确认当前网络可以访问 GitHub。\n" + error.getMessage(), error);
        }
        return parseRelease(new JSONObject(json.toString(StandardCharsets.UTF_8.name())), currentVersion);
    }

    private static Release checkReleaseRedirect(String currentVersion) throws IOException {
        HttpURLConnection connection = connect(new URL(RELEASES + "/latest"));
        try {
            int code = connection.getResponseCode();
            if (code != 302) throw new IOException("GitHub 发布页返回 HTTP " + code + "。");
            String target = connection.getHeaderField("Location");
            String prefix = RELEASES + "/tag/";
            if (target == null || !target.startsWith(prefix)) throw new IOException("GitHub 发布地址无效。");
            String tag = target.substring(prefix.length());
            if (compareVersions(tag, currentVersion) <= 0) return null;
            String version = tag.startsWith("v") ? tag.substring(1) : tag;
            return new Release(version, RELEASES + "/download/" + tag + "/MinClash.apk", -1, "", "");
        } finally {
            connection.disconnect();
        }
    }

    static Release parseRelease(JSONObject release, String currentVersion) throws Exception {
        String tag = release.getString("tag_name");
        if (release.optBoolean("draft") || release.optBoolean("prerelease") || compareVersions(tag, currentVersion) <= 0) {
            return null;
        }
        String version = tag.startsWith("v") ? tag.substring(1) : tag;
        JSONArray assets = release.getJSONArray("assets");
        JSONObject apk = null;
        for (int i = 0; i < assets.length(); i++) {
            JSONObject asset = assets.getJSONObject(i);
            if (!asset.optString("state").equals("uploaded")) continue;
            String name = asset.optString("name");
            if (name.equals("MinClash.apk")) {
                apk = asset;
                break;
            }
            if (name.equals("MinClash-v" + version + ".apk")) apk = asset;
        }
        if (apk == null) throw new IOException("GitHub 已发布 " + version + "，APK 附件尚不可下载。");
        String address = apk.getString("browser_download_url");
        String expected = "https://github.com" + REPOSITORY + "/releases/download/" + tag + "/" + apk.getString("name");
        long size = apk.getLong("size");
        String digest = apk.isNull("digest") ? "" : apk.optString("digest");
        if (!address.equals(expected) || size <= 0 || size > APK_LIMIT
            || !digest.isEmpty() && !digest.matches("sha256:[0-9a-f]{64}")) {
            throw new IOException("GitHub 发布的 APK 信息无效。");
        }
        String notes = release.optString("body", "").trim();
        if (notes.length() > 3000) notes = notes.substring(0, 3000) + "…";
        return new Release(version, address, size, digest, notes);
    }

    static int compareVersions(String left, String right) throws IOException {
        String pattern = "v?[0-9]{1,6}\\.[0-9]{1,6}\\.[0-9]{1,6}";
        if (!left.matches(pattern) || !right.matches(pattern)) throw new IOException("App 版本号格式无效。");
        String[] first = left.replaceFirst("^v", "").split("\\.");
        String[] second = right.replaceFirst("^v", "").split("\\.");
        for (int i = 0; i < first.length; i++) {
            int compare = Integer.compare(Integer.parseInt(first[i]), Integer.parseInt(second[i]));
            if (compare != 0) return compare;
        }
        return 0;
    }

    static void downloadAndInstall(Context context, Release release) throws Exception {
        File apk = File.createTempFile("minclash-update-", ".apk", context.getCacheDir());
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (OutputStream output = new FileOutputStream(apk)) {
                long limit = release.size() > 0 ? release.size() : APK_LIMIT;
                long length = transfer(new URL(release.address()), DOWNLOAD_HOSTS, output, limit, digest);
                if (length == 0 || release.size() > 0 && length != release.size()) {
                    throw new IOException("APK 下载不完整，请重试。");
                }
            }
            if (!release.digest().isEmpty()) {
                StringBuilder actual = new StringBuilder("sha256:");
                for (byte value : digest.digest()) actual.append(String.format(Locale.ROOT, "%02x", value & 255));
                if (!release.digest().contentEquals(actual)) throw new IOException("APK 校验失败，请重新下载。");
            }
            PackageManager manager = context.getPackageManager();
            PackageInfo archive = manager.getPackageArchiveInfo(apk.getAbsolutePath(), 0);
            PackageInfo current = manager.getPackageInfo(context.getPackageName(), 0);
            if (archive == null || !current.packageName.equals(archive.packageName)
                || !release.version().equals(archive.versionName) || archive.getLongVersionCode() <= current.getLongVersionCode()) {
                throw new IOException("下载的 APK 与发布版本不匹配，或版本代码未递增。");
            }
            install(context, apk);
        } finally {
            apk.delete();
        }
    }

    private static void install(Context context, File apk) throws IOException {
        PackageInstaller installer = context.getPackageManager().getPackageInstaller();
        PackageInstaller.SessionParams params = new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
        params.setAppPackageName(context.getPackageName());
        params.setSize(apk.length());
        if (android.os.Build.VERSION.SDK_INT >= 31) {
            params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_REQUIRED);
        }
        int id = installer.createSession(params);
        boolean committed = false;
        try (PackageInstaller.Session session = installer.openSession(id)) {
            try (InputStream input = new FileInputStream(apk); OutputStream output = session.openWrite("base.apk", 0, apk.length())) {
                byte[] buffer = new byte[65536];
                int count;
                while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
                session.fsync(output);
            }
            int flags = android.os.Build.VERSION.SDK_INT >= 31 ? PendingIntent.FLAG_MUTABLE : 0;
            PendingIntent callback = PendingIntent.getBroadcast(context, id,
                new Intent(context, UpdateInstallReceiver.class), flags);
            session.commit(callback.getIntentSender());
            committed = true;
        } finally {
            if (!committed) installer.abandonSession(id);
        }
    }

    private static long transfer(URL url, List<String> hosts, OutputStream output, long limit, MessageDigest digest)
        throws IOException {
        long deadline = SystemClock.elapsedRealtime() + 5 * 60_000L;
        for (int redirects = 0; redirects <= 5; redirects++) {
            if (!url.getProtocol().equals("https") || !hosts.contains(url.getHost()) || url.getUserInfo() != null
                || url.getPort() != -1 && url.getPort() != 443) throw new IOException("更新下载地址无效。");
            HttpURLConnection connection = connect(url);
            try {
                boolean api = url.getHost().equals("api.github.com");
                if (api) connection.setRequestProperty("Accept", "application/vnd.github+json");
                int code = connection.getResponseCode();
                if (code == 301 || code == 302 || code == 303 || code == 307 || code == 308) {
                    String location = connection.getHeaderField("Location");
                    if (location == null) throw new IOException("更新下载重定向无效。");
                    url = new URL(url, location);
                    continue;
                }
                if (api && (code == 403 || code == 429)) throw new RateLimited();
                if (code == 403 || code == 429) throw new IOException("GitHub 暂时限制请求，请稍后重试。");
                if (code != 200) throw new IOException("GitHub 返回 HTTP " + code + "。");
                if (connection.getContentLengthLong() > limit) throw new IOException("更新数据超过预期大小。");
                try (InputStream input = connection.getInputStream()) {
                    long total = 0;
                    byte[] buffer = new byte[65536];
                    int count;
                    while ((count = input.read(buffer)) != -1) {
                        if (SystemClock.elapsedRealtime() > deadline) throw new IOException("更新下载超时，请重试。");
                        total += count;
                        if (total > limit) throw new IOException("更新数据超过预期大小。");
                        output.write(buffer, 0, count);
                        if (digest != null) digest.update(buffer, 0, count);
                    }
                    return total;
                }
            } finally {
                connection.disconnect();
            }
        }
        throw new IOException("更新下载重定向过多。");
    }

    private static HttpURLConnection connect(URL url) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        connection.setConnectTimeout(15000);
        connection.setReadTimeout(20000);
        connection.setInstanceFollowRedirects(false);
        connection.setRequestProperty("User-Agent", "MinClash");
        return connection;
    }

    private AppUpdate() {}
}
