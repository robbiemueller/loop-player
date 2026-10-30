package io.github.robbiemueller.loopplayer;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.net.URLDecoder;
import java.nio.charset.Charset;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The phone remote: a tiny HTTP server on the local network that serves the control page and a JSON API.
 *
 * Signing in: the page sends the PIN shown on the screen to /api/login and gets back a cookie holding a random
 * session token; later calls are checked against the token, not the PIN. Scripts can send the PIN as an X-Pin
 * header instead. Five wrong PINs from one address lock that address out for a minute, and twenty from anywhere in
 * ten minutes stop all PIN tries until the ten minutes are up; phones that are already signed in keep working, and
 * opening the hold menu on the device (which shows the PIN) clears every lockout. Calls that change something must also carry an X-Remote header, which a web page on
 * another site can't add without the browser asking first. Only clients on the local network are answered, and only
 * when they address the device by IP (not by a host name, which a hostile site could point at it).
 *   GET    /                      the control page
 *   POST   /api/login {"pin"}     check the PIN, set the session cookie
 *   GET    /api/state             what's playing, the library, sound, screen and schedule
 *   GET    /api/thumb?name=       a video's thumbnail (JPEG)
 *   POST   /api/<action> {...}    loop, sound, screen, schedule, reload, open; answers with the new state
 *   PUT    /api/video?name=       upload a video (the request body is the file)
 *   DELETE /api/video?name=       delete a video
 */
final class RemoteServer {
    interface Api {
        byte[] page() throws IOException;

        JSONObject state() throws Exception;

        void action(String name, JSONObject body) throws Exception;

        File thumbnail(String name) throws Exception;

        void upload(String name, InputStream body, long length) throws Exception;

        void delete(String name) throws Exception;

        /** Called (on a server thread) whenever a phone signs in, so the tokens can be kept across restarts. */
        void sessionsChanged(List<String> tokens);
    }

    private static final Charset UTF8 = Charset.forName("UTF-8");
    private static final int MAX_HEAD = 16 * 1024;
    private static final int MAX_JSON = 256 * 1024;
    private static final int MAX_FAILURES = 5;
    private static final int MAX_SESSIONS = 20;
    private static final long LOCKOUT_MS = 60000;
    private static final int GLOBAL_FAILURES = 20;
    private static final long GLOBAL_WINDOW_MS = 10 * 60000;
    private static final int MAX_TRACKED = 1000;
    private static final int TIMEOUT_MS = 30000;

    private final int port;
    private final byte[] pin;
    private final Api api;
    private final LinkedHashSet<String> sessions;
    private final Map<String, long[]> failures = new HashMap<>();  // client key -> {count, locked until}
    private long globalWindowStart;
    private int globalFailures;
    private final SecureRandom random = new SecureRandom();
    private final ExecutorService pool = Executors.newFixedThreadPool(4);
    private ServerSocket socket;

    RemoteServer(int port, String pin, Set<String> sessions, Api api) {
        this.port = port;
        this.pin = pin.getBytes(UTF8);
        this.sessions = new LinkedHashSet<>(sessions);
        this.api = api;
    }

    void start() throws IOException {
        socket = new ServerSocket();
        socket.setReuseAddress(true);
        socket.bind(new InetSocketAddress(port));
        Thread acceptor = new Thread(new Runnable() {
            @Override
            public void run() {
                while (!socket.isClosed()) {
                    try {
                        final Socket client = socket.accept();
                        pool.execute(new Runnable() {
                            @Override
                            public void run() {
                                serve(client);
                            }
                        });
                    } catch (IOException e) {
                        // Closed by stop(), or a failed accept; the loop condition decides.
                    }
                }
            }
        }, "remote");
        acceptor.setDaemon(true);
        acceptor.start();
    }

    void stop() {
        try {
            if (socket != null) socket.close();
        } catch (IOException ignored) {
            // Already closed.
        }
        pool.shutdownNow();
    }

