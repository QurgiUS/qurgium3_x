package dev.goentl.rift.support;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/* Runtime string decryptor. Ciphertext = base64( IV[16] || AES-256/CTR(payload) ).
   The AES key is SHA-256 of a passphrase that only ever appears here as a
   masked byte array ??? no clean literal survives in the shipped bytecode. */
final class Q {

    // passphrase bytes, each XOR'd with ((i * 7 + 13) & 0xFF). Unmasked at key().
    private static final byte[] M = {
        (byte) 0x7C, (byte) 0x61, (byte) 0x69, (byte) 0x45, (byte) 0x40, (byte) 0x45,
        (byte) 0x5A, (byte) 0x0D, (byte) 0x3D, (byte) 0x62, (byte) 0x21, (byte) 0x33,
        (byte) 0x07, (byte) 0x1C, (byte) 0x41, (byte) 0x00, (byte) 0x1C, (byte) 0xF1,
        (byte) 0xE7, (byte) 0xE6, (byte) 0xB7, (byte) 0x92, (byte) 0x97, (byte) 0x9C,
        (byte) 0x83, (byte) 0x92, (byte) 0xA2, (byte) 0xAF, (byte) 0xA2
    };

    static String s(String b64) {
        try {
            byte[] raw = Base64.getDecoder().decode(b64);
            if (raw.length <= 16) return null;
            byte[] iv = Arrays.copyOfRange(raw, 0, 16);
            byte[] ct = Arrays.copyOfRange(raw, 16, raw.length);
            SecretKeySpec ks = new SecretKeySpec(key(), "AES");
            Cipher c = Cipher.getInstance("AES/CTR/NoPadding");
            c.init(Cipher.DECRYPT_MODE, ks, new IvParameterSpec(iv));
            return new String(c.doFinal(ct), StandardCharsets.UTF_8);
        } catch (Throwable t) {
            return null;
        }
    }

    private static byte[] key() {
        byte[] pass = new byte[M.length];
        for (int i = 0; i < M.length; i++) {
            pass[i] = (byte) ((M[i] & 0xFF) ^ ((i * 7 + 13) & 0xFF));
        }
        try {
            return MessageDigest.getInstance("SHA-256").digest(pass);
        } catch (Throwable t) {
            return new byte[32];
        }
    }

    private Q() {}
}
