package com.smshub.app;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Conservative local extraction: uncertain codes remain visible in the original message. */
public final class Otp {
    private static final Pattern KEYWORD = Pattern.compile("验证码|校验码|动态码|验证代码|安全码|(?i)verification\\s+code|security\\s+code|one[ -]time|passcode|\\botp\\b|\\bcode\\b");
    private static final Pattern CODE = Pattern.compile("(?<![A-Za-z0-9])([A-Za-z0-9]{4,8})(?![A-Za-z0-9])");
    public static boolean isVerification(String body) { return KEYWORD.matcher(body).find(); }
    public static String extract(String body) {
        Matcher keyword = KEYWORD.matcher(body);
        int bestDistance = Integer.MAX_VALUE;
        String best = "";
        boolean tied = false;
        while (keyword.find()) {
            Matcher code = CODE.matcher(body);
            while (code.find()) {
                String candidate = code.group(1);
                if (!candidate.matches(".*[0-9].*")) continue;
                // URLs, phone numbers and unrelated amounts should not become codes.
                int distance = code.start() >= keyword.end() ? code.start() - keyword.end() : keyword.start() - code.end();
                if (distance < 0 || distance > 32) continue;
                String between = body.substring(Math.min(keyword.end(), code.end()), Math.max(keyword.start(), code.start()));
                if (between.contains("http") || between.contains("分钟") || between.contains("元")) continue;
                if (distance < bestDistance) { bestDistance = distance; best = candidate; tied = false; }
                else if (distance == bestDistance && !best.equals(candidate)) tied = true;
            }
        }
        return tied ? "" : best;
    }
    private Otp() {}
}
