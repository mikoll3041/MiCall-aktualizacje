package pl.micall.app;

import android.Manifest;
import android.app.Activity;
import android.app.DownloadManager;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.webkit.ConsoleMessage;
import android.webkit.CookieManager;
import android.webkit.PermissionRequest;
import android.webkit.URLUtil;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.Toast;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/** Okno z MiCall (ta sama aplikacja co w przeglądarce, z dodatkami telefonu). */
public class Web extends Activity {
    static volatile int visible; // ile okien MiCall jest teraz na ekranie
    static final int RC_FILE = 11, RC_PERM = 12, RC_NOTIF = 13;

    WebView wv;
    Bridge br;
    FrameLayout root;
    private ValueCallback<Uri[]> fileCb;
    private PermissionRequest perm;
    private View full;
    private WebChromeClient.CustomViewCallback fullCb;

    String cid() { return ""; }

    String url(Intent i) { return U.base(this) + "/"; }

    void leave() { moveTaskToBack(true); }

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().setStatusBarColor(0xFF0B0616);
        getWindow().setNavigationBarColor(0xFF0B0616);
        root = new FrameLayout(this);
        wv = new WebView(this);
        wv.setBackgroundColor(0xFF0B0616);
        root.addView(wv, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        setContentView(root);

        WebSettings s = wv.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(true);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        s.setTextZoom(100);
        s.setSupportMultipleWindows(false);
        s.setUserAgentString(s.getUserAgentString() + " " + U.ua(this));
        CookieManager.getInstance().setAcceptCookie(true);
        CookieManager.getInstance().setAcceptThirdPartyCookies(wv, true);

        br = new Bridge(this, cid());
        wv.addJavascriptInterface(br, "MiCallAndroid");
        wv.setWebViewClient(new WebViewClient() {
            @Override public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) {
                return r.isForMainFrame() && outside(r.getUrl());
            }

            @Override public void onPageStarted(WebView v, String url, Bitmap fav) { br.newNonce(); }

            @Override public void onPageCommitVisible(WebView v, String url) { inject(); }

            @Override public void onPageFinished(WebView v, String url) { inject(); }

            @Override public void onReceivedError(WebView v, WebResourceRequest r, WebResourceError e) {
                if (r.isForMainFrame()) offline();
            }
        });
        wv.setWebChromeClient(new Chrome());
        wv.setDownloadListener((url, ua, cd, mime, len) -> download(url, ua, cd, mime));
        wv.loadUrl(url(getIntent()));
    }

    /** Sekret strony: dostaje go tylko główna strona MiCall (nie ramki z YouTube itp.). */
    void inject() {
        wv.evaluateJavascript("window.__mcn=" + JSONObject.quote(br.nonce) + ";window.MiCallNativeReady&&MiCallNativeReady()", null);
    }

    boolean same(Uri u) {
        Uri b = Uri.parse(U.base(this));
        return u != null && b.getScheme().equalsIgnoreCase(String.valueOf(u.getScheme()))
                && b.getHost().equalsIgnoreCase(String.valueOf(u.getHost())) && b.getPort() == u.getPort();
    }

    /** Linki spoza MiCall otwieramy w przeglądarce. */
    boolean outside(Uri u) {
        if (same(u)) return false;
        String sc = String.valueOf(u.getScheme());
        if (sc.equals("about") || sc.equals("data") || sc.equals("blob")) return false;
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, u).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (ActivityNotFoundException e) {
            Toast.makeText(this, "Nie mam czym otworzyć tego linku", Toast.LENGTH_SHORT).show();
        }
        return true;
    }

    void offline() {
        String base = U.base(this);
        String html = "<!doctype html><meta name=viewport content='width=device-width,initial-scale=1'>"
                + "<body style='margin:0;background:#0B0616;color:#e9e3ff;font-family:sans-serif;display:flex;align-items:center;justify-content:center;height:100vh;text-align:center'>"
                + "<div><div style='font-size:54px'>🛰️</div><h2>Brak połączenia z MiCall</h2><p style='color:#a99bd6'>Sprawdź internet — spróbuję ponownie za chwilę.</p>"
                + "<a href='" + base + "/' style='display:inline-block;margin-top:12px;padding:12px 22px;border-radius:12px;background:#8B5CF6;color:#fff;text-decoration:none'>Spróbuj teraz</a></div>"
                + "<script>setTimeout(function(){location.href='" + base + "/'},6000)</script></body>";
        wv.loadDataWithBaseURL("about:blank", html, "text/html", "utf-8", null);
    }

    void vis(boolean v) {
        if (wv != null) wv.evaluateJavascript("window.MiCallNativeVis&&MiCallNativeVis(" + v + ")", null);
    }

    void download(String url, String ua, String cd, String mime) {
        if (url == null || !url.startsWith("http")) {
            Toast.makeText(this, "Ten plik zapisz, przytrzymując go palcem", Toast.LENGTH_LONG).show();
            return;
        }
        try {
            DownloadManager.Request q = new DownloadManager.Request(Uri.parse(url));
            String name = URLUtil.guessFileName(url, cd, mime);
            if (mime != null) q.setMimeType(mime);
            q.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
            q.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, name);
            q.addRequestHeader("User-Agent", ua);
            String ck = CookieManager.getInstance().getCookie(url);
            if (ck != null) q.addRequestHeader("Cookie", ck);
            if (same(Uri.parse(url)) && !U.token(this).isEmpty()) q.addRequestHeader("Authorization", "Bearer " + U.token(this));
            getSystemService(DownloadManager.class).enqueue(q);
            Toast.makeText(this, "Pobieram: " + name, Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            outside(Uri.parse(url));
        }
    }

    class Chrome extends WebChromeClient {
        @Override public void onPermissionRequest(PermissionRequest r) { runOnUiThread(() -> askPerm(r)); }

        @Override public void onPermissionRequestCanceled(PermissionRequest r) { if (perm == r) perm = null; }

        @Override public boolean onShowFileChooser(WebView v, ValueCallback<Uri[]> cb, FileChooserParams p) {
            if (fileCb != null) fileCb.onReceiveValue(null);
            fileCb = cb;
            try {
                Intent i = p.createIntent();
                i.addCategory(Intent.CATEGORY_OPENABLE);
                if (p.getMode() == FileChooserParams.MODE_OPEN_MULTIPLE) i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
                startActivityForResult(Intent.createChooser(i, "Wybierz plik"), RC_FILE);
                return true;
            } catch (Exception e) {
                fileCb = null;
                return false;
            }
        }

        @SuppressWarnings("deprecation")
        @Override public void onShowCustomView(View view, CustomViewCallback cb) {
            if (full != null) { cb.onCustomViewHidden(); return; }
            full = view;
            fullCb = cb;
            root.addView(view, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            wv.setVisibility(View.GONE);
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
            getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
        }

        @Override public void onHideCustomView() { exitFull(); }

        @Override public boolean onConsoleMessage(ConsoleMessage m) {
            Log.d(U.TAG, "js: " + m.message() + " @" + m.sourceId() + ":" + m.lineNumber());
            return true;
        }

        @Override public Bitmap getDefaultVideoPoster() { return Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888); }
    }

    @SuppressWarnings("deprecation")
    void exitFull() {
        if (full == null) return;
        root.removeView(full);
        full = null;
        wv.setVisibility(View.VISIBLE);
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
        getWindow().getDecorView().setSystemUiVisibility(0);
        if (fullCb != null) fullCb.onCustomViewHidden();
        fullCb = null;
    }

    boolean granted(String p) { return checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED; }

    /** Mikrofon / kamera dla rozmów — tylko dla strony MiCall. */
    void askPerm(PermissionRequest r) {
        if (!same(r.getOrigin())) { r.deny(); return; }
        List<String> miss = new ArrayList<>();
        for (String res : r.getResources()) {
            if (res.equals(PermissionRequest.RESOURCE_AUDIO_CAPTURE) && !granted(Manifest.permission.RECORD_AUDIO)) miss.add(Manifest.permission.RECORD_AUDIO);
            if (res.equals(PermissionRequest.RESOURCE_VIDEO_CAPTURE) && !granted(Manifest.permission.CAMERA)) miss.add(Manifest.permission.CAMERA);
        }
        if (miss.isEmpty()) { grantAllowed(r); return; }
        perm = r;
        requestPermissions(miss.toArray(new String[0]), RC_PERM);
    }

    void grantAllowed(PermissionRequest r) {
        List<String> ok = new ArrayList<>();
        for (String res : r.getResources()) {
            if (res.equals(PermissionRequest.RESOURCE_AUDIO_CAPTURE) && granted(Manifest.permission.RECORD_AUDIO)) ok.add(res);
            if (res.equals(PermissionRequest.RESOURCE_VIDEO_CAPTURE) && granted(Manifest.permission.CAMERA)) ok.add(res);
        }
        if (ok.isEmpty()) r.deny();
        else r.grant(ok.toArray(new String[0]));
    }

    @Override public void onRequestPermissionsResult(int rc, String[] p, int[] res) {
        super.onRequestPermissionsResult(rc, p, res);
        if (rc == RC_PERM && perm != null) {
            PermissionRequest r = perm;
            perm = null;
            grantAllowed(r);
        }
        if (rc == RC_NOTIF) MiService.start(this);
    }

    @Override protected void onActivityResult(int rc, int res, Intent data) {
        super.onActivityResult(rc, res, data);
        if (rc != RC_FILE || fileCb == null) return;
        Uri[] out = null;
        if (res == RESULT_OK && data != null) {
            ClipData cd = data.getClipData();
            if (cd != null && cd.getItemCount() > 0) {
                out = new Uri[cd.getItemCount()];
                for (int i = 0; i < out.length; i++) out[i] = cd.getItemAt(i).getUri();
            } else if (data.getData() != null) {
                out = new Uri[]{data.getData()};
            }
        }
        fileCb.onReceiveValue(out);
        fileCb = null;
    }

    @Override protected void onResume() {
        super.onResume();
        visible++;
        vis(true);
    }

    @Override protected void onPause() {
        visible = Math.max(0, visible - 1);
        vis(false);
        super.onPause();
    }

    @SuppressWarnings("deprecation")
    @Override public void onBackPressed() {
        if (full != null) { exitFull(); return; }
        if (wv.canGoBack()) wv.goBack();
        else leave();
    }

    @Override protected void onDestroy() {
        if (wv != null) {
            root.removeView(wv);
            wv.destroy();
            wv = null;
        }
        super.onDestroy();
    }
}
