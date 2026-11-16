package com.krayestation.smarttv;

import android.app.Activity;
import android.app.Dialog;
import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.util.Base64;
import android.view.KeyEvent;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Full-screen WebView launcher. The UI lives in assets/index.html; this class
 * forwards the remote's D-pad to it and exposes a small "TV" bridge for
 * launching apps, listing apps, network fetches and the Spotify player.
 */
public class MainActivity extends Activity {

    /** id -> candidate packages, in order of preference. */
    static final String[][] BUILTIN = {
            {"netflix", "com.netflix.ninja"},
            {"prime", "com.amazon.avod", "com.amazon.avod.thirdpartyclient"},
            {"stremio", "com.stremio.one"},
            // SmartTube first (ad-free), then the official YouTube app as a fallback
            {"youtube", "com.teamsmart.videomanager.tv", "com.liskovsoft.smarttubetv.beta",
                    "com.liskovsoft.smarttubetv", "com.amazon.firetv.youtube", "com.google.android.youtube.tv"},
            {"hbomax", "com.wbd.stream", "com.hbo.hbonow"},
            {"spotify", "com.spotify.tv.android"},
            // Fire TV has no AirPlay or Google Cast built in; these receiver apps add both
            {"cast","com.ionitech.airscreen", "com.softmedia.receiver.lite", "com.softmedia.receiver"},
    };

    static final String AMAZON_HOME = "com.amazon.tv.launcher";
    static final String SP_REDIRECT = "http://127.0.0.1:8898/callback";
    static final String SP_SCOPES = "user-read-playback-state user-modify-playback-state user-read-currently-playing";

