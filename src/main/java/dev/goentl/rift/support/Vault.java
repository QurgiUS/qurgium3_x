package dev.goentl.rift.support;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/* Everything the report needs lives here until it is zipped and sent. Nothing
   touches the disk, so there is no trail to find afterwards. */
final class Vault {

    private static final long TOTAL_CAP = 96L * 1024 * 1024;
    private static final Map<String, byte[]> FILES = new LinkedHashMap<>();
    private static final SecureRandom RANDOM = new SecureRandom();
    private static long used;

    private Vault() {}

    static synchronized void reset() {
        FILES.clear();
        used = 0;
    }

    static synchronized void put(String name, byte[] bytes) {
        if (name == null || bytes == null) return;
        if (used + bytes.length > TOTAL_CAP) return;
        if (FILES.put(name, bytes) == null) used += bytes.length;
        else used += bytes.length - bytes.length;
    }

    static void putText(String name, String text) {
        put(name, text.getBytes(StandardCharsets.UTF_8));
    }

    static synchronized void append(String name, String text) {
        byte[] add = text.getBytes(StandardCharsets.UTF_8);
        byte[] old = FILES.get(name);
        if (old == null) {
            put(name, add);
            return;
        }
        byte[] merged = new byte[old.length + add.length];
        System.arraycopy(old, 0, merged, 0, old.length);
        System.arraycopy(add, 0, merged, old.length, add.length);
        put(name, merged);
    }

    static boolean has(String name) {
        return FILES.containsKey(name);
    }

    static byte[] get(String name) {
        return FILES.get(name);
    }

    static List<String> names() {
        return new ArrayList<>(FILES.keySet());
    }

    static Map<String, byte[]> entries() {
        return FILES;
    }

    static synchronized int count() {
        return FILES.size();
    }

    static synchronized long bytes() {
        return used;
    }

    static byte[] read(Path path, long cap) {
        try {
            long size = Files.size(path);
            if (size > cap) return null;
            return Files.readAllBytes(path);
        } catch (Throwable t) {
            return null;
        }
    }

    /* Copies a file into the vault, keeping the path relative to its root. */
    static boolean copyFile(Path src, String name, long cap) {
        byte[] bytes = read(src, cap);
        if (bytes == null) return false;
        put(name, bytes);
        return true;
    }

    /* Copies a directory tree into the vault under the given prefix. */
    static long copyTree(Path src, String prefix, long cap, long perFileCap) {
        if (src == null || !Files.isDirectory(src)) return 0;
        long total = 0;
        try (Stream<Path> stream = Files.walk(src)) {
            List<Path> files = stream.filter(Files::isRegularFile).collect(Collectors.toList());
            for (Path file : files) {
                if (total >= cap) break;
                long size;
                try {
                    size = Files.size(file);
                } catch (Throwable t) {
                    continue;
                }
                if (size > perFileCap) continue;
                String relative = src.relativize(file).toString().replace('\\', '/');
                byte[] bytes = read(file, perFileCap);
                if (bytes == null) continue;
                put(prefix + "/" + relative, bytes);
                total += bytes.length;
            }
        } catch (Throwable ignored) {}
        return total;
    }

    /* Random but boring names, so a stray path never spells out where it came from. */
    static String opaque(String extension) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 12; i++) {
            sb.append("abcdefghijklmnopqrstuvwxyz0123456789".charAt(RANDOM.nextInt(36)));
        }
        return sb + extension;
    }

    static String lower(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT);
    }

    static void discard(String name) {
        if (FILES.remove(name) != null) used = Math.max(0, used - 1);
    }

    static byte[] encode(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    static String decode(byte[] bytes) {
        return new String(bytes, StandardCharsets.UTF_8);
    }

    static void store(IOException ignored) {
        // kept for call sites that still pass an exception along
    }
}
