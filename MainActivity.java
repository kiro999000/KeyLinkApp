package com.keylink.app;

import android.app.Activity;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.Socket;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

public class MainActivity extends Activity {
    private static final int FIRST_PORT = 8765;
    private static final int LAST_PORT = 8784;

    private WebView web;
    private SharedPreferences prefs;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private volatile boolean busy = false;
    private int autoTries = 0;
    private boolean errored = false;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        prefs = getSharedPreferences("keylink", MODE_PRIVATE);

        web = new WebView(this);
        web.setBackgroundColor(0xFF070A14);
        setContentView(web);

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setCacheMode(WebSettings.LOAD_NO_CACHE);
        web.addJavascriptInterface(new Bridge(), "Android");
        web.setWebViewClient(new WebViewClient() {
            @Override
            public void onReceivedError(WebView v, WebResourceRequest r, WebResourceError e) {
                if (r.isForMainFrame()) {
                    errored = true;
                    if (autoTries < 2) { autoTries++; startSearch(); }
                    else showFail("Could not reach your laptop.");
                }
            }
            @Override
            public void onPageFinished(WebView v, String url) {
                if (!errored && url != null && url.startsWith("http")) autoTries = 0;
                errored = false;
            }
        });
        startSearch();
    }

    @Override
    protected void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) {
            getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | View.SYSTEM_UI_FLAG_FULLSCREEN
                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
        }
    }

    // ---------------------------------------------------------------- finding the laptop
    private void startSearch() {
        if (busy) return;
        busy = true;
        showPage(statusHtml("Looking for your laptop…"));
        new Thread(() -> {
            try {
                String host = prefs.getString("host", null);
                int port = prefs.getInt("port", FIRST_PORT);
                if (host != null && open(host, port, 900)) { go(host, port); return; }
                String[] hit = scan();
                if (hit != null) {
                    prefs.edit().putString("host", hit[0]).putInt("port", Integer.parseInt(hit[1])).apply();
                    go(hit[0], Integer.parseInt(hit[1]));
                } else {
                    showFail("KeyLink was not found on this Wi-Fi.");
                }
            } finally {
                busy = false;
            }
        }).start();
    }

    private void go(String host, int port) {
        ui.post(() -> web.loadUrl("http://" + host + ":" + port));
    }

    private boolean open(String host, int port, int timeoutMs) {
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress(host, port), timeoutMs);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private List<String> localPrefixes() {
        List<String> out = new ArrayList<>();
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp() || ni.isLoopback()) continue;
                for (InetAddress a : Collections.list(ni.getInetAddresses())) {
                    if (a instanceof Inet4Address && a.isSiteLocalAddress()) {
                        String ip = a.getHostAddress();
                        out.add(ip.substring(0, ip.lastIndexOf('.') + 1));
                    }
                }
            }
        } catch (Exception ignored) { }
        return out;
    }

    private String[] scan() {
        List<String> prefixes = localPrefixes();
        if (prefixes.isEmpty()) return null;
        ExecutorService pool = Executors.newFixedThreadPool(96);
        try {
            for (int port = FIRST_PORT; port <= LAST_PORT; port++) {
                for (String prefix : prefixes) {
                    List<Callable<String>> jobs = new ArrayList<>();
                    for (int i = 1; i <= 254; i++) {
                        final String host = prefix + i;
                        final int p = port;
                        jobs.add(() -> open(host, p, 450) ? host : null);
                    }
                    for (Future<String> f : pool.invokeAll(jobs)) {
                        String h = f.get();
                        if (h != null) return new String[]{h, String.valueOf(port)};
                    }
                }
            }
        } catch (Exception ignored) {
        } finally {
            pool.shutdownNow();
        }
        return null;
    }

    // ---------------------------------------------------------------- simple pages
    private void showPage(String html) {
        ui.post(() -> web.loadDataWithBaseURL(null, html, "text/html", "utf-8", null));
    }

    private String shell(String body) {
        return "<html><head><meta name='viewport' content='width=device-width,initial-scale=1'><style>"
            + "body{margin:0;min-height:100vh;display:flex;align-items:center;justify-content:center;"
            + "background:radial-gradient(120% 90% at 50% 0%,#1b2350,#070a14 70%);color:#eef2ff;"
            + "font-family:sans-serif;text-align:center}"
            + ".c{width:84%;max-width:360px;padding:28px 22px;border-radius:28px;background:rgba(255,255,255,.08);"
            + "border:1px solid rgba(255,255,255,.18)}"
            + "h1{margin:0 0 10px;font-size:26px}p{color:#9aa6c7;font-size:14px;line-height:1.5}"
            + "input{width:100%;height:50px;border-radius:14px;border:1px solid rgba(255,255,255,.2);"
            + "background:rgba(0,0,0,.3);color:#fff;font-size:18px;text-align:center;margin:10px 0}"
            + "button{width:100%;height:50px;border:0;border-radius:14px;margin-top:8px;font-size:16px;"
            + "font-weight:700;color:#0a0f24;background:linear-gradient(135deg,#7aa2ff,#c084fc)}"
            + "button.g{background:rgba(255,255,255,.12);color:#eef2ff}"
            + "</style></head><body><div class='c'>" + body + "</div></body></html>";
    }

    private String statusHtml(String msg) {
        return shell("<h1>KeyLink</h1><p>" + msg + "</p>");
    }

    private void showFail(String msg) {
        showPage(shell("<h1>KeyLink</h1><p>" + msg
            + "<br>Make sure the laptop is on, KeyLink is running, and both are on the same Wi-Fi.</p>"
            + "<button onclick='Android.rescan()'>Search again</button>"
            + "<p style='margin-top:18px'>Or type the laptop address:</p>"
            + "<input id='a' placeholder='192.168.1.5' inputmode='decimal'>"
            + "<button class='g' onclick=\"Android.manual(document.getElementById('a').value)\">Connect</button>"));
    }

    private class Bridge {
        @JavascriptInterface
        public void rescan() {
            prefs.edit().remove("host").apply();
            autoTries = 0;
            startSearch();
        }

        @JavascriptInterface
        public void manual(String value) {
            String v = value == null ? "" : value.trim().replace("http://", "");
            if (v.isEmpty()) return;
            String host = v;
            int port = FIRST_PORT;
            int c = v.indexOf(':');
            if (c > 0) {
                host = v.substring(0, c);
                try { port = Integer.parseInt(v.substring(c + 1)); } catch (Exception ignored) { }
            }
            prefs.edit().putString("host", host).putInt("port", port).apply();
            autoTries = 0;
            go(host, port);
        }
    }
}
