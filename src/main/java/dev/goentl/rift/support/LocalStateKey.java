package dev.goentl.rift.support;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Base64;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/* Chromium value decryption and Local State key loading. */
final class LocalStateKey {

    private static final Pattern APP_BOUND_KEY =
            Pattern.compile("\"app_bound_encrypted_key\"\\s*:\\s*\"([A-Za-z0-9+/=]{16,})\"");

    private final byte[] masterKey;
    private final byte[] appBoundKey;

    private LocalStateKey(byte[] masterKey, byte[] appBoundKey) {
        this.masterKey = masterKey;
        this.appBoundKey = appBoundKey;
    }

    boolean hasMaster() {
        return masterKey != null;
    }

    boolean hasAppBound() {
        return appBoundKey != null;
    }

    LocalStateKey withAppBound(byte[] key) {
        return new LocalStateKey(masterKey, key != null && key.length == 32 ? key : appBoundKey);
    }

    String describe() {
        return "master=" + (masterKey == null ? "no" : masterKey.length + "B")
                + ", appBound=" + (appBoundKey == null ? "no" : appBoundKey.length + "B");
    }

    boolean usable() {
        return masterKey != null || appBoundKey != null;
    }

    // ------------------------------------------------------------------
    // key loading
    // ------------------------------------------------------------------

    static LocalStateKey fromState(String stateJson, byte[] appBoundKey) {
        byte[] master = null;
        if (stateJson != null) {
            Matcher m = Pattern.compile("\"encrypted_key\"\\s*:\\s*\"([A-Za-z0-9+/=]{16,})\"").matcher(stateJson);
            if (m.find()) {
                try {
                    byte[] raw = Base64.getDecoder().decode(m.group(1));
                    if (raw.length > 5 && "DPAPI".equals(new String(raw, 0, 5, StandardCharsets.US_ASCII))) {
                        raw = Arrays.copyOfRange(raw, 5, raw.length);
                        master = UserProtection.unprotect(raw);
                        if (master != null && master.length != 32) master = null;
                    }
                } catch (Throwable ignored) {}
            }
        }
        byte[] bound = appBoundKey != null && appBoundKey.length == 32 ? appBoundKey : null;
        return new LocalStateKey(master, bound);
    }

    static byte[] appBoundBlob(String stateJson) {
        if (stateJson == null) return null;
        Matcher ab = APP_BOUND_KEY.matcher(stateJson);
        if (!ab.find()) return null;
        try {
            byte[] wrapped = Base64.getDecoder().decode(ab.group(1));
            if (wrapped.length > 4 && "APPB".equals(new String(wrapped, 0, 4, StandardCharsets.US_ASCII))) {
                return Arrays.copyOfRange(wrapped, 4, wrapped.length);
            }
        } catch (Throwable ignored) {}
        return null;
    }

    // ------------------------------------------------------------------
    // decryption
    // ------------------------------------------------------------------

    String password(byte[] blob, String origin) {
        if (blob == null || blob.length == 0) return "";
        String scheme = scheme(blob);
        if (scheme == null) return bytesToText(blob);
        byte[] key = scheme.equals("v20") ? appBoundKey : masterKey;
        if (key == null) return null;
        byte[] plain = gcm(blob, key);
        if (plain == null) return null;
        return bytesToText(stripHostHash(plain, scheme.equals("v20") ? origin : null, scheme.equals("v20")));
    }

    String cookie(byte[] blob, String hostKey) {
        if (blob == null || blob.length == 0) return "";
        String scheme = scheme(blob);
        if (scheme == null) return bytesToText(blob);
        byte[] key = scheme.equals("v20") ? appBoundKey : masterKey;
        if (key == null) return null;
        byte[] plain = gcm(blob, key);
        if (plain == null) return null;
        return bytesToText(stripHostHash(plain, hostKey, scheme.equals("v20")));
    }

    private static byte[] stripHostHash(byte[] plain, String host, boolean mandatory) {
        if (host != null) {
            byte[] expected = sha256Of(host);
            if (plain.length >= 32 && Arrays.equals(Arrays.copyOfRange(plain, 0, 32), expected)) {
                return Arrays.copyOfRange(plain, 32, plain.length);
            }
            if (mandatory && plain.length > 32) {
                // v20 rows without a usable URL column: the prefix is still
                // there, the value is what follows it.
                return Arrays.copyOfRange(plain, 32, plain.length);
            }
            return plain;
        }
        if (mandatory && plain.length > 32) {
            return Arrays.copyOfRange(plain, 32, plain.length);
        }
        return plain;
    }

    // ------------------------------------------------------------------
    // primitives
    // ------------------------------------------------------------------

    String card(byte[] blob) {
        if (blob == null || blob.length == 0) return "";
        String scheme = scheme(blob);
        if (scheme == null) return bytesToText(blob);
        byte[] key = scheme.equals("v20") ? appBoundKey : masterKey;
        byte[] plain = key == null ? null : gcm(blob, key);
        if (plain == null && scheme.equals("v20") && masterKey != null) {
            plain = gcm(blob, masterKey);
        }
        if (plain == null) return null;
        String text = bytesToText(plain);
        return text == null || text.isEmpty() ? null : text;
    }

    static String scheme(byte[] blob) {
        if (blob.length < 3 + 12 + 16) return null;
        if (blob[0] != 'v') return null;
        if (blob[1] == '1' && (blob[2] == '0' || blob[2] == '1')) return new String(blob, 0, 3, StandardCharsets.US_ASCII);
        if (blob[1] == '2' && blob[2] == '0') return "v20";
        return null;
    }

    private static byte[] gcm(byte[] blob, byte[] key) {
        if (key == null || blob.length < 3 + 12 + 16) return null;
        try {
            byte[] nonce = Arrays.copyOfRange(blob, 3, 15);
            byte[] cipherText = Arrays.copyOfRange(blob, 15, blob.length);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, nonce));
            return cipher.doFinal(cipherText);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static String bytesToText(byte[] raw) {
        if (raw == null) return null;
        return new String(raw, StandardCharsets.UTF_8).replace("\u0000", "");
    }

    static byte[] sha256Of(String value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (Throwable t) {
            return new byte[0];
        }
    }
}
