package com.live_jgv.notification.infrastructure.jdbc;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.UUID;

/** Opaque, scoped checkpoint, not an authentication token. */
public record AlertCursor(UUID epoch, String matchId, long sequence) {
    public String encode() {
        String scope = Base64.getUrlEncoder().withoutPadding().encodeToString(matchId.getBytes(StandardCharsets.UTF_8));
        return Base64.getUrlEncoder().withoutPadding().encodeToString(
                ("1|" + epoch + "|" + scope + "|" + sequence).getBytes(StandardCharsets.UTF_8));
    }
    public static AlertCursor decode(String token, String matchId) {
        try {
            if (token == null || token.length() > 4096) throw new IllegalArgumentException();
            String[] fields = new String(Base64.getUrlDecoder().decode(token), StandardCharsets.UTF_8).split("\\|", -1);
            if (fields.length != 4 || !fields[0].equals("1")) throw new IllegalArgumentException();
            var cursor = new AlertCursor(UUID.fromString(fields[1]),
                    new String(Base64.getUrlDecoder().decode(fields[2]), StandardCharsets.UTF_8), Long.parseLong(fields[3]));
            if (cursor.sequence < 0 || !cursor.matchId.equals(matchId) || !cursor.encode().equals(token)) throw new IllegalArgumentException();
            return cursor;
        } catch (RuntimeException invalid) { throw new IllegalArgumentException("Invalid or mismatched alert cursor"); }
    }
    public static final class StaleCursorException extends RuntimeException { }
}
