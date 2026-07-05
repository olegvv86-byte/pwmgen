package com.pwmgen;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
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
import android.webkit.WebViewClient;
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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

public class MainActivity extends Activity {

    private static final String ACTION_USB_PERMISSION = "com.pwmgen.USB_PERMISSION";
    private static final int BAUD_RATE = 115200;
    private static final int WIFI_PORT = 8888;
    private static final int WIFI_STATUS_MIN_MS = 500;

    private WebView webView;
    private UsbManager usbManager;
    private UsbSerialPort serialPort;
    private Handler mainHandler;
    private volatile boolean reading = false;
    private StringBuilder readBuf = new StringBuilder();

    private Socket wifiSocket;
    private OutputStream wifiOut;
    private volatile boolean wifiReading = false;
    private String wifiHost = "192.168.4.1";
    private String connMode = "usb";

    private volatile boolean sliderBusy = false;
    private long lastStatusJsMs = 0;
    private String pendingStatusJson = null;

    private final Object wifiCmdLock = new Object();
    private final HashMap<String, String> wifiCmdLatest = new HashMap<>();
    private final LinkedBlockingQueue<Object> wifiSendSignal = new LinkedBlockingQueue<>();
    private volatile boolean wifiSendRunning = false;

    private final Runnable deliverStatusRunnable = new Runnable() {
        @Override
        public void run() {
            if (sliderBusy || pendingStatusJson == null) return;
            long now = System.currentTimeMillis();
            long wait = WIFI_STATUS_MIN_MS - (now - lastStatusJsMs);
            if (wait > 0) {
                mainHandler.postDelayed(this, wait);
                return;
            }
            String line = pendingStatusJson;
            pendingStatusJson = null;
            lastStatusJsMs = now;
            notifyJs("data:" + line);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        setContentView(R.layout.activity_main);
        mainHandler = new Handler(Looper.getMainLooper());
        usbManager = (UsbManager) getSystemService(Context.USB_SERVICE);
        setupWebView();
        registerUsbReceiver();
    }

    private void setupWebView() {
        webView = findViewById(R.id.webview);
        webView.setLayerType(View.LAYER_TYPE_HARDWARE, null);
        WebSettings s = webView.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setAllowFileAccess(true);
        s.setCacheMode(WebSettings.LOAD_NO_CACHE);
        webView.addJavascriptInterface(new UsbBridge(), "AndroidUSB");
        webView.setWebViewClient(new WebViewClient());
        webView.loadUrl("file:///android_asset/index.html");
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
        public void send(String data) {
            if ("wifi".equals(connMode)) {
                sendWifi(data);
            } else {
                sendUsb(data);
            }
        }

        @JavascriptInterface
        public String getMode() {
            return connMode;
        }

        @JavascriptInterface
        public void setSliderBusy(boolean busy) {
            sliderBusy = busy;
            if (!busy) {
                scheduleStatusDelivery();
            }
        }
    }

    private void scheduleStatusDelivery() {
        if (pendingStatusJson == null || sliderBusy) return;
        mainHandler.removeCallbacks(deliverStatusRunnable);
        mainHandler.post(deliverStatusRunnable);
    }

    private void showConnMenu() {
        String[] items = {"USB кабель", "WiFi (PWMGen)"};
        new AlertDialog.Builder(this)
            .setTitle("Подключение")
            .setItems(items, (d, w) -> {
                if (w == 0) {
                    connMode = "usb";
                    disconnectWifi();
                    String r = connectUsb();
                    notifyJs(r.equals("NO_DEVICE") ? "disconnected" : "connected");
                } else {
                    showWifiDialog();
                }
            }).show();
    }

    private void showWifiDialog() {
        EditText input = new EditText(this);
        input.setInputType(android.text.InputType.TYPE_CLASS_TEXT);
        input.setText(wifiHost);
        input.setHint("192.168.4.1");
        new AlertDialog.Builder(this)
            .setTitle("IP адрес Pico W")
            .setView(input)
            .setPositiveButton("Подключить", (d, w) -> {
                String ip = input.getText().toString().trim();
                if (!ip.isEmpty()) wifiHost = ip;
                connMode = "wifi";
                new Thread(() -> {
                    disconnectUsb();
                    connectWifi(wifiHost);
                }).start();
            })
            .setNegativeButton("Отмена", null).show();
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
            serialPort = driver.getPorts().get(0);
            serialPort.open(conn);
            serialPort.setParameters(BAUD_RATE, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE);
            startUsbRead();
            notifyJs("connected");
            return "OK";
        } catch (IOException e) {
            return "ERROR";
        }
    }

    private void sendUsb(String data) {
        if (serialPort == null) return;
        try {
            serialPort.write(data.getBytes(), 200);
        } catch (IOException ignored) {}
    }

