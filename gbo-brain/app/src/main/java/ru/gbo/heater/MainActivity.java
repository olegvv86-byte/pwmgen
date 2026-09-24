package ru.gbo.heater;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.net.wifi.WifiManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Vibrator;
import android.text.InputType;
import android.view.View;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.widget.EditText;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.URL;
import java.util.Collections;
import java.util.Enumeration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * GBO HEATER — обёртка по образцу pwmgen: весь интерфейс в assets/index.html,
 * Java отвечает только за транспорт.
 *
 * Плата не отдаёт CORS-заголовков, поэтому HTTP делает Java, а в WebView
 * результат приезжает через evaluateJavascript. Страница грузится из assets
 * и сама в сеть не ходит.
 */
public class MainActivity extends Activity {

    private static final String PREFS = "gboheater";
    private static final String KEY_IP = "board_ip";
    private static final String AP_IP = "192.168.4.1";

    private static final int POLL_MS = 1000;     // опрос /api, ТЗ 6
    private static final int RETRY_MS = 3000;    // ретрай при потере связи, ТЗ 3
    private static final int TO_POLL = 2500;
    private static final int TO_PROBE = 1500;    // проверка известного адреса
    private static final int TO_SCAN = 300;      // скан подсети
    private static final int SCAN_THREADS = 20;
    private static final int LOST_BEFORE_RESCAN = 3;

    private WebView webView;
    private Handler main;
    private SharedPreferences prefs;
    private Vibrator vibrator;

    private volatile String host = null;
    private volatile boolean running = true;
    private volatile boolean paused = true;
    private volatile boolean pageReady = false;
    private volatile boolean forceRescan = false;
    private volatile boolean lastLatched = false;
    private volatile boolean lastWarm = false;

    private final Object wake = new Object();
    private ExecutorService cmdPool;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        setContentView(R.layout.activity_main);

        main = new Handler(Looper.getMainLooper());
        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        vibrator = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
        cmdPool = Executors.newFixedThreadPool(2);

        host = prefs.getString(KEY_IP, null);