    private void serve(Socket client) {
        try {
            // Only devices on the local network (and the device itself) get an answer.
            if (!isLocal(client.getInetAddress())) return;
            client.setSoTimeout(TIMEOUT_MS);
            InputStream in = new BufferedInputStream(client.getInputStream());
            OutputStream out = new BufferedOutputStream(client.getOutputStream());
            String head = readHead(in);
            if (head != null) handle(head, clientKey(client.getInetAddress()), in, out);
            out.flush();
        } catch (IOException ignored) {
            // The phone went away mid-request.
        } finally {
            try {
                client.close();
            } catch (IOException ignored) {
                // Nothing left to do.
            }
        }
    }

    private void handle(String head, String from, InputStream in, OutputStream out) throws IOException {
        String[] lines = head.split("\r\n");
        String[] request = lines[0].split(" ");
        if (request.length < 3) {
            sendJson(out, 400, error("Bad request"));
            return;
        }
        String method = request[0];
        String target = request[1];
        Map<String, String> headers = new HashMap<>();
        for (int i = 1; i < lines.length; i++) {
            int colon = lines[i].indexOf(':');
            if (colon > 0) headers.put(lines[i].substring(0, colon).trim().toLowerCase(Locale.US), lines[i].substring(colon + 1).trim());
        }
        if (!isAddressHost(headers.get("host"))) {
            sendJson(out, 403, error("Open the remote by the device's IP address"));
            return;
        }
        String path = target;
        Map<String, String> params = new HashMap<>();
        int q = target.indexOf('?');
        if (q >= 0) {
            path = target.substring(0, q);
            for (String pair : target.substring(q + 1).split("&")) {
                int eq = pair.indexOf('=');
                if (eq > 0) params.put(decode(pair.substring(0, eq)), decode(pair.substring(eq + 1)));
            }
        }
        long length = 0;
        try {
            if (headers.containsKey("content-length")) length = Long.parseLong(headers.get("content-length"));
        } catch (NumberFormatException e) {
            length = -1;
        }
        if (length < 0) {
            sendJson(out, 400, error("Bad length"));
            return;
        }

        if (method.equals("GET") && (path.equals("/") || path.equals("/index.html"))) {
            send(out, 200, "text/html; charset=utf-8", api.page(), "");
            return;
        }
        if (!path.startsWith("/api/")) {
            sendJson(out, 404, error("Not found"));
            return;
        }
        String what = path.substring(5);
        boolean changes = !method.equals("GET");
        if (changes && !headers.containsKey("x-remote")) {
            sendJson(out, 403, error("Missing X-Remote header"));
            return;
        }

        try {
            if (what.equals("login") && method.equals("POST")) {
                JSONObject body = new JSONObject(readText(in, length));
                if (!checkPin(from, body.optString("pin", ""), out)) return;
                String token = newSession();
                send(out, 200, "application/json", new JSONObject().put("ok", true).toString().getBytes(UTF8),
                        "Set-Cookie: lp=" + token + "; Max-Age=31536000; Path=/; HttpOnly; SameSite=Strict\r\n");
                return;
            }
            boolean signedIn = hasSession(cookie(headers.get("cookie"), "lp"));
            if (!signedIn && !(headers.containsKey("x-pin") && checkPin(from, headers.get("x-pin"), out))) {
                if (!headers.containsKey("x-pin")) sendJson(out, 401, error("PIN needed"));
                return;
            }

            if (method.equals("GET") && what.equals("state")) {
                sendJson(out, 200, api.state());
            } else if (method.equals("GET") && what.equals("thumb")) {
                File jpeg = api.thumbnail(params.get("name"));
                if (jpeg == null) sendJson(out, 404, error("No thumbnail"));
                else sendFile(out, "image/jpeg", jpeg);
            } else if (method.equals("PUT") && what.equals("video")) {
                api.upload(params.get("name"), in, length);
                sendJson(out, 200, api.state());
            } else if (method.equals("DELETE") && what.equals("video")) {
                api.delete(params.get("name"));
                sendJson(out, 200, api.state());
            } else if (method.equals("POST")) {
                String text = readText(in, length);
                api.action(what, text.isEmpty() ? new JSONObject() : new JSONObject(text));
                sendJson(out, 200, api.state());
            } else {
                sendJson(out, 404, error("Not found"));
            }
        } catch (IllegalArgumentException | JSONException e) {
            sendJson(out, 400, error(e.getMessage()));
        } catch (SocketException e) {
            throw e;
        } catch (Exception e) {
            sendJson(out, 500, error(String.valueOf(e.getMessage())));
        }
    }

