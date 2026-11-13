package io.minclash.app;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class ControlResponse {
    private static final Pattern STATUS = Pattern.compile("HTTP/1\\.[01] ([0-9]{3})(?:[ \\t].*)?");
    static String body(String reply, boolean missingOkay) throws IOException {
        int headerEnd = reply.indexOf("\r\n\r\n"), lineEnd = reply.indexOf("\r\n");
        if (headerEnd < 0 || lineEnd < 0 || lineEnd > headerEnd) throw new IOException("控制接口响应不完整，请刷新后重试。");
        Matcher status = STATUS.matcher(reply.substring(0, lineEnd));
        if (!status.matches()) throw new IOException("无法识别控制接口响应。");
        int code = Integer.parseInt(status.group(1));
        if (missingOkay && (code == 404 || code == 410)) return "";
        if (code != 200 && code != 204) {
            String reason = code == 401 || code == 403 ? "控制接口拒绝授权。"
                : code == 404 ? "当前内核不支持此操作。" : code == 409 ? "连接状态已变化，请刷新后重试。"
                : code >= 500 ? "内核暂时无法处理请求，请稍后重试。" : "内核拒绝了操作，请刷新后重试。";
            throw new IOException(reason);
        }
        String body = reply.substring(headerEnd + 4);
        String headers = lineEnd == headerEnd ? "" : reply.substring(lineEnd + 2, headerEnd);
        for (String header : headers.split("\r\n")) {
            int colon = header.indexOf(':');
            if (colon > 0 && header.substring(0, colon).trim().equalsIgnoreCase("Content-Length")) {
                try {
                    long expected = Long.parseLong(header.substring(colon + 1).trim());
                    if (expected < 0 || expected != body.getBytes(StandardCharsets.UTF_8).length) throw new NumberFormatException();
                } catch (NumberFormatException truncated) { throw new IOException("控制接口数据不完整，请重新刷新。"); }
            }
        }
        return body;
    }
    private ControlResponse() {}
}
