package com.pwmgen;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Локальный HTTP как HttpListener в POBEDA (localhost → /cmd, /status). */
public class LocalProxyServer implements Runnable {

    public interface Callback {
        byte[] getIndexHtml();

        void onCmd(String key, String value);

        String getStatusJson();

        boolean isLinkUp();
    }

    private final Callback callback;
    private volatile boolean running = true;
    private ServerSocket server;
    private int port = 18088;
    private final ExecutorService workers = Executors.newFixedThreadPool(4);

    public LocalProxyServer(Callback callback) {
        this.callback = callback;
    }

    public int getPort() {
        return port;
    }

    /** Привязать порт до loadUrl WebView. */
    public int bindPort() {
        if (server != null) return port;
        for (int p = 18088; p < 18098; p++) {
            try {
                ServerSocket ss = new ServerSocket();
                ss.setReuseAddress(true);
                ss.bind(new InetSocketAddress("127.0.0.1", p));
                server = ss;
                port = p;
                return port;
            } catch (IOException ignored) {}
        }
        return -1;
    }

    public void start() {
        if (server == null && bindPort() < 0) return;
        new Thread(this, "pwmgen-http").start();
    }

    public void stop() {
        running = false;
        workers.shutdownNow();
        try {
            if (server != null) server.close();
        } catch (IOException ignored) {}
        server = null;
    }

    @Override
    public void run() {
        if (server == null) return;

        while (running) {
            try {
                Socket client = server.accept();
                workers.execute(() -> handle(client));
            } catch (IOException e) {
                if (running) {
                    try {
                        Thread.sleep(50);
                    } catch (InterruptedException ignored) {}
                }
            }
        }
    }

    private void handle(Socket client) {
        try {
            client.setSoTimeout(5000);
            InputStream in = client.getInputStream();
            OutputStream out = client.getOutputStream();

            BufferedReader br = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
            String reqLine = br.readLine();
            if (reqLine == null || reqLine.length() < 3) return;

            String method = reqLine.split(" ")[0];
            String path = "/";
            String[] parts = reqLine.split(" ");
            if (parts.length >= 2) path = parts[1];

            int contentLen = 0;
            String line;
            while ((line = br.readLine()) != null && !line.isEmpty()) {
                String low = line.toLowerCase();
                if (low.startsWith("content-length:")) {
                    try {
                        contentLen = Integer.parseInt(line.substring(15).trim());
                    } catch (NumberFormatException ignored) {}
                }
            }

            if (contentLen > 0) {
                char[] buf = new char[contentLen];
                br.read(buf, 0, contentLen);
            }

            if ("/".equals(path) || "/index.html".equals(path)) {
                byte[] html = callback.getIndexHtml();
                if (html == null) html = new byte[0];
                writeResponse(out, 200, "text/html; charset=utf-8", html);
            } else if (path.startsWith("/cmd/")) {
                String rest = path.substring(5);
                if (rest.startsWith("/")) rest = rest.substring(1);
                String[] kv = rest.split("/", 2);
                if (kv.length == 2 && !kv[0].isEmpty()) {
                    callback.onCmd(kv[0], kv[1]);
                }
                writeResponse(out, 200, "text/plain; charset=utf-8", "OK".getBytes(StandardCharsets.UTF_8));
            } else if ("/status".equals(path)) {
                String json = callback.getStatusJson();
                if (json == null || json.isEmpty()) json = "{}";
                writeResponse(out, 200, "application/json; charset=utf-8",
                    json.getBytes(StandardCharsets.UTF_8));
            } else if ("/ping".equals(path)) {
                String ok = callback.isLinkUp() ? "UP" : "DOWN";
                writeResponse(out, 200, "text/plain; charset=utf-8", ok.getBytes(StandardCharsets.UTF_8));
            } else {
                writeResponse(out, 404, "text/plain; charset=utf-8", "NOT FOUND".getBytes(StandardCharsets.UTF_8));
            }
        } catch (Exception ignored) {
        } finally {
            try {
                client.close();
            } catch (IOException ignored) {}
        }
    }

    private void writeResponse(OutputStream out, int code, String type, byte[] body) throws IOException {
        String status = code == 200 ? "OK" : "Not Found";
        String hdr = "HTTP/1.1 " + code + " " + status + "\r\n"
            + "Content-Type: " + type + "\r\n"
            + "Content-Length: " + body.length + "\r\n"
            + "Connection: close\r\n"
            + "Access-Control-Allow-Origin: *\r\n"
            + "\r\n";
        out.write(hdr.getBytes(StandardCharsets.UTF_8));
        out.write(body);
        out.flush();
    }
}
