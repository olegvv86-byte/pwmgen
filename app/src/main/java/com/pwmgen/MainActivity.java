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
    private static final String WIFI_DEFAULT_HOST = "192.168.4.1";
    private static final int WIFI_PORT = 8888;

    private WebView webView;
    private UsbManager usbManager;
    private UsbSerialPort serialPort;
    private Handler mainHandler;
    private Thread readThread;
    private volatile boolean reading = false;
    private StringBuilder readBuf = new StringBuilder();

    private Socket wifiSocket;
    private OutputStream wifiOut;
    private InputStream wifiIn;
    private Thread wifiReadThread;
    private volatile boolean wifiReading = false;
    private String wifiHost = WIFI_DEFAULT_HOST;
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

    public class UsbBridge {
        @JavascriptInterface
        public String connect() {
            if (connMode.equals("wifi")) {
                new Thread(() -> connectWifi(wifiHost)).start();
                return "WIFI";
            }
            try { return connectUsb(); } catch (Exception e) { return "ERROR: " + e.getMessage(); }
        }

        @JavascriptInterface
        public void send(String data) {
            if (connMode.equals("wifi")) sendWifi(data);
            else try { sendSerial(data); } catch (Exception e) {}
        }

        @JavascriptInterface
        public void disconnect() {
            if (connMode.equals("wifi")) new Thread(() -> disconnectWifi()).start();
            else disconnectUsb();
        }

        @JavascriptInterface
        public boolean isConnected() {
            if (connMode.equals("wifi")) return wifiSocket != null && wifiSocket.isConnected();
            return serialPort != null;
        }

        @JavascriptInterface
        public String getMode() { return connMode; }

        @JavascriptInterface
        public void showConnectionMenu() { mainHandler.post(() -> showConnMenu()); }
    }

    private void showConnMenu() {
        new AlertDialog.Builder(this)
            .setTitle("Подключение")
            .setItems(new String[]{"USB кабель", "WiFi (PWMGen)"}, (dialog, which) -> {
                if (which == 0) {
                    connMode = "usb";
                    new Thread(() -> { disconnectWifi(); connectUsb(); }).start();
                } else {
                    showWifiIpDialog();
                }
            }).show();
    }

    private void showWifiIpDialog() {
        EditText input = new EditText(this);
        input.setInputType(InputType.TYPE_CLASS_TEXT);
        input.setText(wifiHost);
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
            PendingIntent pi = PendingIntent.getBroadcast(this, 0, new Intent(ACTION_USB_PERMISSION), PendingIntent.FLAG_MUTABLE);
            usbManager.requestPermission(device, pi);
            return "REQUESTING_PERMISSION";
        }
        try {
            UsbDeviceConnection connection = usbManager.openDevice(device);
            if (connection == null) return "OPEN_FAILED";
            serialPort = driver.getPorts().get(0);
            serialPort.open(connection);
            serialPort.setParameters(BAUD_RATE, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE);
            serialPort.setDTR(true);
            serialPort.setRTS(true);
        } catch (IOException e) { return "PORT_ERROR: " + e.getMessage(); }
        startReading();
        notifyJs("connected");
        return "OK";
    }

    private void startReading() {
        reading = true;
        readThread = new Thread(() -> {
            byte[] buf = new byte[256];
            while (reading && serialPort != null) {
                try {
                    int n = serialPort.read(buf, 200);
                    if (n > 0) {
                        readBuf.append(new String(buf, 0, n));
                        int idx;
                        while ((idx = readBuf.indexOf("\n")) >= 0) {
                            String line = readBuf.substring(0, idx).trim();
                            readBuf.delete(0, idx + 1);
                            if (!line.isEmpty()) { final String l = line; mainHandler.post(() -> notifyJs("data:" + l)); }
                        }
                    }
                } catch (IOException e) { if (reading) mainHandler.post(() -> notifyJs("error:" + e.getMessage())); break; }
            }
        });
        readThread.start();
    }

    private void sendSerial(String data) {
        if (serialPort == null) return;
        new Thread(() -> { try { serialPort.write(data.getBytes(), 1000); } catch (IOException e) {} }).start();
    }

    private void disconnectUsb() {
        reading = false;
        if (serialPort != null) { try { serialPort.close(); } catch (IOException e) {} serialPort = null; }
        mainHandler.post(() -> notifyJs("disconnected"));
    }

    private void connectWifi(String host) {
        try {
            if (wifiSocket != null) { try { wifiSocket.close(); } catch (Exception e) {} }
            wifiSocket = new Socket(host, WIFI_PORT);
            wifiOut = wifiSocket.getOutputStream();
            wifiIn  = wifiSocket.getInputStream();
            mainHandler.post(() -> {
                Toast.makeText(MainActivity.this, "WiFi подключён!", Toast.LENGTH_SHORT).show();
                notifyJs("connected");
            });
            startWifiRead();
        } catch (Exception e) {
            mainHandler.post(() -> {
                Toast.makeText(MainActivity.this, "Ошибка: " + e.getMessage(), Toast.LENGTH_LONG).show();
                notifyJs("disconnected");
            });
        }
    }

    private void sendWifi(String data) {
        new Thread(() -> { try { if (wifiOut != null) wifiOut.write(data.getBytes()); } catch (Exception e) { mainHandler.post(() -> notifyJs("disconnected")); } }).start();
    }

    private void disconnectWifi() {
        wifiReading = false;
        try { if (wifiSocket != null) wifiSocket.close(); } catch (Exception e) {}
        wifiSocket = null; wifiOut = null; wifiIn = null;
    }

    private void startWifiRead() {
        wifiReading = true;
        wifiReadThread = new Thread(() -> {
            byte[] buf = new byte[256];
            StringBuilder sb = new StringBuilder();
            while (wifiReading && wifiIn != null) {
                try {
                    int n = wifiIn.read(buf);
                    if (n > 0) {
                        sb.append(new String(buf, 0, n));
                        int idx;
                        while ((idx = sb.indexOf("\n")) >= 0) {
                            String line = sb.substring(0, idx).trim();
                            sb.delete(0, idx + 1);
                            if (!line.isEmpty()) { final String fl = line; mainHandler.post(() -> notifyJs("data:" + fl)); }
                        }
                    } else break;
                } catch (Exception e) { break; }
            }
            mainHandler.post(() -> notifyJs("disconnected"));
        });
        wifiReadThread.start();
    }

    private void notifyJs(String event) {
        String escaped = event.replace("'", "\\'").replace("\n", "\\n");
        mainHandler.post(() -> webView.evaluateJavascript("if(window.onUsbEvent) window.onUsbEvent('" + escaped + "');", null));
    }

    private final BroadcastReceiver usbReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (ACTION_USB_PERMISSION.equals(action)) {
                if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) connectUsb();
                else notifyJs("error:Permission denied");
            } else if (UsbManager.ACTION_USB_DEVICE_ATTACHED.equals(action)) {
                mainHandler.postDelayed(() -> connectUsb(), 500);
            } else if (UsbManager.ACTION_USB_DEVICE_DETACHED.equals(action)) {
                disconnectUsb();
            }
        }
    };

    private void registerUsbReceiver() {
        IntentFilter filter = new IntentFilter();
        filter.addAction(ACTION_USB_PERMISSION);
        filter.addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED);
        filter.addAction(UsbManager.ACTION_USB_DEVICE_DETACHED);
        registerReceiver(usbReceiver, filter, RECEIVER_EXPORTED);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        disconnectUsb();
        disconnectWifi();
        try { unregisterReceiver(usbReceiver); } catch (Exception e) {}
    }

    @Override
    public void onBackPressed() {
        if (webView.canGoBack()) webView.goBack();
        else super.onBackPressed();
    }
}
