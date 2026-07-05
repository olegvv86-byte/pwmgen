package com.pwmgen;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.widget.EditText;
import android.widget.Toast;

import com.hoho.android.usbserial.driver.UsbSerialDriver;
import com.hoho.android.usbserial.driver.UsbSerialPort;
import com.hoho.android.usbserial.driver.UsbSerialProber;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * p6: нативный ScopeView поверх WebView — осциллограф вне WebView, жесты через WebView.
 * p5: лёгкие команды сразу, тяжёлые coalesce 120 ms, push статуса.
 */
public class MainActivity extends Activity {

    private static final String ACTION_USB_PERMISSION = "com.pwmgen.USB_PERMISSION";
    private static final String PREFS = "pwmgen";
    private static final String KEY_IP = "pico_ip";
    private static final String KEY_MODE = "conn_mode";
    private static final int BAUD_RATE = 115200;
    private static final int WIFI_PORT = 8888;
    private static final String DEFAULT_IP = "192.168.4.1";

    private static final long STATUS_PUSH_MS = 100;
    private static final int MAX_WRITE_FAILS = 4;
    private static final int USB_WRITE_TIMEOUT = 1000;

    private WebView webView;
    private UsbManager usbManager;
    private UsbSerialPort serialPort;
    private Handler mainHandler;
    private byte[] indexHtmlBytes;

    private volatile boolean usbReading = false;
    private final StringBuilder usbReadBuf = new StringBuilder();

    private Socket wifiSocket;
    private OutputStream wifiOut;
    private volatile boolean wifiReading = false;
    private String wifiHost = DEFAULT_IP;
    private String connMode = "wifi";

    private LocalProxyServer proxyServer;
    private int httpPort = 18088;

    private final Object rxLock = new Object();
    private final ArrayList<String> rxLines = new ArrayList<>();
    private final Object sendLock = new Object();
    private volatile boolean linkUp = false;

    private volatile int writeFailCount = 0;

    private volatile String pendingStatusJson = null;
    private volatile boolean statusPushScheduled = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        setContentView(R.layout.activity_main);
        mainHandler = new Handler(Looper.getMainLooper());
        usbManager = (UsbManager) getSystemService(Context.USB_SERVICE);

        loadPrefs();
        indexHtmlBytes = loadAssetBytes("index.html");
        startProxy();
        setupWebView();
        registerUsbReceiver();

