package dev.ua.ikeepcalm.catwalk.common.utils;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.HexFormat;

public class QueueSignature {

    public static final String SIGNATURE_HEADER = "X-CatWalk-Signature";
    public static final String TIMESTAMP_HEADER = "X-CatWalk-Timestamp";

    public static String sign(String key, String... parts) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            for (String part : parts) {
                byte[] bytes = (part != null ? part : "").getBytes(StandardCharsets.UTF_8);
                mac.update((bytes.length + ":").getBytes(StandardCharsets.UTF_8));
                mac.update(bytes);
            }
            return HexFormat.of().formatHex(mac.doFinal());
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HmacSHA256 is not available", e);
        }
    }

    public static boolean verify(String key, String signature, String... parts) {
        return signature != null && MessageDigest.isEqual(
                sign(key, parts).getBytes(StandardCharsets.UTF_8), signature.getBytes(StandardCharsets.UTF_8));
    }
}
