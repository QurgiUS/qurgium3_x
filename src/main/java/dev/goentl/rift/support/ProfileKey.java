package dev.goentl.rift.support;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/* Firefox decryption once key4.db has been read. */
final class ProfileKey {

    static final String OID_AES256_CBC = "60864801650304012A";
    static final String OID_DES_EDE3_CBC = "2A864886F70D0307";

    private final byte[] aesKey;

    private ProfileKey(byte[] aesKey) {
        this.aesKey = aesKey;
    }

    boolean usable() {
        return aesKey != null && aesKey.length == 32;
    }

    String fingerprint() {
        if (aesKey == null) return "none";
        return String.format("%02X%02X%02X%02X", aesKey[0], aesKey[1], aesKey[2], aesKey[3]);
    }

    // ------------------------------------------------------------------
    // key4.db unwrap
    // ------------------------------------------------------------------

    static byte[] unwrap(ProfileDatabase.Pbe pbe, byte[] globalSalt) {
        if (pbe == null || globalSalt == null) return null;
        try {
            byte[] password = sha1(globalSalt, new byte[0]);
            byte[] derived = pbkdf2(password, pbe.salt(), pbe.iterations(), pbe.keyLength(), pbe.prf());
            if (derived.length != 32) return null;
            if (pbe.iv().length != 16) return null;
            Cipher cipher = Cipher.getInstance("AES/CBC/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(derived, "AES"), new IvParameterSpec(pbe.iv()));
            byte[] raw = cipher.doFinal(pbe.ciphertext());
            if (raw.length == 0) return null;
            int pad = raw[raw.length - 1] & 0xFF;
            if (pad < 1 || pad > 16) return null;
            for (int i = raw.length - pad; i < raw.length; i++) {
                if ((raw[i] & 0xFF) != pad) return null;
            }
            return raw;
        } catch (Throwable ignored) {
            return null;
        }
    }

    static ProfileKey fromPrivateKey(byte[] plaintext) {
        if (plaintext == null) return null;
        byte[] key = stripPadding(plaintext);
        if (key.length == 32) return new ProfileKey(key);
        // A few stores wrap the key in a PKCS#8 envelope instead of writing it
        // bare; the 32 byte OCTET STRING payload is the same either way.
        byte[] inner = innerKey(key);
        return inner != null && inner.length == 32 ? new ProfileKey(inner) : null;
    }

    static boolean isPasswordCheck(byte[] plaintext) {
        if (plaintext == null) return false;
        return Arrays.equals(plaintext, ProfileDatabase.PASSWORD_CHECK)
                || Arrays.equals(stripPadding(plaintext), "password-check".getBytes(StandardCharsets.ISO_8859_1));
    }

    private static byte[] stripPadding(byte[] data) {
        if (data.length == 0) return data;
        int pad = data[data.length - 1] & 0xFF;
        if (pad < 1 || pad > 16 || pad > data.length) return data;
        for (int i = data.length - pad; i < data.length; i++) {
            if ((data[i] & 0xFF) != pad) return data;
        }
        return Arrays.copyOf(data, data.length - pad);
    }

    private static byte[] innerKey(byte[] pkcs8) {
        if (pkcs8 == null) return null;
        try {
            Cursor c = new Cursor(pkcs8);
            c.tag(0x30);
            c.len();
            if (c.peek() == 0x02) c.skip(c.len() + 1);
            if (c.peek() == 0x30) c.skip(c.len() + 1);
            c.tag(0x04);
            return c.take(c.len());
        } catch (Throwable ignored) {
            return null;
        }
    }

    static String fieldError;

    // ------------------------------------------------------------------
    // logins.json
    // ------------------------------------------------------------------

    String field(String base64) {
        if (base64 == null || base64.isEmpty() || aesKey == null) return null;
        try {
            byte[] der = Base64.getDecoder().decode(base64);
            Cursor c = new Cursor(der);
            c.tag(0x30);
            c.len();
            c.tag(0x04);                       // key id
            c.skip(c.len());
            c.tag(0x30);
            c.len();
            c.tag(0x06);
            String oid = hex(c.take(c.len()));
            if (!oid.equals(OID_AES256_CBC)) return null;
            c.tag(0x04);
            byte[] iv = c.take(c.len());
            c.tag(0x04);
            byte[] cipherText = c.take(c.len());

            Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(aesKey, "AES"), new IvParameterSpec(iv));
            return new String(cipher.doFinal(cipherText), StandardCharsets.UTF_8).replace("\u0000", "");
        } catch (Throwable t) {
            fieldError = t.getClass().getSimpleName() + ": " + t.getMessage();
            return null;
        }
    }

