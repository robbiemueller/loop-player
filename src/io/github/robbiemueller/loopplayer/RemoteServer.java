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
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.net.URLDecoder;
import java.nio.charset.Charset;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The phone remote: a tiny HTTP server on the local network that serves the control page and a JSON API.
 *
 * Every /api/ call needs the PIN shown on the sign, sent once to /api/login, which answers with a cookie (or as an
 * X-Pin header, for scripts). Five wrong PINs in a row lock the API for a minute. Calls that change something must
 * also carry an X-Remote header, which a web page on another site can't add without the browser asking first.
 *   GET    /                      the control page
 *   POST   /api/login {"pin"}     check the PIN, set the cookie
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
    }

    private static final Charset UTF8 = Charset.forName("UTF-8");
    private static final int MAX_HEAD = 16 * 1024;
    private static final int MAX_JSON = 256 * 1024;
    private static final int MAX_FAILURES = 5;
    private static final long LOCKOUT_MS = 60000;
    private static final int TIMEOUT_MS = 30000;

    private final int port;
    private final byte[] pin;
    private final Api api;
    private final ExecutorService pool = Executors.newFixedThreadPool(4);
    private ServerSocket socket;
    private int failures;
    private long lockedUntil;

    RemoteServer(int port, String pin, Api api) {
        this.port = port;
        this.pin = pin.getBytes(UTF8);
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
            client.setSoTimeout(TIMEOUT_MS);
            InputStream in = new BufferedInputStream(client.getInputStream());
            OutputStream out = new BufferedOutputStream(client.getOutputStream());
            String head = readHead(in);
            if (head != null) handle(head, in, out);
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

    private void handle(String head, InputStream in, OutputStream out) throws IOException {
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
            sendJson(out, 400, error("Bad length"));
            return;
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
                if (!checkPin(body.optString("pin", ""), out)) return;
                send(out, 200, "application/json", new JSONObject().put("ok", true).toString().getBytes(UTF8),
                        "Set-Cookie: lp=" + new String(pin, UTF8) + "; Max-Age=31536000; Path=/; HttpOnly; SameSite=Strict\r\n");
                return;
            }
            String given = headers.containsKey("x-pin") ? headers.get("x-pin") : cookie(headers.get("cookie"), "lp");
            if (!checkPin(given, out)) return;

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

    /** Compares in constant time and counts failures; answers 401 or 429 itself and returns false on a bad PIN. */
    private boolean checkPin(String given, OutputStream out) throws IOException {
        synchronized (this) {
            long now = System.currentTimeMillis();
            if (now < lockedUntil) {
                sendJson(out, 429, error("Too many wrong PINs. Try again in a minute."));
                return false;
            }
            if (given != null && MessageDigest.isEqual(given.getBytes(UTF8), pin)) {
                failures = 0;
                return true;
            }
            if (given != null && !given.isEmpty() && ++failures >= MAX_FAILURES) {
                failures = 0;
                lockedUntil = now + LOCKOUT_MS;
            }
        }
        sendJson(out, 401, error(given == null || given.isEmpty() ? "PIN needed" : "Wrong PIN"));
        return false;
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