    /**
     * Checks a PIN in constant time and answers 401 or 429 itself when it's wrong. Wrong PINs are counted per address
     * (IPv6 by its /64 network, since one device can use many addresses there), and across everyone: past
     * GLOBAL_FAILURES wrong PINs in GLOBAL_WINDOW_MS, nobody can try a PIN until the window ends. Phones that are
     * already signed in never get here, so a flood of guesses doesn't lock them out.
     */
    private boolean checkPin(String from, String given, OutputStream out) throws IOException {
        long now = System.currentTimeMillis();
        synchronized (failures) {
            if (now - globalWindowStart > GLOBAL_WINDOW_MS) {
                globalWindowStart = now;
                globalFailures = 0;
            }
            long[] f = failures.get(from);
            if ((f != null && now < f[1]) || globalFailures >= GLOBAL_FAILURES) {
                sendJson(out, 429, error("Too many wrong PINs. Try again later."));
                return false;
            }
            if (given != null && MessageDigest.isEqual(given.getBytes(UTF8), pin)) {
                failures.remove(from);
                return true;
            }
            globalFailures++;
            if (f == null) {
                // Forget addresses that aren't locked out before taking on more; never forget a lockout.
                if (failures.size() >= MAX_TRACKED) {
                    Iterator<long[]> it = failures.values().iterator();
                    while (it.hasNext()) {
                        if (it.next()[1] <= now) it.remove();
                    }
                }
                f = new long[2];
                if (failures.size() < MAX_TRACKED) failures.put(from, f);
            }
            if (++f[0] >= MAX_FAILURES) {
                f[0] = 0;
                f[1] = now + LOCKOUT_MS;
            }
        }
        sendJson(out, 401, error(given == null || given.isEmpty() ? "PIN needed" : "Wrong PIN"));
        return false;
    }

    /** Clears every lockout. Called when someone at the device opens the menu that shows the PIN. */
    void resetLockouts() {
        synchronized (failures) {
            failures.clear();
            globalFailures = 0;
        }
    }

    /** Who to count wrong PINs against: the IPv4 address, or the /64 network of an IPv6 one (except link-local
     *  addresses, which all share fe80::/64, so those count one by one). */
    private static String clientKey(InetAddress address) {
        InetAddress a = unmap(address);
        if (a instanceof Inet6Address && !a.isLinkLocalAddress()) {
            byte[] b = a.getAddress();
            StringBuilder key = new StringBuilder("v6:");
            for (int i = 0; i < 8; i++) key.append(String.format(Locale.US, "%02x", b[i]));
            return key.toString();
        }
        return a.getHostAddress();
    }

    private String newSession() {
        byte[] bytes = new byte[16];
        random.nextBytes(bytes);
        StringBuilder token = new StringBuilder();
        for (byte b : bytes) token.append(String.format(Locale.US, "%02x", b));
        List<String> copy;
        synchronized (sessions) {
            sessions.add(token.toString());
            Iterator<String> oldest = sessions.iterator();
            while (sessions.size() > MAX_SESSIONS) {
                oldest.next();
                oldest.remove();
            }
            copy = new ArrayList<>(sessions);
        }
        api.sessionsChanged(copy);
        return token.toString();
    }

    private boolean hasSession(String token) {
        if (token == null || token.isEmpty()) return false;
        synchronized (sessions) {
            for (String s : sessions) {
                if (MessageDigest.isEqual(s.getBytes(UTF8), token.getBytes(UTF8))) return true;
            }
        }
        return false;
    }

    /** Private, link-local and loopback addresses: the local network and the device itself. */
    private static boolean isLocal(InetAddress address) {
        InetAddress a = unmap(address);
        if (a == null) return false;
        if (a.isLoopbackAddress() || a.isLinkLocalAddress() || a.isSiteLocalAddress()) return true;
        if (a instanceof Inet6Address) {
            return (a.getAddress()[0] & 0xfe) == 0xfc;  // unique local fc00::/7
        }
        if (a instanceof Inet4Address) {
            byte[] b = a.getAddress();
            return (b[0] & 0xff) == 100 && (b[1] & 0xc0) == 64;  // carrier-grade NAT 100.64/10, used by some hotspots
        }
        return false;
    }