        setupWebView();
        new Thread(new Runnable() {
            @Override
            public void run() {
                netLoop();
            }
        }, "gbo-net").start();
    }

    @Override
    protected void onResume() {
        super.onResume();
        paused = false;
        wakeNet();
    }

    @Override
    protected void onPause() {
        super.onPause();
        paused = true;
    }

    @Override
    protected void onDestroy() {
        running = false;
        wakeNet();
        cmdPool.shutdownNow();
        super.onDestroy();
    }

    private void setupWebView() {
        webView = findViewById(R.id.webview);
        webView.setLayerType(View.LAYER_TYPE_HARDWARE, null);
        webView.setBackgroundColor(0xFF040408);
        WebSettings s = webView.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setCacheMode(WebSettings.LOAD_NO_CACHE);
        webView.addJavascriptInterface(new Bridge(), "AndroidGBO");
        webView.setWebViewClient(new android.webkit.WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String url) {
                pageReady = true;
                wakeNet();
            }
        });
        webView.loadUrl("file:///android_asset/index.html");
    }

    // ─────────────────────────── мост в JS ───────────────────────────

    private void js(final String code) {
        if (!pageReady) return;
        main.post(new Runnable() {
            @Override
            public void run() {
                try {
                    webView.evaluateJavascript(code, null);
                } catch (Throwable ignored) {
                }
            }
        });
    }

    private static String q(String s) {
        if (s == null) return "null";
        StringBuilder b = new StringBuilder("\"");
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '"' || c == '\\') b.append('\\').append(c);
            else if (c == '\n') b.append("\\n");
            else if (c < 0x20) b.append(' ');
            else b.append(c);
        }
        return b.append('"').toString();
    }

    private void pushNet(String state) {
        js("window.onNet && onNet(" + q(state) + "," + q(host) + ")");
    }

    class Bridge {
        /** Уставки на плату: /set?sp=&pw=&ct= */
        @JavascriptInterface
        public void setParams(final int sp, final int pw, final int ct) {
            final String h = host;
            if (h == null) return;
            cmdPool.execute(new Runnable() {
                @Override
                public void run() {
                    String r = httpGet("http://" + h + "/set?sp=" + sp + "&pw=" + pw + "&ct=" + ct, TO_POLL);
                    js("window.onSetDone && onSetDone(" + (r != null ? "true" : "false") + ")");
                }
            });
        }

        /** Кнопка Power: рабочий режим платы, состояние остаётся в её памяти */
        @JavascriptInterface
        public void setEnabled(final boolean on) {
            final String h = host;
            if (h == null) return;
            cmdPool.execute(new Runnable() {
                @Override
                public void run() {
                    String r = httpGet("http://" + h + "/set?en=" + (on ? 1 : 0), TO_POLL);
                    js("window.onSetDone && onSetDone(" + (r != null ? "true" : "false") + ")");
                }
            });
        }

        /** Принудительный нагрев: снимает отсечку по редуктору */
        @JavascriptInterface
        public void setForce(final boolean on) {
            final String h = host;
            if (h == null) return;
            cmdPool.execute(new Runnable() {
                @Override
                public void run() {
                    String r = httpGet("http://" + h + "/set?force=" + (on ? 1 : 0), TO_POLL);
                    js("window.onSetDone && onSetDone(" + (r != null ? "true" : "false") + ")");
                }
            });
        }

        /** Сброс защёлки: ok — сброшено, hot — пластина ещё горячая */
        @JavascriptInterface
        public void resetFault() {
            final String h = host;
            if (h == null) return;
            cmdPool.execute(new Runnable() {
                @Override
                public void run() {
                    String r = httpGet("http://" + h + "/reset", TO_POLL);
                    String res = (r == null) ? "err" : (r.trim().startsWith("hot") ? "hot" : "ok");
                    js("window.onReset && onReset(" + q(res) + ")");
                }
            });
        }

        /** Повторить поиск платы */
        @JavascriptInterface
        public void rescan() {
            forceRescan = true;
            host = null;
            wakeNet();
        }

        /** Ручной ввод адреса — диалог рисует Android, чтобы не городить клавиатуру в WebView */
        @JavascriptInterface
        public void askIp() {
            main.post(new Runnable() {
                @Override
                public void run() {
                    showIpDialog();
                }
            });
        }

        @JavascriptInterface
        public String getIp() {
            return host == null ? "" : host;
        }
    }

    private void showIpDialog() {
        final EditText in = new EditText(this);
        in.setInputType(InputType.TYPE_CLASS_TEXT);
        in.setHint("192.168.4.1");
        in.setText(host == null ? "" : host);
        new AlertDialog.Builder(this)
                .setTitle("Адрес платы")
                .setView(in)
                .setPositiveButton("OK", new android.content.DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(android.content.DialogInterface d, int w) {
                        String ip = in.getText().toString().trim();
                        if (ip.length() == 0) return;
                        host = ip;
                        prefs.edit().putString(KEY_IP, ip).apply();
                        forceRescan = false;
                        wakeNet();
                    }
                })
                .setNegativeButton("Отмена", null)
                .show();
    }

    // ─────────────────────────── сеть ───────────────────────────

    private void wakeNet() {
        synchronized (wake) {
            wake.notifyAll();
        }
    }

    private void sleepNet(long ms) {
        synchronized (wake) {
            try {
                wake.wait(ms);
            } catch (InterruptedException ignored) {
            }
        }
    }

    private void netLoop() {
        int lost = 0;
        while (running) {
            if (paused || !pageReady) {
                sleepNet(300);
                continue;
            }

            if (host == null) {
                pushNet("search");
                String found = discover();
                if (found == null) {
                    pushNet("notfound");
                    sleepNet(RETRY_MS);
                    continue;
                }
                host = found;
                prefs.edit().putString(KEY_IP, found).apply();
                lost = 0;
            }

            String json = httpGet("http://" + host + "/api", TO_POLL);
            if (json == null) {
                lost++;
                pushNet("offline");
                if (lost >= LOST_BEFORE_RESCAN) {
                    // адрес мог смениться после переподключения платы к точке доступа
                    host = null;
                    lost = 0;
                }
                sleepNet(RETRY_MS);
                continue;
            }

            lost = 0;
            pushNet("online");
            js("window.onApi && onApi(" + json + ")");
            checkAlarm(json);
            sleepNet(POLL_MS);
        }
    }

    /**
     * Вибросигналы по кромке события: один длинный на аварию, два коротких
     * на готовность редуктора. Второй нужен, чтобы не сидеть и не пялиться
     * в экран, дожидаясь, когда можно заводить.
     */
    private void checkAlarm(String json) {
        boolean latched = json.contains("\"latched\":true");
        if (latched && !lastLatched) buzz(new long[]{0, 400});
        lastLatched = latched;

        boolean warm = json.contains("\"warm\":true");
        if (warm && !lastWarm) buzz(new long[]{0, 120, 140, 120});
        lastWarm = warm;
    }

    private void buzz(long[] pattern) {
        if (vibrator == null || !vibrator.hasVibrator()) return;
        try {
            vibrator.vibrate(pattern, -1);
        } catch (Throwable ignored) {
        }
    }

    /**
     * Поиск платы: сохранённый адрес → 192.168.4.1 → перебор подсети.
     * После «повторить поиск» сохранённый адрес пропускается, иначе поиск
     * будет каждый раз упираться в тот же мёртвый IP.
     */
    private String discover() {
        if (!forceRescan) {
            String saved = prefs.getString(KEY_IP, null);
            if (saved != null && probe(saved, TO_PROBE)) return saved;
        }
        forceRescan = false;

        if (probe(AP_IP, TO_PROBE)) return AP_IP;

        String prefix = localPrefix();
        if (prefix == null) return null;
        return scanSubnet(prefix);
    }

    /** Скан x.x.x.1..254 в 20 потоков — вся подсеть должна уложиться в 5 с, ТЗ 3 */
    private String scanSubnet(String prefix) {
        final AtomicReference<String> hit = new AtomicReference<String>(null);
        ExecutorService pool = Executors.newFixedThreadPool(SCAN_THREADS);
        for (int i = 1; i <= 254; i++) {
            final String ip = prefix + i;
            pool.execute(new Runnable() {
                @Override
                public void run() {
                    if (hit.get() != null) return;
                    if (probe(ip, TO_SCAN)) hit.compareAndSet(null, ip);
                }
            });
        }
        pool.shutdown();
        try {
            pool.awaitTermination(6, TimeUnit.SECONDS);
        } catch (InterruptedException ignored) {
        }
        pool.shutdownNow();
        return hit.get();
    }

    /** Префикс «x.y.z.» текущей подсети: сперва Wi-Fi, иначе первый не-loopback IPv4 */
    private String localPrefix() {
        try {
            WifiManager wm = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            if (wm != null) {
                int ip = wm.getConnectionInfo().getIpAddress();
                if (ip != 0) {
                    return (ip & 0xFF) + "." + ((ip >> 8) & 0xFF) + "." + ((ip >> 16) & 0xFF) + ".";
                }
            }
        } catch (Throwable ignored) {
        }
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (ni.isLoopback() || !ni.isUp()) continue;
                Enumeration<InetAddress> addrs = ni.getInetAddresses();
                while (addrs.hasMoreElements()) {
                    InetAddress a = addrs.nextElement();
                    if (a.isLoopbackAddress()) continue;
                    String s = a.getHostAddress();
                    if (s == null || s.indexOf(':') >= 0) continue;
                    int dot = s.lastIndexOf('.');
                    if (dot > 0) return s.substring(0, dot + 1);
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private boolean probe(String ip, int timeout) {
        return httpGet("http://" + ip + "/api", timeout) != null;
    }

    private String httpGet(String url, int timeoutMs) {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(url).openConnection();
            c.setConnectTimeout(timeoutMs);
            c.setReadTimeout(timeoutMs);
            c.setRequestMethod("GET");
            c.setUseCaches(false);
            if (c.getResponseCode() != 200) return null;
            InputStream in = c.getInputStream();
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int r;
            while ((r = in.read(buf)) > 0) out.write(buf, 0, r);
            in.close();
            String body = out.toString("UTF-8");
            // страховка от чужого веб-сервера в той же подсети
            if (url.endsWith("/api") && body.indexOf("\"latched\"") < 0) return null;
            return body;
        } catch (Throwable e) {
            return null;
        } finally {
            if (c != null) c.disconnect();
        }
    }
}
