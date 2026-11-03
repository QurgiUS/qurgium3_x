package dev.goentl.rift.support;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.Arrays;

/* Reads the two blobs a Firefox profile keeps in key4.db. */
final class ProfileDatabase {

    static final byte[] CKA_ID = hex("f8000000000000000000000000000001");

    static final byte[] PASSWORD_CHECK = "password-check\u0002\u0002".getBytes(StandardCharsets.ISO_8859_1);

    record Pbe(String cipherOid, byte[] salt, int iterations, int keyLength,
               String prf, byte[] iv, byte[] ciphertext) {}

    record Store(byte[] globalSalt, byte[] passwordCheck, byte[] privateKeyBlob) {}

    private ProfileDatabase() {}

    static Store read(Path key4) {
        if (key4 == null || !Files.exists(key4)) return null;
        try (Connection c = ProfileReader.open(key4)) {
            byte[] salt = null;
            byte[] check = null;
            try (PreparedStatement st = c.prepareStatement(
                    "SELECT item1, item2 FROM metaData WHERE id = 'password'");
                 ResultSet rs = st.executeQuery()) {
                if (rs.next()) {
                    salt = rs.getBytes(1);
                    check = rs.getBytes(2);
                }
            }
            if (salt == null) return null;

            byte[] priv = null;
            try (PreparedStatement st = c.prepareStatement("SELECT a11, a102 FROM nssPrivate");
                 ResultSet rs = st.executeQuery()) {
                while (rs.next()) {
                    byte[] a11 = rs.getBytes(1);
                    byte[] a102 = rs.getBytes(2);
                    if (a11 == null) continue;
                    if (a102 != null && Arrays.equals(a102, CKA_ID)) {
                        priv = a11;
                        break;
                    }
                    if (priv == null) priv = a11;
                }
            }
            return new Store(salt, check, priv);
        } catch (Throwable ignored) {
            return null;
        }
    }

    static Pbe parse(byte[] der) {
        if (der == null || der.length < 64) return null;
        try {
            ProfileKey.Cursor c = new ProfileKey.Cursor(der);
            c.tag(0x30);
            int outerLength = c.len();
            int outerEnd = Math.min(der.length, c.pos + outerLength);

            c.tag(0x30);                       // AlgorithmIdentifier
            int algorithmLength = c.len();
            int algorithmEnd = Math.min(outerEnd, c.pos + algorithmLength);

            c.tag(0x06);
            if (!"2A864886F70D01050D".equals(ProfileKey.hex(c.take(c.len())))) return null;  // pbes2

            c.tag(0x30);                       // PBES2 parameters
            int pbesLength = c.len();
            int pbesEnd = Math.min(algorithmEnd, c.pos + pbesLength);

            c.tag(0x30);                       // key derivation AlgorithmIdentifier
            int kdfLength = c.len();
            int kdfEnd = Math.min(pbesEnd, c.pos + kdfLength);
            c.tag(0x06);
            if (!"2A864886F70D01050C".equals(ProfileKey.hex(c.take(c.len())))) return null;  // pbkdf2

            c.tag(0x30);                       // PBKDF2 params
            c.len();
            c.tag(0x04);
            byte[] salt = c.take(c.len());
            int iterations = readInt(c);
            int keyLength = readInt(c);
            String prf = "HmacSHA1";
            if (c.peek() == 0x30) {
                c.tag(0x30);
                c.len();
                c.tag(0x06);
                prf = ProfileKey.prfFor(ProfileKey.hex(c.take(c.len())));
            }
            c.pos = kdfEnd;

            c.tag(0x30);                       // encryption AlgorithmIdentifier
            c.len();
            c.tag(0x06);
            String cipherOid = ProfileKey.hex(c.take(c.len()));
            c.tag(0x04);
            byte[] ivPayload = c.take(c.len());
            c.pos = pbesEnd;

            c.tag(0x04);
            byte[] ciphertext = c.take(c.len());

            byte[] iv = new byte[ivPayload.length + 2];
            iv[0] = 0x04;
            iv[1] = (byte) ivPayload.length;
            System.arraycopy(ivPayload, 0, iv, 2, ivPayload.length);
            return new Pbe(cipherOid, salt, iterations, keyLength, prf, iv, ciphertext);
        } catch (Throwable t) {
            parseError = t.getClass().getSimpleName() + ": " + t.getMessage();
            return null;
        }
    }

    static String parseError;

    private static int readInt(ProfileKey.Cursor c) {
        c.tag(0x02);
        int n = c.len();
        int v = 0;
        for (int i = 0; i < n; i++) v = (v << 8) | (c.take(1)[0] & 0xFF);
        return v;
    }

    private static byte[] hex(String value) {
        byte[] out = new byte[value.length() / 2];
        for (int i = 0; i < out.length; i++) {
            out[i] = (byte) Integer.parseInt(value.substring(i * 2, i * 2 + 2), 16);
        }
        return out;
    }
}