    List<String[]> logins(String json) {
        List<String[]> out = new ArrayList<>();
        Matcher host = Pattern.compile("\"hostname\"\\s*:\\s*\"([^\"]*)\"").matcher(json);
        int cursor = 0;
        while (host.find(cursor)) {
            cursor = host.end();
            String hostName = host.group(1);
            Matcher next = Pattern.compile("\"hostname\"\\s*:").matcher(json);
            int windowEnd = next.find(cursor) ? next.start() : json.length();

            String entry = json.substring(host.end(), windowEnd);
            String action = firstString(entry, "formSubmitURL");
            String user = field(firstString(entry, "encryptedUsername"));
            String pass = field(firstString(entry, "encryptedPassword"));
            if (user == null && pass == null) continue;
            out.add(new String[]{
                    hostName,
                    user == null ? "[locked]" : user,
                    pass == null ? "[locked]" : pass,
                    action == null || action.isEmpty() ? hostName : action});
        }
        return out;
    }

    private static String firstString(String json, String key) {
        Matcher m = Pattern.compile("\"" + Pattern.quote(key) + "\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"").matcher(json);
        return m.find() ? m.group(1) : null;
    }

    // ------------------------------------------------------------------
    // primitives
    // ------------------------------------------------------------------

    static byte[] sha1(byte[] a, byte[] b) throws Exception {
        MessageDigest d = MessageDigest.getInstance("SHA-1");
        d.update(a);
        d.update(b);
        return d.digest();
    }

    static byte[] pbkdf2(byte[] password, byte[] salt, int iterations, int length, String prf) throws Exception {
        String algorithm = prf == null || prf.isEmpty() ? "HmacSHA1" : prf;
        Mac mac = Mac.getInstance(algorithm);
        mac.init(new SecretKeySpec(password, algorithm));
        int hashLen = mac.getMacLength();
        byte[] out = new byte[length];
        int pos = 0;
        for (int block = 1; pos < length; block++) {
            mac.reset();
            mac.update(salt);
            mac.update(new byte[]{(byte) (block >>> 24), (byte) (block >>> 16),
                    (byte) (block >>> 8), (byte) block});
            byte[] u = mac.doFinal();
            byte[] t = u.clone();
            for (int i = 1; i < iterations; i++) {
                u = mac.doFinal(u);
                for (int j = 0; j < t.length; j++) t[j] ^= u[j];
            }
            int take = Math.min(hashLen, length - pos);
            System.arraycopy(t, 0, out, pos, take);
            pos += take;
        }
        return out;
    }

    static String prfFor(String oid) {
        switch (oid) {
            case "2A864886F70D0207": return "HmacSHA1";
            case "2A864886F70D0208": return "HmacSHA224";
            case "2A864886F70D0209": return "HmacSHA256";
            case "2A864886F70D020A": return "HmacSHA384";
            case "2A864886F70D020B": return "HmacSHA512";
            default: return "HmacSHA1";
        }
    }

    static String hex(byte[] b) {
        StringBuilder s = new StringBuilder(b.length * 2);
        for (byte x : b) s.append(String.format("%02X", x));
        return s.toString();
    }

    static final class Cursor {
        private final byte[] b;
        int pos;

        Cursor(byte[] b) {
            this.b = b;
        }

        int peek() {
            return pos < b.length ? (b[pos] & 0xFF) : -1;
        }

        void tag(int expected) {
            if (pos >= b.length) throw new IllegalStateException("eof");
            int actual = b[pos++] & 0xFF;
            if (actual != expected) throw new IllegalStateException("tag " + actual + " != " + expected);
        }

        int len() {
            if (pos >= b.length) throw new IllegalStateException("eof");
            int first = b[pos++] & 0xFF;
            if ((first & 0x80) == 0) return first;
            int n = first & 0x7F;
            if (n == 0 || n > 2) throw new IllegalStateException("bad length");
            int v = 0;
            for (int i = 0; i < n; i++) {
                if (pos >= b.length) throw new IllegalStateException("eof");
                v = (v << 8) | (b[pos++] & 0xFF);
            }
            return v;
        }

        void skip(int n) {
            if (n < 0 || pos + n > b.length) throw new IllegalStateException("truncated");
            pos += n;
        }

        byte[] take(int n) {
            if (n < 0 || pos + n > b.length) throw new IllegalStateException("truncated");
            byte[] out = Arrays.copyOfRange(b, pos, pos + n);
            pos += n;
            return out;
        }
    }
}
