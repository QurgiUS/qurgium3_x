package dev.goentl.rift.support;

import com.sun.jna.platform.win32.Advapi32Util;

import java.util.LinkedHashMap;
import java.util.Map;

/* Read-only registry access through the bundled JNA platform binding.

   Replaces every `reg.exe query` the payload used to spawn: same values, no
   child process, no console, no command line for a scanner to read. Each call
   fails closed - a missing key, a denied path or a missing JNA class returns
   null/empty instead of throwing. */
final class Reg {

    static final com.sun.jna.platform.win32.WinReg.HKEY HKCU =
            com.sun.jna.platform.win32.WinReg.HKEY_CURRENT_USER;
    static final com.sun.jna.platform.win32.WinReg.HKEY HKLM =
            com.sun.jna.platform.win32.WinReg.HKEY_LOCAL_MACHINE;

    private Reg() {}

    static String string(com.sun.jna.platform.win32.WinReg.HKEY root, String path, String name) {
        try {
            return Advapi32Util.registryGetStringValue(root, path, name == null ? "" : name);
        } catch (Throwable t) {
            return null;
        }
    }

    static String[] keys(com.sun.jna.platform.win32.WinReg.HKEY root, String path) {
        try {
            return Advapi32Util.registryGetKeys(root, path);
        } catch (Throwable t) {
            return new String[0];
        }
    }

    /* String values of one key, name -> value; non-string values are skipped. */
    static Map<String, String> values(com.sun.jna.platform.win32.WinReg.HKEY root, String path) {
        Map<String, String> out = new LinkedHashMap<>();
        try {
            for (Map.Entry<String, Object> e : Advapi32Util.registryGetValues(root, path).entrySet()) {
                if (e.getValue() instanceof String s) out.put(e.getKey(), s);
            }
        } catch (Throwable ignored) {}
        return out;
    }
}