    WebView web;
    SharedPreferences prefs;
    boolean pageReady = false;
    volatile boolean visible = false;
    volatile Thread poller = null;
    final Map<String, String> artCache = Collections.synchronizedMap(new HashMap<String, String>());

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN, WindowManager.LayoutParams.FLAG_FULLSCREEN);
        prefs = getSharedPreferences("tv", Context.MODE_PRIVATE);

        WebView.setWebContentsDebuggingEnabled(true);
        web = new WebView(this);
        web.setBackgroundColor(Color.BLACK);
        web.setFocusable(false);
        web.setFocusableInTouchMode(false);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setAllowFileAccess(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setTextZoom(100);                 // ignore system font-size settings, the layout is fixed
        s.setUseWideViewPort(false);
        s.setLoadWithOverviewMode(false);
        s.setSupportZoom(false);
        web.setInitialScale(0);
        web.setLayerType(View.LAYER_TYPE_HARDWARE, null);
        web.addJavascriptInterface(new Bridge(), "TV");
        web.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String url) {
                pageReady = true;
                pushSize();
            }
        });
        // Tell the page the real screen size whenever it changes, so it can scale itself to fit
        web.addOnLayoutChangeListener(new View.OnLayoutChangeListener() {
            @Override
            public void onLayoutChange(View v, int l, int t, int r, int bt, int ol, int ot, int or, int ob) {
                pushSize();
            }
        });
        setContentView(web);
        hideSystemUi();
        web.loadUrl("file:///android_asset/index.html");
    }

    void pushSize() {
        int w = web.getWidth(), h = web.getHeight();
        if (w <= 0 || h <= 0) return;
        float d = getResources().getDisplayMetrics().density;
        js("window.__vw=" + (w / d) + ";window.__vh=" + (h / d) + ";window.refit&&window.refit()");
    }

    void hideSystemUi() {
        web.setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
    }

    @Override
    protected void onResume() {
        super.onResume();
        HomeService.bypass = false; // back on our screen: Home returns here again
        visible = true;
        hideSystemUi();
        if (pageReady) { pushSize(); js("window.onResume&&window.onResume()"); }
        startSpotifyPoller();
    }

    @Override
    protected void onPause() {
        super.onPause();
        visible = false;
    }

    // ---------------------------------------------------------------- remote

    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        String k;
        switch (e.getKeyCode()) {
            case KeyEvent.KEYCODE_DPAD_LEFT: k = "left"; break;
            case KeyEvent.KEYCODE_DPAD_RIGHT: k = "right"; break;
            case KeyEvent.KEYCODE_DPAD_UP: k = "up"; break;
            case KeyEvent.KEYCODE_DPAD_DOWN: k = "down"; break;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
            case KeyEvent.KEYCODE_BUTTON_A: k = "ok"; break;
            case KeyEvent.KEYCODE_MENU: k = "menu"; break;
            case KeyEvent.KEYCODE_BACK: k = "back"; break;
            default: return super.dispatchKeyEvent(e);
        }
        if (e.getAction() == KeyEvent.ACTION_DOWN) js("window.nav&&window.nav('" + k + "')");
        return true; // never let Back close the launcher
    }

    void js(final String code) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                web.evaluateJavascript(code, null);
            }
        });
    }

    void callback(String fn, JSONObject data) {
        js("window." + fn + "&&window." + fn + "(" + JSONObject.quote(data.toString()) + ")");
    }

    // ---------------------------------------------------------------- apps

    Intent launchIntentFor(String pkg) {
        PackageManager pm = getPackageManager();
        Intent i = null;
        try { i = pm.getLeanbackLaunchIntentForPackage(pkg); } catch (Exception ignored) { }
        if (i == null) {
            try { i = pm.getLaunchIntentForPackage(pkg); } catch (Exception ignored) { }
        }
        return i;
    }

    String[] builtin(String id) {
        for (String[] r : BUILTIN) if (r[0].equals(id)) return r;
        return null;
    }

    /** The package an id launches: "pkg:com.x" or a built-in id. */
    String pkgFor(String id) {
        if (id.startsWith("pkg:")) {
            String p = id.substring(4);
            return launchIntentFor(p) != null ? p : null;
        }
        String[] r = builtin(id);
        if (r == null) return null;
        for (int i = 1; i < r.length; i++) if (launchIntentFor(r[i]) != null) return r[i];
        return null;
    }

    String labelFor(String pkg) {
        try {
            PackageManager pm = getPackageManager();
            ApplicationInfo ai = pm.getApplicationInfo(pkg, 0);
            return pm.getApplicationLabel(ai).toString();
        } catch (Exception e) {
            return pkg;
        }
    }

    /** App banner (or icon) as a PNG data URI. kind[0] is set to true for banners. */
    String artFor(String pkg, int minWidth, boolean[] isBanner) {
        String key = pkg + "@" + minWidth;
        String cached = artCache.get(key);
        if (cached != null) {
            isBanner[0] = cached.charAt(0) == 'B';
            return cached.substring(1);
        }
        try {
            PackageManager pm = getPackageManager();
            Drawable d = pm.getApplicationBanner(pkg);
            isBanner[0] = d != null;
            if (d == null) d = pm.getApplicationIcon(pkg);
            int w = d.getIntrinsicWidth(), h = d.getIntrinsicHeight();
            if (w <= 0 || h <= 0) { w = isBanner[0] ? 320 : 192; h = isBanner[0] ? 180 : 192; }
            float min = isBanner[0] ? minWidth : Math.min(256, minWidth);
            if (w < min) { float f = min / w; w = Math.round(w * f); h = Math.round(h * f); }
            Bitmap bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            Canvas c = new Canvas(bmp);
            d.setBounds(0, 0, w, h);
            d.draw(c);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            bmp.compress(Bitmap.CompressFormat.PNG, 100, out);
            bmp.recycle();
            String uri = "data:image/png;base64," + Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP);
            artCache.put(key, (isBanner[0] ? "B" : "I") + uri);
            return uri;
        } catch (Exception e) {
            return null;
        }
    }

    void start(Intent i) {
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        startActivity(i);
    }

    void openStore(String pkg) {
        try {
            start(new Intent(Intent.ACTION_VIEW, Uri.parse("amzn://apps/android?p=" + pkg)));
        } catch (ActivityNotFoundException e) {
            start(new Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=" + pkg)));
        }
    }

    String doLaunch(String id) {
        try {
            if ("settings".equals(id)) {
                start(new Intent(Settings.ACTION_SETTINGS));
                return "ok";
            }
            if ("firehome".equals(id)) {
                HomeService.bypass = true; // let Amazon's home stay up this time
                Intent i = new Intent(Intent.ACTION_MAIN);
                i.addCategory(Intent.CATEGORY_HOME);
                i.setPackage(AMAZON_HOME);
                try {
                    start(i);
                } catch (ActivityNotFoundException e) {
                    Intent j = launchIntentFor(AMAZON_HOME);
                    if (j == null) return "missing";
                    start(j);
                }
                return "ok";
            }
            String pkg = pkgFor(id);
            if (pkg != null) {
                start(launchIntentFor(pkg));
                return "ok";
            }
            if ("youtube".equals(id)) return "smarttube";
            String[] r = builtin(id);
            if (r == null) return "missing";
            openStore(r[1]);
            return "store";
        } catch (Exception e) {
            return "missing";
        }
    }

    String allApps() {
        PackageManager pm = getPackageManager();
        Map<String, String> found = new LinkedHashMap<String, String>();
        String[] cats = {Intent.CATEGORY_LEANBACK_LAUNCHER, Intent.CATEGORY_LAUNCHER};
        for (String cat : cats) {
            Intent q = new Intent(Intent.ACTION_MAIN);
            q.addCategory(cat);
            List<ResolveInfo> list = pm.queryIntentActivities(q, 0);
            for (ResolveInfo ri : list) {
                String p = ri.activityInfo.packageName;
                if (p.equals(getPackageName()) || found.containsKey(p)) continue;
                found.put(p, ri.loadLabel(pm).toString());
            }
        }
        List<Map.Entry<String, String>> entries = new ArrayList<Map.Entry<String, String>>(found.entrySet());
        Collections.sort(entries, new Comparator<Map.Entry<String, String>>() {
            @Override
            public int compare(Map.Entry<String, String> a, Map.Entry<String, String> b) {
                return a.getValue().compareToIgnoreCase(b.getValue());
            }
        });
        JSONArray arr = new JSONArray();
        for (Map.Entry<String, String> en : entries) {
            try {
                JSONObject o = new JSONObject();
                o.put("pkg", en.getKey());
                o.put("label", en.getValue());
                boolean[] banner = new boolean[1];
                String img = artFor(en.getKey(), 320, banner);
                if (img != null) {
                    o.put("img", img);
                    o.put("kind", banner[0] ? "banner" : "icon");
                }
                arr.put(o);
            } catch (Exception ignored) { }
        }
        return arr.toString();
    }

    // ---------------------------------------------------------------- network

    static class Resp {
        int code;
        String body;
    }

    static Resp http(String method, String url, String body, String contentType, String bearer) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(8000);
        c.setReadTimeout(12000);
        c.setRequestMethod(method);
        c.setRequestProperty("Accept", "application/json");
        c.setRequestProperty("User-Agent", "SmartTV-Launcher/1.0 (https://github.com/krayestation/smart-tv)");
        if (bearer != null) c.setRequestProperty("Authorization", "Bearer " + bearer);
        if (body != null || "PUT".equals(method) || "POST".equals(method)) {
            byte[] bytes = (body == null ? "" : body).getBytes("UTF-8");
            c.setDoOutput(true);
            c.setRequestProperty("Content-Type", contentType != null ? contentType : "application/json");
            c.setFixedLengthStreamingMode(bytes.length);
            OutputStream os = c.getOutputStream();
            os.write(bytes);
            os.close();
        }
        Resp r = new Resp();
        r.code = c.getResponseCode();
        InputStream is = r.code >= 400 ? c.getErrorStream() : c.getInputStream();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        if (is != null) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = is.read(buf)) > 0) out.write(buf, 0, n);
            is.close();
        }
        c.disconnect();
        r.body = out.toString("UTF-8");
        return r;
    }

    void fetchAsync(final String url, final String cbId) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                int code = 0;
                String body = "";
                try {
                    Resp r = http("GET", url, null, null, null);
                    code = r.code;
                    body = r.body;
                } catch (Exception ignored) { }
                js("window.onFetch&&window.onFetch(" + JSONObject.quote(cbId) + "," + code + "," + JSONObject.quote(body) + ")");
            }
        }).start();
    }

    // ---------------------------------------------------------------- Spotify

    static String enc(String s) {
        try { return URLEncoder.encode(s, "UTF-8"); } catch (Exception e) { return s; }
    }

    static String b64url(byte[] b) {
        return Base64.encodeToString(b, Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING);
    }

    void spotifyLogin(final String clientId) {
        prefs.edit().putString("spClient", clientId).apply();
        try {
            byte[] rnd = new byte[48];
            new SecureRandom().nextBytes(rnd);
            final String verifier = b64url(rnd);
            String challenge = b64url(MessageDigest.getInstance("SHA-256").digest(verifier.getBytes("US-ASCII")));
            final String url = "https://accounts.spotify.com/authorize?client_id=" + enc(clientId)
                    + "&response_type=code&redirect_uri=" + enc(SP_REDIRECT)
                    + "&code_challenge_method=S256&code_challenge=" + challenge
                    + "&scope=" + enc(SP_SCOPES);
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    final Dialog d = new Dialog(MainActivity.this, android.R.style.Theme_Black_NoTitleBar_Fullscreen);
                    final WebView w = new WebView(MainActivity.this);
                    final boolean[] done = new boolean[1];
                    w.getSettings().setJavaScriptEnabled(true);
                    w.getSettings().setDomStorageEnabled(true);
                    w.setWebViewClient(new WebViewClient() {
                        boolean handle(String u) {
                            if (u == null || !u.startsWith(SP_REDIRECT)) return false;
                            if (!done[0]) {
                                done[0] = true;
                                String code = Uri.parse(u).getQueryParameter("code");
                                d.dismiss();
                                if (code != null) spotifyExchange(code, verifier, clientId);
                            }
                            return true;
                        }

                        @Override
                        public boolean shouldOverrideUrlLoading(WebView v, String u) {
                            return handle(u);
                        }

                        @Override
                        public void onPageStarted(WebView v, String u, Bitmap favicon) {
                            if (handle(u)) v.stopLoading();
                        }
                    });
                    d.setContentView(w);
                    d.show();
                    w.loadUrl(url);
                    w.requestFocus();
                }
            });
        } catch (Exception ignored) { }
    }

    void spotifyExchange(final String code, final String verifier, final String clientId) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    String body = "grant_type=authorization_code&code=" + enc(code) + "&redirect_uri=" + enc(SP_REDIRECT)
                            + "&client_id=" + enc(clientId) + "&code_verifier=" + enc(verifier);
                    Resp r = http("POST", "https://accounts.spotify.com/api/token", body, "application/x-www-form-urlencoded", null);
                    if (r.code == 200) saveTokens(new JSONObject(r.body));
                } catch (Exception ignored) { }
                startSpotifyPoller();
                spotifyPoll();
            }
        }).start();
    }

    void saveTokens(JSONObject j) {
        SharedPreferences.Editor e = prefs.edit();
        e.putString("spAccess", j.optString("access_token"));
        e.putLong("spExpires", System.currentTimeMillis() + j.optLong("expires_in", 3600) * 1000L);
        String refresh = j.optString("refresh_token", "");
        if (refresh.length() > 0) e.putString("spRefresh", refresh);
        e.apply();
    }

    synchronized String spotifyToken() {
        String access = prefs.getString("spAccess", null);
        if (access != null && System.currentTimeMillis() < prefs.getLong("spExpires", 0) - 60000) return access;
        String refresh = prefs.getString("spRefresh", null), client = prefs.getString("spClient", null);
        if (refresh == null || client == null) return null;
        try {
            Resp r = http("POST", "https://accounts.spotify.com/api/token",
                    "grant_type=refresh_token&refresh_token=" + enc(refresh) + "&client_id=" + enc(client),
                    "application/x-www-form-urlencoded", null);
            if (r.code == 200) {
                saveTokens(new JSONObject(r.body));
                return prefs.getString("spAccess", null);
            }
            if (r.code == 400) prefs.edit().remove("spRefresh").remove("spAccess").apply(); // revoked
        } catch (Exception ignored) { }
        return null;
    }

    void spotifyPoll() {
        JSONObject out = new JSONObject();
        try {
            boolean linked = prefs.getString("spRefresh", null) != null;
            out.put("linked", linked);
            if (!linked) { callback("onSpotify", out); return; }
            String token = spotifyToken();
            if (token == null) { callback("onSpotify", out); return; }
            Resp r = http("GET", "https://api.spotify.com/v1/me/player?additional_types=episode", null, null, token);
            if (r.code == 401) prefs.edit().remove("spAccess").apply();
            if (r.code == 200 && r.body.length() > 0) {
                JSONObject p = new JSONObject(r.body);
                JSONObject item = p.optJSONObject("item");
                if (item != null) {
                    JSONObject o = new JSONObject();
                    o.put("title", item.optString("name"));
                    StringBuilder artists = new StringBuilder();
                    JSONArray ar = item.optJSONArray("artists");
                    if (ar != null) {
                        for (int i = 0; i < ar.length(); i++) {
                            if (i > 0) artists.append(", ");
                            artists.append(ar.optJSONObject(i).optString("name"));
                        }
                    } else if (item.optJSONObject("show") != null) {
                        artists.append(item.optJSONObject("show").optString("name"));
                    }
                    o.put("artist", artists.toString());
                    JSONArray imgs = null;
                    if (item.optJSONObject("album") != null) imgs = item.optJSONObject("album").optJSONArray("images");
                    if (imgs == null) imgs = item.optJSONArray("images");
                    if (imgs != null && imgs.length() > 0) o.put("art", imgs.optJSONObject(0).optString("url"));
                    JSONObject dev = p.optJSONObject("device");
                    if (dev != null) o.put("device", dev.optString("name"));
                    o.put("isPlaying", p.optBoolean("is_playing"));
                    o.put("progress", p.optLong("progress_ms"));
                    o.put("duration", item.optLong("duration_ms"));
                    out.put("item", o);
                }
            }
        } catch (Exception ignored) { }
        callback("onSpotify", out);
    }

    void startSpotifyPoller() {
        if (prefs.getString("spRefresh", null) == null) return;
        Thread t = poller;
        if (t != null && t.isAlive()) return;
        poller = new Thread(new Runnable() {
            @Override
            public void run() {
                while (visible && prefs.getString("spRefresh", null) != null) {
                    spotifyPoll();
                    try { Thread.sleep(5000); } catch (InterruptedException e) { return; }
                }
            }
        });
        poller.start();
    }

    void spotifyControl(final String action) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    String token = spotifyToken();
                    if (token == null) return;
                    String base = "https://api.spotify.com/v1/me/player/";
                    if ("play".equals(action)) http("PUT", base + "play", null, null, token);
                    else if ("pause".equals(action)) http("PUT", base + "pause", null, null, token);
                    else if ("next".equals(action)) http("POST", base + "next", null, null, token);
                    else if ("prev".equals(action)) http("POST", base + "previous", null, null, token);
                    Thread.sleep(400);
                } catch (Exception ignored) { }
                spotifyPoll();
            }
        }).start();
    }

    boolean homeServiceOn() {
        try {
            String list = Settings.Secure.getString(getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            if (list == null) return false;
            ComponentName me = new ComponentName(this, HomeService.class);
            return list.contains(me.flattenToString()) || list.contains(me.flattenToShortString());
        } catch (Exception e) {
            return false;
        }
    }

    // ---------------------------------------------------------------- JS bridge

    class Bridge {
        /** Info for the pinned ids: installed, label, art. */
        @JavascriptInterface
        public String resolve(String idsJson) {
            JSONArray out = new JSONArray();
            try {
                JSONArray ids = new JSONArray(idsJson);
                for (int i = 0; i < ids.length(); i++) {
                    String id = ids.optString(i);
                    JSONObject o = new JSONObject();
                    o.put("id", id);
                    String pkg = pkgFor(id);
                    o.put("installed", pkg != null);
                    if (pkg != null) {
                        o.put("pkg", pkg);
                        o.put("label", labelFor(pkg));
                        if (!"cast".equals(id)) {
                            boolean[] banner = new boolean[1];
                            String img = artFor(pkg, 320, banner);
                            if (img != null) {
                                o.put("img", img);
                                o.put("kind", banner[0] ? "banner" : "icon");
                            }
                        }
                    }
                    out.put(o);
                }
            } catch (Exception ignored) { }
            return out.toString();
        }

        /** Same as resolve(), but off the UI path: answers via window.onApps(json). */
        @JavascriptInterface
        public void resolveAsync(final String idsJson) {
            new Thread(new Runnable() {
                @Override
                public void run() {
                    js("window.onApps&&window.onApps(" + JSONObject.quote(resolve(idsJson)) + ")");
                }
            }).start();
        }

        @JavascriptInterface
        public String allApps() { return MainActivity.this.allApps(); }

        /** Answers via window.onAllApps(json). */
        @JavascriptInterface
        public void allAppsAsync() {
            new Thread(new Runnable() {
                @Override
                public void run() {
                    js("window.onAllApps&&window.onAllApps(" + JSONObject.quote(MainActivity.this.allApps()) + ")");
                }
            }).start();
        }

        @JavascriptInterface
        public String launch(String id) { return doLaunch(id); }

        @JavascriptInterface
        public void fetch(String url, String cbId) { fetchAsync(url, cbId); }

        @JavascriptInterface
        public void spotifyLogin(String clientId) { MainActivity.this.spotifyLogin(clientId); }

        @JavascriptInterface
        public void spotifyLogout() {
            prefs.edit().remove("spRefresh").remove("spAccess").remove("spExpires").apply();
        }

        @JavascriptInterface
        public void spotifyControl(String action) { MainActivity.this.spotifyControl(action); }

        @JavascriptInterface
        public boolean homeServiceOn() { return MainActivity.this.homeServiceOn(); }

        @JavascriptInterface
        public void openAccessibility() {
            try { start(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)); }
            catch (Exception e) { start(new Intent(Settings.ACTION_SETTINGS)); }
        }
    }
}