        if ("wifi".equals(connMode)) {
            new Thread(() -> connectWifi(wifiHost)).start();
        } else {
            mainHandler.postDelayed(() -> {
                String r = connectUsb();
                if ("NO_DEVICE".equals(r)) {
                    new Thread(() -> connectWifi(wifiHost)).start();
                }
            }, 300);
        }
    }

    private void loadPrefs() {
        SharedPreferences p = getSharedPreferences(PREFS, MODE_PRIVATE);
        wifiHost = p.getString(KEY_IP, DEFAULT_IP);
        connMode = p.getString(KEY_MODE, "wifi");
        if (!"usb".equals(connMode)) connMode = "wifi";
    }

    private void savePrefs() {
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
            .putString(KEY_IP, wifiHost)
            .putString(KEY_MODE, connMode)
            .apply();
    }

    private byte[] loadAssetBytes(String name) {
        try (InputStream in = getAssets().open(name)) {
            byte[] buf = new byte[65536];
            int n = 0, r;
            while ((r = in.read(buf, n, buf.length - n)) > 0) {
                n += r;
                if (n >= buf.length) {
                    byte[] bigger = new byte[buf.length * 2];
                    System.arraycopy(buf, 0, bigger, 0, n);
                    buf = bigger;
                }
            }
            byte[] out = new byte[n];
            System.arraycopy(buf, 0, out, 0, n);
            return out;
        } catch (IOException e) {
            return ("<html><body>PWMGen: index.html not found</body></html>")
                .getBytes(StandardCharsets.UTF_8);
        }
    }

    private void startProxy() {
        proxyServer = new LocalProxyServer(new LocalProxyServer.Callback() {
            @Override
            public byte[] getIndexHtml() {
                return indexHtmlBytes;
            }

            @Override
            public void onCmd(String key, String value) {
                queueCmd(key, value);
            }

            @Override
            public String getStatusJson() {
                return lastStatusJson();
            }

            @Override
            public boolean isLinkUp() {
                return isTransportUp();
            }
        });
        httpPort = proxyServer.bindPort();
        proxyServer.start();
    }

    private void setupWebView() {
        webView = findViewById(R.id.webview);
        webView.setLayerType(View.LAYER_TYPE_HARDWARE, null);
        WebSettings s = webView.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setAllowFileAccess(false);
        s.setCacheMode(WebSettings.LOAD_NO_CACHE);
        webView.addJavascriptInterface(new UsbBridge(), "AndroidUSB");

        int port = httpPort > 0 ? httpPort : 18088;
        webView.loadUrl("http://127.0.0.1:" + port + "/");
    }

    class UsbBridge {
        @JavascriptInterface
        public String connect() {
            if ("wifi".equals(connMode)) {
                new Thread(() -> connectWifi(wifiHost)).start();
                return "WIFI";
            }
            return connectUsb();
        }

        @JavascriptInterface
        public void showConnectionMenu() {
            mainHandler.post(() -> showConnMenu());
        }

        @JavascriptInterface
        public String getMode() {
            return connMode;
        }
    }

    private void showConnMenu() {
        String[] items = {"USB кабель (OTG)", "WiFi (PWMGen AP)"};
        new AlertDialog.Builder(this)
            .setTitle("Подключение")
            .setItems(items, (d, w) -> {
                if (w == 0) {
                    connMode = "usb";
                    savePrefs();
                    disconnectWifi();
                    String r = connectUsb();
                    if ("NO_DEVICE".equals(r)) {
                        Toast.makeText(this, "USB не найден", Toast.LENGTH_SHORT).show();
                        notifyJs("disconnected");
                    }
                } else {
                    showWifiDialog();
                }
            }).show();
    }

    private void showWifiDialog() {
        EditText input = new EditText(this);
        input.setInputType(android.text.InputType.TYPE_CLASS_TEXT);
        input.setText(wifiHost);
        input.setHint(DEFAULT_IP);
        new AlertDialog.Builder(this)
            .setTitle("IP адрес Pico")
            .setView(input)
            .setPositiveButton("Подключить", (d, w) -> {
                String ip = input.getText().toString().trim();
                if (!ip.isEmpty()) wifiHost = ip;
                connMode = "wifi";
                savePrefs();
                new Thread(() -> {
                    disconnectUsb();
                    connectWifi(wifiHost);
                }).start();
            })
            .setNegativeButton("Отмена", null).show();
    }

    private boolean isTransportUp() {
        if ("wifi".equals(connMode)) return wifiReading && wifiSocket != null;
        return usbReading && serialPort != null;
    }

    private void clearRx() {
        synchronized (rxLock) {
            rxLines.clear();
        }
    }

    private String lastStatusJson() {
        synchronized (rxLock) {
            for (int i = rxLines.size() - 1; i >= 0; i--) {
                String line = rxLines.get(i);
                if (line.startsWith("{")) return line;
            }
        }
        return "{}";
    }

    private void appendRxLine(String line) {
        synchronized (rxLock) {
            rxLines.add(line);
            if (rxLines.size() > 500) rxLines.remove(0);
        }
        if (line.startsWith("{")) {
            scheduleStatusPush(line);
        }
    }

    /** Коалесцируем всплески статуса: в JS уходит не чаще одного пуша за STATUS_PUSH_MS. */
    private void scheduleStatusPush(String json) {
        pendingStatusJson = json;
        if (statusPushScheduled) return;
        statusPushScheduled = true;
        mainHandler.postDelayed(() -> {
            statusPushScheduled = false;
            String j = pendingStatusJson;
            pendingStatusJson = null;
            if (j != null) notifyJs("data:" + j);
        }, STATUS_PUSH_MS);
    }

    /**
     * Как ПОБЕSДА на ПК: команда сразу уходит в TCP/USB — lock, write, flush.
     * Никакой очереди, никакого TX-потока, никакой задержки.
     */
    private void queueCmd(String key, String value) {
        if (key == null || key.isEmpty()) return;
        if (!isTransportUp()) return;
        writeLine(key + ":" + value + "\n");
    }

    private void writeLine(String line) {
        if ("wifi".equals(connMode)) {
            sendWifiLine(line);
        } else {
            sendUsb(line);
        }
    }

    private void onLinkUp() {
        if (linkUp) return;
        linkUp = true;
        writeFailCount = 0;
        mainHandler.post(() -> notifyJs("connected"));
    }

    private void onLinkDown() {
        if (!linkUp) return;
        linkUp = false;
        pendingStatusJson = null;
        clearRx();
        mainHandler.post(() -> notifyJs("disconnected"));
    }

    private String connectUsb() {
        List<UsbSerialDriver> drivers = UsbSerialProber.getDefaultProber().findAllDrivers(usbManager);
        if (drivers.isEmpty()) return "NO_DEVICE";
        UsbSerialDriver driver = drivers.get(0);
        UsbDevice device = driver.getDevice();
        if (!usbManager.hasPermission(device)) {
            PendingIntent pi = PendingIntent.getBroadcast(this, 0,
                new Intent(ACTION_USB_PERMISSION), PendingIntent.FLAG_IMMUTABLE);
            usbManager.requestPermission(device, pi);
            return "NO_PERMISSION";
        }
        UsbDeviceConnection conn = usbManager.openDevice(device);
        if (conn == null) return "NO_DEVICE";
        try {
            disconnectUsbQuiet();
            serialPort = driver.getPorts().get(0);
            serialPort.open(conn);
            serialPort.setParameters(BAUD_RATE, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE);
            connMode = "usb";
            savePrefs();
            clearRx();
            startUsbRead();
            onLinkUp();
            mainHandler.post(() -> Toast.makeText(this, "USB подключён", Toast.LENGTH_SHORT).show());
            return "OK";
        } catch (IOException e) {
            return "ERROR";
        }
    }

    private void sendUsb(String data) {
        UsbSerialPort port = serialPort;
        if (port == null) return;
        synchronized (sendLock) {
            try {
                port.write(data.getBytes(StandardCharsets.UTF_8), USB_WRITE_TIMEOUT);
                writeFailCount = 0;
            } catch (IOException e) {
                // Одиночный таймаут/сбой не рвём связь — рвём только при серии подряд.
                if (++writeFailCount >= MAX_WRITE_FAILS) {
                    usbReading = false;
                    onLinkDown();
                }
            }
        }
    }

    private void disconnectUsbQuiet() {
        usbReading = false;
        if (serialPort != null) {
            try {
                serialPort.close();
            } catch (IOException ignored) {}
            serialPort = null;
        }
        usbReadBuf.setLength(0);
    }

    private void disconnectUsb() {
        disconnectUsbQuiet();
        if ("usb".equals(connMode)) onLinkDown();
    }

    private void startUsbRead() {
        usbReading = true;
        new Thread(() -> {
            byte[] buf = new byte[512];
            while (usbReading && serialPort != null) {
                try {
                    int n = serialPort.read(buf, 300);
                    if (n > 0) {
                        processIncoming(new String(buf, 0, n, StandardCharsets.UTF_8), usbReadBuf);
                    }
                } catch (IOException e) {
                    break;
                }
            }
            if (usbReading) {
                usbReading = false;
                onLinkDown();
            }
        }, "pwmgen-usb-rx").start();
    }

    private void connectWifi(String host) {
        try {
            disconnectWifiQuiet();
            Socket sock = new Socket();
            sock.connect(new InetSocketAddress(host, WIFI_PORT), 8000);
            sock.setTcpNoDelay(true);
            sock.setSoTimeout(0);
            wifiSocket = sock;
            wifiOut = sock.getOutputStream();
            wifiReading = true;
            wifiHost = host;
            connMode = "wifi";
            savePrefs();
            clearRx();
            startWifiRead();
            onLinkUp();
            mainHandler.post(() -> Toast.makeText(this, "WiFi: " + host, Toast.LENGTH_SHORT).show());
        } catch (Exception e) {
            mainHandler.post(() -> {
                Toast.makeText(this, "WiFi: " + e.getMessage(), Toast.LENGTH_LONG).show();
                onLinkDown();
            });
        }
    }

    private void sendWifiLine(String line) {
        OutputStream out = wifiOut;
        if (out == null) return;
        synchronized (sendLock) {
            try {
                out.write(line.getBytes(StandardCharsets.UTF_8));
                out.flush();
                writeFailCount = 0;
            } catch (IOException e) {
                // Одиночный сбой записи не рвём связь — рвём только при серии подряд.
                if (++writeFailCount >= MAX_WRITE_FAILS) {
                    wifiReading = false;
                    onLinkDown();
                    disconnectWifiQuiet();
                }
            }
        }
    }

    private void disconnectWifiQuiet() {
        wifiReading = false;
        try {
            if (wifiSocket != null) wifiSocket.close();
        } catch (Exception ignored) {}
        wifiSocket = null;
        wifiOut = null;
    }

    private void disconnectWifi() {
        disconnectWifiQuiet();
        if ("wifi".equals(connMode)) onLinkDown();
    }

    private void startWifiRead() {
        final StringBuilder wifiBuf = new StringBuilder();
        new Thread(() -> {
            byte[] buf = new byte[512];
            while (wifiReading && wifiSocket != null) {
                try {
                    InputStream in = wifiSocket.getInputStream();
                    int n = in.read(buf);
                    if (n > 0) {
                        processIncoming(new String(buf, 0, n, StandardCharsets.UTF_8), wifiBuf);
                    } else {
                        break;
                    }
                } catch (Exception e) {
                    break;
                }
            }
            if (wifiReading) {
                wifiReading = false;
                onLinkDown();
            }
        }, "pwmgen-wifi-rx").start();
    }

    private void processIncoming(String chunk, StringBuilder buf) {
        buf.append(chunk);
        int idx;
        while ((idx = buf.indexOf("\n")) >= 0) {
            String line = buf.substring(0, idx).trim();
            buf.delete(0, idx + 1);
            if (!line.isEmpty()) appendRxLine(line);
        }
    }

    private void notifyJs(String event) {
        if (webView == null) return;
        String safe = event.replace("\\", "\\\\")
            .replace("'", "\\'")
            .replace("\r", "")
            .replace("\n", "\\n");
        webView.evaluateJavascript(
            "window.onUsbEvent&&window.onUsbEvent('" + safe + "')", null);
    }

    private final BroadcastReceiver usbReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context ctx, Intent intent) {
            String action = intent.getAction();
            if (ACTION_USB_PERMISSION.equals(action)) {
                if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) {
                    connectUsb();
                }
            } else if (UsbManager.ACTION_USB_DEVICE_DETACHED.equals(action)) {
                disconnectUsb();
            }
        }
    };

    private void registerUsbReceiver() {
        IntentFilter f = new IntentFilter();
        f.addAction(ACTION_USB_PERMISSION);
        f.addAction(UsbManager.ACTION_USB_DEVICE_DETACHED);
        registerReceiver(usbReceiver, f);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (proxyServer != null) proxyServer.stop();
        disconnectUsbQuiet();
        disconnectWifiQuiet();
        try {
            unregisterReceiver(usbReceiver);
        } catch (Exception ignored) {}
    }
}
