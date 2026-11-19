package com.ezeebit.wallet.common;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * Fingerprint of an idempotent request's significant fields. Used to detect a client re-using an
 * idempotency key for a different request (which must be rejected, not silently replayed).
 */
public final class RequestHash {

    private RequestHash() {
    }

    public static String of(Object... parts) {
        StringBuilder sb = new StringBuilder();
        for (Object p : parts) {
            String s = String.valueOf(p);
            // length-prefix each part so ("ab","c") and ("a","bc") hash differently
            sb.append(s.length()).append(':').append(s).append('|');
        }
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(sb.toString().getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
