package com.pwmgen;

import android.app.Activity;
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
import android.webkit.JavascriptInterface;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.view.WindowManager;

import com.hoho.android.usbserial.driver.UsbSerialDriver;
import com.hoho.android.usbserial.driver.UsbSerialPort;
import com.hoho.android.usbserial.driver.UsbSerialProber;

import java.io.IOException;
import java.util.List;

public class MainActivity extends Activity {

    private static final String ACTION_USB_PERMISSION = "com.pwmgen.USB_PERMISSION";
    private static final int BAUD_RATE = 115200;

    private WebView webView;
    private UsbManager usbManager;
    private UsbSerialPort serialPort;
    private Handler mainHandler;
    private Thread readThread;
    private volatile boolean reading = false;
    private StringBuilder readBuf = new StringBuilder();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Полноэкранный режим
        getWindow().setFlags(
            WindowManager.LayoutParams.FLAG_FULLSCREEN,
            WindowManager.LayoutParams.FLAG_FULLSCREEN
        );
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        setContentView(R.layout.activity_main);

        mainHandler = new Handler(Looper.getMainLooper());
        usbManager = (UsbManager) getSystemService(Context.USB_SERVICE);

        setupWebView();
        registerUsbReceiver();

        // Если запущено через USB attach
        if (getIntent().getAction() != null &&
            getIntent().getAction().equals("android.hardware.usb.action.USB_DEVICE_ATTACHED")) {
            connectUsb();
        }
    }

    private void setupWebView() {
        webView = findViewById(R.id.webview);
        WebSettings s = webView.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setAllowFileAccess(true);
        s.setMediaPlaybackRequiresUserGesture(false);

        // Мост JS ↔ Java для USB Serial
        webView.addJavascriptInterface(new UsbBridge(), "AndroidUSB");
        webView.setWebViewClient(new WebViewClient());

        // Загружаем наш HTML из assets
        webView.loadUrl("file:///android_asset/index.html");
    }

    // ─── USB Serial Bridge (вызывается из JS) ───
    public class UsbBridge {

        @JavascriptInterface
        public String connect() {
            return connectUsb();
        }

        @JavascriptInterface
        public void send(String data) {
            sendSerial(data);
        }

        @JavascriptInterface
        public void disconnect() {
            disconnectUsb();
        }

        @JavascriptInterface
        public boolean isConnected() {
            return serialPort != null;
        }
    }

    private String connectUsb() {
        List<UsbSerialDriver> drivers =
            UsbSerialProber.getDefaultProber().findAllDrivers(usbManager);

        if (drivers.isEmpty()) {
            return "NO_DEVICE";
        }

        UsbSerialDriver driver = drivers.get(0);
        UsbDevice device = driver.getDevice();

        if (!usbManager.hasPermission(device)) {
            PendingIntent pi = PendingIntent.getBroadcast(
                this, 0,
                new Intent(ACTION_USB_PERMISSION),
                PendingIntent.FLAG_IMMUTABLE
            );
            usbManager.requestPermission(device, pi);
            return "REQUESTING_PERMISSION";
        }

        UsbDeviceConnection connection = usbManager.openDevice(device);
        if (connection == null) return "OPEN_FAILED";

        serialPort = driver.getPorts().get(0);
        try {
            serialPort.open(connection);
            serialPort.setParameters(BAUD_RATE, 8,
                UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE);
            serialPort.setDTR(true);
            serialPort.setRTS(true);
        } catch (IOException e) {
            return "PORT_ERROR: " + e.getMessage();
        }

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
                        String chunk = new String(buf, 0, n);
                        readBuf.append(chunk);
                        // Обрабатываем строки
                        int idx;
                        while ((idx = readBuf.indexOf("\n")) >= 0) {
                            String line = readBuf.substring(0, idx).trim();
                            readBuf.delete(0, idx + 1);
                            if (!line.isEmpty()) {
                                final String l = line;
                                mainHandler.post(() -> notifyJs("data:" + l));
                            }
                        }
                    }
                } catch (IOException e) {
                    if (reading) {
                        mainHandler.post(() -> notifyJs("error:" + e.getMessage()));
                    }
                    break;
                }
            }
        });
        readThread.start();
    }

    private void sendSerial(String data) {
        if (serialPort == null) return;
        new Thread(() -> {
            try {
                serialPort.write(data.getBytes(), 1000);
            } catch (IOException e) {
                mainHandler.post(() -> notifyJs("error:" + e.getMessage()));
            }
        }).start();
    }

    private void disconnectUsb() {
        reading = false;
        if (serialPort != null) {
            try { serialPort.close(); } catch (IOException e) { }
            serialPort = null;
        }
        notifyJs("disconnected");
    }

    private void notifyJs(String event) {
        String escaped = event.replace("'", "\\'").replace("\n", "\\n");
        webView.evaluateJavascript(
            "if(window.onUsbEvent) window.onUsbEvent('" + escaped + "');",
            null
        );
    }

    // ─── USB Permission receiver ───
    private final BroadcastReceiver usbReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (ACTION_USB_PERMISSION.equals(action)) {
                UsbDevice device = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
                if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) {
                    connectUsb();
                }
            } else if (UsbManager.ACTION_USB_DEVICE_ATTACHED.equals(action)) {
                connectUsb();
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
        registerReceiver(usbReceiver, filter);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        disconnectUsb();
        unregisterReceiver(usbReceiver);
    }

    @Override
    public void onBackPressed() {
        if (webView.canGoBack()) {
            webView.goBack();
        } else {
            super.onBackPressed();
        }
    }
}