    private void disconnectUsb() {
        reading = false;
        if (serialPort != null) {
            try {
                serialPort.close();
            } catch (IOException ignored) {}
            serialPort = null;
        }
    }

    private void startUsbRead() {
        reading = true;
        new Thread(() -> {
            byte[] buf = new byte[256];
            while (reading && serialPort != null) {
                try {
                    int n = serialPort.read(buf, 200);
                    if (n > 0) processIncoming(new String(buf, 0, n), readBuf, false);
                } catch (IOException e) {
                    reading = false;
                }
            }
        }).start();
    }

    private void connectWifi(String host) {
        try {
            disconnectWifi();
            Socket sock = new Socket();
            sock.connect(new InetSocketAddress(host, WIFI_PORT), 5000);
            sock.setTcpNoDelay(true);
            sock.setSoTimeout(0);
            wifiSocket = sock;
            wifiOut = sock.getOutputStream();
            wifiReading = true;
            startWifiSendThread();
            startWifiRead();
            mainHandler.post(() -> {
                Toast.makeText(this, "WiFi подключён!", Toast.LENGTH_SHORT).show();
                notifyJs("connected");
            });
        } catch (Exception e) {
            mainHandler.post(() -> {
                Toast.makeText(this, "Ошибка: " + e.getMessage(), Toast.LENGTH_LONG).show();
                notifyJs("disconnected");
            });
        }
    }

    private void startWifiSendThread() {
        if (wifiSendRunning) return;
        wifiSendRunning = true;
        new Thread(() -> {
            while (wifiSendRunning) {
                try {
                    wifiSendSignal.poll(25, TimeUnit.MILLISECONDS);
                    ArrayList<String> batch;
                    synchronized (wifiCmdLock) {
                        if (wifiCmdLatest.isEmpty()) continue;
                        batch = new ArrayList<>(wifiCmdLatest.values());
                        wifiCmdLatest.clear();
                    }
                    OutputStream out = wifiOut;
                    if (out == null) continue;
                    for (String cmd : batch) {
                        out.write(cmd.getBytes());
                    }
                    out.flush();
                } catch (InterruptedException e) {
                    break;
                } catch (Exception e) {
                    mainHandler.post(() -> notifyJs("disconnected"));
                    disconnectWifi();
                    break;
                }
            }
        }).start();
    }

    private void sendWifi(String data) {
        if (!wifiSendRunning || wifiOut == null || data == null) return;
        String line = data.endsWith("\n") ? data : data + "\n";
        int c = line.indexOf(':');
        String key = c > 0 ? line.substring(0, c) : line;
        synchronized (wifiCmdLock) {
            wifiCmdLatest.put(key, line);
        }
        wifiSendSignal.offer(Boolean.TRUE);
    }

    private void disconnectWifi() {
        wifiReading = false;
        wifiSendRunning = false;
        wifiSendSignal.clear();
        synchronized (wifiCmdLock) {
            wifiCmdLatest.clear();
        }
        pendingStatusJson = null;
        mainHandler.removeCallbacks(deliverStatusRunnable);
        try {
            if (wifiSocket != null) wifiSocket.close();
        } catch (Exception ignored) {}
        wifiSocket = null;
        wifiOut = null;
    }

    private void startWifiRead() {
        StringBuilder wifiBuf = new StringBuilder();
        new Thread(() -> {
            byte[] buf = new byte[512];
            while (wifiReading && wifiSocket != null) {
                try {
                    InputStream in = wifiSocket.getInputStream();
                    int n = in.read(buf);
                    if (n > 0) processIncoming(new String(buf, 0, n), wifiBuf, true);
                    else break;
                } catch (Exception e) {
                    break;
                }
            }
            if (wifiReading) {
                wifiReading = false;
                mainHandler.post(() -> notifyJs("disconnected"));
            }
        }).start();
    }

    private void processIncoming(String data, StringBuilder buf, boolean wifi) {
        buf.append(data);
        int idx;
        while ((idx = buf.indexOf("\n")) >= 0) {
            String line = buf.substring(0, idx).trim();
            buf.delete(0, idx + 1);
            if (line.isEmpty()) continue;
            if (wifi && line.startsWith("{")) {
                pendingStatusJson = line;
                if (!sliderBusy) {
                    scheduleStatusDelivery();
                }
                continue;
            }
            final String fl = line;
            mainHandler.post(() -> notifyJs("data:" + fl));
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
                    String r = connectUsb();
                    if (!"NO_DEVICE".equals(r)) notifyJs("connected");
                }
            } else if (UsbManager.ACTION_USB_DEVICE_DETACHED.equals(action)) {
                disconnectUsb();
                notifyJs("disconnected");
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
        disconnectUsb();
        disconnectWifi();
        try {
            unregisterReceiver(usbReceiver);
        } catch (Exception ignored) {}
    }
}
