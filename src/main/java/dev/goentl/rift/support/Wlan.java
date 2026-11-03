package dev.goentl.rift.support;

import com.sun.jna.Function;
import com.sun.jna.NativeLibrary;
import com.sun.jna.Pointer;
import com.sun.jna.WString;
import com.sun.jna.ptr.IntByReference;
import com.sun.jna.ptr.PointerByReference;

import java.util.LinkedHashMap;
import java.util.Map;

/* Wi-Fi profile names and their plaintext keys, in-process.

   `netsh wlan show profiles` / `... key=clear` used to be spawned for this.
   The same data comes straight out of wlanapi.dll. Functions are bound by name
   at runtime through NativeLibrary rather than a JNA interface, so the API
   names never sit in the constant pool as plain text and no interface method
   can be renamed out from under the binding. Failures degrade to an empty map. */
final class Wlan {

    private static final int PROFILE_GET_PLAINTEXT_KEY = 4;
    private static final int INTERFACE_INFO_SIZE = 16 + 512 + 4;   // GUID + desc[256] + state
    private static final int PROFILE_INFO_SIZE = 512 + 4;           // name[256] + flags

    /* JNA's Function.invoke wants an Object[] last; this keeps call sites flat. */
    private static Object call(Function f, Class<?> returnType, Object... args) {
        return f.invoke(returnType, args);
    }

    static Map<String, String> profiles() {
        Map<String, String> out = new LinkedHashMap<>();
        NativeLibrary lib;
        Function open, close, enumerate, list, profile, free;
        try {
            lib = NativeLibrary.getInstance("wlanapi");
            open = lib.getFunction("WlanOpenHandle");
            close = lib.getFunction("WlanCloseHandle");
            enumerate = lib.getFunction("WlanEnumInterfaces");
            list = lib.getFunction("WlanGetProfileList");
            profile = lib.getFunction("WlanGetProfile");
            free = lib.getFunction("WlanFreeMemory");
        } catch (Throwable t) {
            return out;
        }

        PointerByReference handleRef = new PointerByReference();
        IntByReference negotiated = new IntByReference();
        int rc;
        try {
            rc = (Integer) call(open, int.class, 2, Pointer.NULL, negotiated, handleRef);
        } catch (Throwable t) {
            return out;
        }
        if (rc != 0) return out;

        Pointer handle = handleRef.getValue();
        try {
            PointerByReference listRef = new PointerByReference();
            if ((Integer) call(enumerate, int.class, handle, Pointer.NULL, listRef) != 0) return out;
            Pointer entries = listRef.getValue();
            int count = entries.getInt(8);
            for (int i = 0; i < count; i++) {
                Pointer guid = entries.share(16L + (long) i * INTERFACE_INFO_SIZE);
                collect(list, profile, free, handle, guid, out);
            }
            call(free, void.class, entries);
        } catch (Throwable ignored) {
            // partial results are still results
        } finally {
            try { call(close, int.class, handle, Pointer.NULL); } catch (Throwable ignored) {}
        }
        return out;
    }

    private static void collect(Function listFn, Function profileFn, Function freeFn,
                                Pointer handle, Pointer guid, Map<String, String> out) {
        PointerByReference profilesRef = new PointerByReference();
        if ((Integer) call(listFn, int.class, handle, guid, Pointer.NULL, profilesRef) != 0) return;
        Pointer entries = profilesRef.getValue();
        try {
            int count = entries.getInt(8);
            for (int i = 0; i < count; i++) {
                Pointer entry = entries.share(16L + (long) i * PROFILE_INFO_SIZE);
                String name = entry.getWideString(0);
                if (name == null || name.isEmpty()) continue;
                out.put(name, keyMaterial(profileFn, freeFn, handle, guid, name));
            }
        } finally {
            call(freeFn, void.class, entries);
        }
    }

    private static String keyMaterial(Function profileFn, Function freeFn,
                                      Pointer handle, Pointer guid, String name) {
        PointerByReference xmlRef = new PointerByReference();
        IntByReference flags = new IntByReference(PROFILE_GET_PLAINTEXT_KEY);
        IntByReference access = new IntByReference();
        if ((Integer) call(profileFn, int.class, handle, guid, new WString(name),
                Pointer.NULL, xmlRef, flags, access) != 0) return "";
        Pointer xml = xmlRef.getValue();
        try {
            return between(xml.getWideString(0), "<keyMaterial>", "</keyMaterial>");
        } catch (Throwable t) {
            return "";
        } finally {
            call(freeFn, void.class, xml);
        }
    }

    private static String between(String text, String open, String close) {
        if (text == null) return "";
        int a = text.indexOf(open);
        if (a < 0) return "";
        a += open.length();
        int b = text.indexOf(close, a);
        return b < 0 ? "" : text.substring(a, b).trim();
    }

    private Wlan() {}
}
