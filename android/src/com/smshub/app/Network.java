package com.smshub.app;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import javax.net.ssl.HttpsURLConnection;
import org.json.JSONObject;

final class Network {
    static final class ApiException extends Exception {
        final int status;
        ApiException(int status, String message) { super(message); this.status = status; }
    }
    static String normalizeUrl(String text) throws Exception {
        URL url = new URL(text.trim());
        if (!"https".equals(url.getProtocol()) || url.getHost().isEmpty() || url.getUserInfo() != null || url.getQuery() != null || url.getRef() != null
            || !(url.getPath().isEmpty() || "/".equals(url.getPath()))) throw new Exception("请输入网站根地址，例如 https://sms.example.com");
        return "https://" + url.getAuthority();
    }
    static JSONObject post(String root, String path, String token, JSONObject body) throws Exception {
        HttpsURLConnection connection = (HttpsURLConnection) new URL(normalizeUrl(root) + path).openConnection();
        // Do not follow redirects: bearer tokens must stay at the paired server.
        connection.setInstanceFollowRedirects(false);
        connection.setConnectTimeout(3000); connection.setReadTimeout(4000);
        connection.setRequestMethod("POST"); connection.setDoOutput(true);
        connection.setRequestProperty("Content-Type", "application/json");
        if (token != null) connection.setRequestProperty("Authorization", "Bearer " + token);
        byte[] payload = body.toString().getBytes(StandardCharsets.UTF_8);
        connection.setFixedLengthStreamingMode(payload.length);
        try {
            try (OutputStream stream = connection.getOutputStream()) { stream.write(payload); }
            int status = connection.getResponseCode();
            if (status >= 300 && status < 400) throw new ApiException(status, "服务器发生跳转，请使用最终 HTTPS 地址重新配对");
            InputStream input = status >= 400 ? connection.getErrorStream() : connection.getInputStream();
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            if (input != null) try (InputStream stream = input) {
                byte[] chunk = new byte[4096]; int length;
                while ((length = stream.read(chunk)) != -1) { buffer.write(chunk, 0, length); if (buffer.size() > 100000) throw new Exception("服务器响应过大"); }
            }
            JSONObject result;
            try { result = new JSONObject(new String(buffer.toByteArray(), StandardCharsets.UTF_8)); }
            catch (Exception error) { throw new ApiException(status, "服务器返回了无法识别的内容，请检查地址"); }
            if (status < 200 || status >= 300) throw new ApiException(status, result.optString("error", "服务器请求失败"));
            return result;
        } finally { connection.disconnect(); }
    }
}
