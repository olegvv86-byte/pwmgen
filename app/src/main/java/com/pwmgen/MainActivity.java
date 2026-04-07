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
import android.text.InputType;
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
import java.net.Socket;
import java.util.List;

public class MainActivity extends Activity {

    private static final String ACTION_USB_PERMISSION = "com.pwmgen.USB_PERMISSION";
    private static final int BAUD_RATE = 115200;
    private static final int WIFI_PORT = 8888;

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
        WebSettings s = webView.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setAllowFileAccess(true);
        webView.addJavascriptInterface(new UsbBridge(), "AndroidUSB");
        webView.setWebViewClient(new WebViewClient());
        webView.loadUrl("file:///android_asset/index.html");
    }

    class UsbBridge {
        @JavascriptInterface
        public String connect() {
            if (connMode.equals("wifi")) {
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
            if (connMode.equals("wifi")) {
                sendWifi(data);
            } else {
                sendUsb(data);
            }
        }

        @JavascriptInterface
        public String getMode() { return connMode; }
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
                new Thread(() -> { disconnectUsb(); connectWifi(wifiHost); }).start();
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
        } catch (IOException e) { return "ERROR"; }
    }

    private void sendUsb(String data) {
        if (serialPort == null) return;
        try { serialPort.write(data.getBytes(), 200); } catch (IOException ignored) {}
    }

    private void disconnectUsb() {
        reading = false;
        if (serialPort != null) {
            try { serialPort.close(); } catch (IOException ignored) {}
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
                    if (n > 0) processIncoming(new String(buf, 0, n), readBuf);
                } catch (IOException e) { reading = false; }
            }
        }).start();
    }

    private void connectWifi(String host) {
        try {
            disconnectWifi();
            wifiSocket = new Socket(host, WIFI_PORT);
            wifiOut = wifiSocket.getOutputStream();
            wifiReading = true;
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

    private void sendWifi(String data) {
        new Thread(() -> {
            try {
                if (wifiOut != null) {
                    synchronized (wifiOut) {
                        wifiOut.write(data.getBytes());
                        wifiOut.flush();
                    }
                }
            } catch (Exception e) {
                mainHandler.post(() -> notifyJs("disconnected"));
            }
        }).start();
    }

    private void disconnectWifi() {
        wifiReading = false;
        try { if (wifiSocket != null) wifiSocket.close(); } catch (Exception ignored) {}
        wifiSocket = null;
        wifiOut = null;
    }

    private void startWifiRead() {
        StringBuilder wifiBuf = new StringBuilder();
        new Thread(() -> {
            byte[] buf = new byte[256];
            while (wifiReading && wifiSocket != null) {
                try {
                    InputStream in = wifiSocket.getInputStream();
                    int n = in.read(buf);
                    if (n > 0) processIncoming(new String(buf, 0, n), wifiBuf);
                    else break;
                } catch (Exception e) { break; }
            }
            if (wifiReading) {
                wifiReading = false;
                mainHandler.post(() -> notifyJs("disconnected"));
            }
        }).start();
    }

    private void processIncoming(String data, StringBuilder buf) {
        buf.append(data);
        int idx;
        while ((idx = buf.indexOf("\n")) >= 0) {
            String line = buf.substring(0, idx).trim();
            buf.delete(0, idx + 1);
            if (!line.isEmpty()) {
                final String fl = line;
                mainHandler.post(() -> notifyJs("data:" + fl));
            }
        }
    }

    private void notifyJs(String event) {
        webView.evaluateJavascript(
            "window.onUsbEvent && window.onUsbEvent('" + event + "')", null);
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
        try { unregisterReceiver(usbReceiver); } catch (Exception ignored) {}
    }
}