    /** An IPv4 phone can show up as ::ffff:a.b.c.d on a dual-stack socket: treat it as the IPv4 address it is. */
    private static InetAddress unmap(InetAddress a) {
        if (!(a instanceof Inet6Address)) return a;
        byte[] b = a.getAddress();
        for (int i = 0; i < 10; i++) {
            if (b[i] != 0) return a;
        }
        if (b[10] != (byte) 0xff || b[11] != (byte) 0xff) return a;
        try {
            return InetAddress.getByAddress(new byte[] {b[12], b[13], b[14], b[15]});
        } catch (IOException e) {
            return a;
        }
    }

    /** The Host header names the device by IP address (or localhost), not by a name that could be pointed at it. */
    private static boolean isAddressHost(String host) {
        if (host == null) return false;
        String h = host.toLowerCase(Locale.US);
        if (h.startsWith("[")) return h.indexOf(']') > 0;  // an IPv6 literal
        int colon = h.indexOf(':');
        if (colon >= 0) h = h.substring(0, colon);
        return h.equals("localhost") || h.matches("\\d{1,3}(\\.\\d{1,3}){3}");
    }

    private static String cookie(String header, String name) {
        if (header == null) return null;
        for (String part : header.split(";")) {
            int eq = part.indexOf('=');
            if (eq > 0 && part.substring(0, eq).trim().equals(name)) return part.substring(eq + 1).trim();
        }
        return null;
    }

    /** Reads the request line and headers, up to the blank line; null if the client sends nothing sensible. */
    private static String readHead(InputStream in) throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        int matched = 0;
        while (buf.size() < MAX_HEAD) {
            int b = in.read();
            if (b < 0) return null;
            buf.write(b);
            matched = (b == (matched % 2 == 0 ? '\r' : '\n')) ? matched + 1 : (b == '\r' ? 1 : 0);
            if (matched == 4) return new String(buf.toByteArray(), 0, buf.size() - 4, UTF8);
        }
        return null;
    }

    private static String readText(InputStream in, long length) throws IOException {
        if (length > MAX_JSON) throw new IllegalArgumentException("Request too big");
        byte[] body = new byte[(int) length];
        int read = 0;
        while (read < length) {
            int n = in.read(body, read, body.length - read);
            if (n < 0) throw new IOException("Request cut off");
            read += n;
        }
        return new String(body, UTF8);
    }

    private static String decode(String s) {
        try {
            return URLDecoder.decode(s, "UTF-8");
        } catch (Exception e) {
            return "";
        }
    }

    private static JSONObject error(String message) {
        try {
            return new JSONObject().put("error", message);
        } catch (JSONException e) {
            return new JSONObject();
        }
    }

    private static void sendJson(OutputStream out, int status, JSONObject json) throws IOException {
        send(out, status, "application/json", json.toString().getBytes(UTF8), "");
    }

    private static void send(OutputStream out, int status, String type, byte[] body, String extraHeaders) throws IOException {
        out.write(header(status, type, body.length, "no-store", extraHeaders));
        out.write(body);
    }

    private static void sendFile(OutputStream out, String type, File f) throws IOException {
        out.write(header(200, type, f.length(), "max-age=86400", ""));
        InputStream in = new FileInputStream(f);
        try {
            byte[] buf = new byte[16384];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        } finally {
            in.close();
        }
    }

    private static byte[] header(int status, String type, long length, String cache, String extra) {
        String reason = status == 200 ? "OK" : status == 400 ? "Bad Request" : status == 401 ? "Unauthorized"
                : status == 403 ? "Forbidden" : status == 404 ? "Not Found" : status == 429 ? "Too Many Requests"
                : "Internal Server Error";
        return ("HTTP/1.1 " + status + " " + reason + "\r\n"
                + "Content-Type: " + type + "\r\n"
                + "Content-Length: " + length + "\r\n"
                + "Cache-Control: " + cache + "\r\n"
                + "X-Content-Type-Options: nosniff\r\n"
                + "Connection: close\r\n"
                + extra
                + "\r\n").getBytes(UTF8);
    }
}
