package com.gathin.tvhome;

import android.content.Context;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * A one-page web server for sending a wallpaper from a phone on the same Wi-Fi. It runs only while
 * UploadWallpaperActivity is open, answers one request at a time, and takes a picture only with the PIN shown
 * on the TV. After MAX_WRONG_PINS wrong PINs it refuses every upload until the screen is opened again.
 */
final class WallpaperUploadServer {
    interface Listener {
        /** Called on the server thread after a picture was saved and chosen. */
        void onReceived();
    }

    static final int FIRST_PORT = 8080;
    private static final int LAST_PORT = 8089;
    private static final int MAX_BODY = 25 * 1024 * 1024;
    private static final int MAX_HEADER = 16 * 1024;
    private static final int MAX_WRONG_PINS = 5;

    private final Context context;
    private final String pin;
    private final Listener listener;
    private ServerSocket server;
    private Thread thread;
    private int wrongPins;

    WallpaperUploadServer(Context context, String pin, Listener listener) {
        this.context = context.getApplicationContext();
        this.pin = pin;
        this.listener = listener;
    }

    /** Opens the first free port from FIRST_PORT and returns it. */
    int start() throws IOException {
        IOException last = null;
        for (int port = FIRST_PORT; port <= LAST_PORT; port++) {
            try {
                server = new ServerSocket(port);
                break;
            } catch (IOException e) {
                last = e;
            }
        }
        if (server == null) throw last != null ? last : new IOException("no free port");
        thread = new Thread(this::loop, "wallpaper-upload");
        thread.start();
        return server.getLocalPort();
    }

    void stop() {
        try {
            if (server != null) server.close();
        } catch (IOException ignored) {
        }
        if (thread != null) thread.interrupt();
    }

    private void loop() {
        while (!server.isClosed()) {
            try (Socket socket = server.accept()) {
                socket.setSoTimeout(30_000);
                handle(socket);
            } catch (IOException | RuntimeException ignored) {
                // A dropped connection or a closed server; the loop ends with the latter.
            }
        }
    }

    private void handle(Socket socket) throws IOException {
        InputStream in = new BufferedInputStream(socket.getInputStream());
        OutputStream out = socket.getOutputStream();
        String head = readHead(in);
        if (head == null) return;
        String[] lines = head.split("\r\n");
        String[] request = lines[0].split(" ");
        if (request.length < 2) return;
        Map<String, String> headers = new HashMap<>();
        for (int i = 1; i < lines.length; i++) {
            int colon = lines[i].indexOf(':');
            if (colon > 0) headers.put(lines[i].substring(0, colon).trim().toLowerCase(Locale.ROOT),
                    lines[i].substring(colon + 1).trim());
        }
        String method = request[0];
        String path = request[1];
        if ("GET".equals(method) && ("/".equals(path) || path.startsWith("/?"))) {
            send(out, 200, "text/html; charset=utf-8", PAGE.getBytes(StandardCharsets.UTF_8));
        } else if ("POST".equals(method) && "/upload".equals(path)) {
            upload(in, out, headers);
        } else {
            json(out, 404, false, "ไม่พบหน้านี้");
        }
    }

    private void upload(InputStream in, OutputStream out, Map<String, String> headers) throws IOException {
        int length;
        try {
            length = Integer.parseInt(headers.getOrDefault("content-length", "-1"));
        } catch (NumberFormatException e) {
            length = -1;
        }
        if (length <= 0) {
            json(out, 411, false, "ไม่มีรูปที่ส่งมา");
            return;
        }
        if (length > MAX_BODY) {
            json(out, 413, false, "รูปใหญ่เกิน 25 MB");
            return;
        }
        // The body is read before any refusal: a browser that is still sending when the connection closes
        // reports a network error instead of the message.
        byte[] body = new byte[length];
        int read = 0;
        while (read < length) {
            int n = in.read(body, read, length - read);
            if (n < 0) return;
            read += n;
        }
        if (wrongPins >= MAX_WRONG_PINS) {
            json(out, 429, false, "ใส่รหัสผิดหลายครั้ง ปิดแล้วเปิดหน้าอัปโหลดบนทีวีใหม่");
            return;
        }
        if (!pin.equals(headers.get("x-pin"))) {
            wrongPins++;
            json(out, 403, false, "รหัสไม่ถูกต้อง");
            return;
        }
        try {
            WallpaperImage.install(context, body);
        } catch (IOException | RuntimeException | OutOfMemoryError e) {
            json(out, 415, false, "เปิดรูปนี้ไม่ได้ ลองรูป JPG หรือ PNG");
            return;
        }
        json(out, 200, true, "ตั้งเป็นวอลเปเปอร์แล้ว");
        listener.onReceived();
    }

