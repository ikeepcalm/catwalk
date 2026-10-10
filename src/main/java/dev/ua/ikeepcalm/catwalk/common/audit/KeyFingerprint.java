package dev.ua.ikeepcalm.catwalk.common.audit;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** Non-reversible short identifier for an API key. The key itself is never logged. */
public final class KeyFingerprint {
    public static final String NONE = "none";
    public static final String INVALID_FORMAT = "invalid-format";

    private KeyFingerprint() {
    }

    /** First 12 hex characters of SHA-256 over the UTF-8 key, or {@link #NONE} for an empty key. */
    public static String of(String key) {
        if (key == null || key.isEmpty()) {
            return NONE;
        }
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(key.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest, 0, 6);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
    }
}