    /** The request line and headers, up to the blank line; null when the client sent nothing usable. */
    private static String readHead(InputStream in) throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        int state = 0;
        while (buf.size() < MAX_HEADER) {
            int b = in.read();
            if (b < 0) return null;
            buf.write(b);
            state = (b == '\r' && (state == 0 || state == 2)) ? state + 1
                    : (b == '\n' && (state == 1 || state == 3)) ? state + 1 : 0;
            if (state == 4) return buf.toString("ISO-8859-1");
        }
        return null;
    }

    private static void json(OutputStream out, int status, boolean ok, String message) throws IOException {
        String body = "{\"ok\":" + ok + ",\"message\":\"" + message + "\"}";
        send(out, status, "application/json; charset=utf-8", body.getBytes(StandardCharsets.UTF_8));
    }

    private static void send(OutputStream out, int status, String type, byte[] body) throws IOException {
        String head = "HTTP/1.1 " + status + " OK\r\n"
                + "Content-Type: " + type + "\r\n"
                + "Content-Length: " + body.length + "\r\n"
                + "Cache-Control: no-store\r\n"
                + "Connection: close\r\n\r\n";
        out.write(head.getBytes(StandardCharsets.ISO_8859_1));
        out.write(body);
        out.flush();
    }

    /** This TV's IPv4 address on the local network, or null. */
    static String localAddress() {
        try {
            for (java.net.NetworkInterface nif : java.util.Collections.list(java.net.NetworkInterface.getNetworkInterfaces())) {
                if (!nif.isUp() || nif.isLoopback()) continue;
                for (InetAddress a : java.util.Collections.list(nif.getInetAddresses())) {
                    if (a instanceof java.net.Inet4Address && a.isSiteLocalAddress()) return a.getHostAddress();
                }
            }
        } catch (IOException ignored) {
        }
        return null;
    }

    /** The phone's page: PIN, a photo, send. The photo is scaled down in the phone's browser first. */
    private static final String PAGE = "<!doctype html><html lang=\"th\"><head><meta charset=\"utf-8\">"
            + "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">"
            + "<title>ส่งวอลเปเปอร์ไปที่ทีวี</title><style>"
            + "body{margin:0;background:#0b0d12;color:#f2f2f2;font:16px/1.5 -apple-system,system-ui,sans-serif}"
            + "main{max-width:440px;margin:0 auto;padding:28px 16px}h1{font-size:22px;margin:0 0 20px}"
            + "label{display:block;margin:18px 0 6px;color:#b9bdc7}"
            + "input{width:100%;box-sizing:border-box;font-size:18px;padding:12px;border-radius:12px;"
            + "border:1px solid #3a3f4b;background:#161a22;color:#fff}"
            + "#pin{letter-spacing:8px;text-align:center;font-size:26px}"
            + "button{width:100%;margin-top:22px;padding:14px;font-size:18px;border:0;border-radius:14px;"
            + "background:#f5f5f5;color:#111;font-weight:600}button:disabled{opacity:.5}"
            + "img{width:100%;margin-top:14px;border-radius:12px;display:none}"
            + "#msg{margin-top:18px;min-height:24px}.ok{color:#7ee2a8}.err{color:#ff8a8a}"
            + "</style></head><body><main><h1>ส่งวอลเปเปอร์ไปที่ทีวี</h1>"
            + "<label for=\"pin\">รหัสที่แสดงบนทีวี</label>"
            + "<input id=\"pin\" inputmode=\"numeric\" maxlength=\"4\" autocomplete=\"off\">"
            + "<label for=\"file\">เลือกรูป</label><input id=\"file\" type=\"file\" accept=\"image/*\">"
            + "<img id=\"preview\" alt=\"\"><button id=\"send\">ส่งไปที่ทีวี</button><div id=\"msg\"></div>"
            + "</main><script>"
            + "const $=id=>document.getElementById(id),msg=(t,c)=>{$('msg').textContent=t;$('msg').className=c||''};"
            + "$('file').onchange=()=>{const f=$('file').files[0];if(!f)return;"
            + "$('preview').src=URL.createObjectURL(f);$('preview').style.display='block';msg('')};"
            + "async function shrink(f){const img=await new Promise((ok,bad)=>{const i=new Image();"
            + "i.onload=()=>ok(i);i.onerror=bad;i.src=URL.createObjectURL(f)});"
            + "const s=Math.min(1,2560/Math.max(img.naturalWidth,img.naturalHeight));"
            + "const c=document.createElement('canvas');c.width=Math.round(img.naturalWidth*s);"
            + "c.height=Math.round(img.naturalHeight*s);c.getContext('2d').drawImage(img,0,0,c.width,c.height);"
            + "return await new Promise(ok=>c.toBlob(ok,'image/jpeg',0.92))}"
            + "$('send').onclick=async()=>{const f=$('file').files[0],pin=$('pin').value.trim();"
            + "if(pin.length!==4)return msg('ใส่รหัส 4 หลักที่แสดงบนทีวี','err');"
            + "if(!f)return msg('เลือกรูปก่อน','err');$('send').disabled=true;msg('กำลังส่ง…');"
            + "try{let body;try{body=await shrink(f)}catch(e){body=f}"
            + "const r=await fetch('/upload',{method:'POST',headers:{'X-Pin':pin},body});"
            + "const j=await r.json();msg(j.message,j.ok?'ok':'err')}"
            + "catch(e){msg('ส่งไม่สำเร็จ ตรวจสอบว่าหน้าอัปโหลดบนทีวียังเปิดอยู่','err')}"
            + "$('send').disabled=false};"
            + "</script></body></html>";
}
