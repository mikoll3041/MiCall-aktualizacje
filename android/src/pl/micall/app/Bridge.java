package pl.micall.app;

import android.content.Intent;
import android.net.Uri;
import android.webkit.JavascriptInterface;

import org.json.JSONObject;

/**
 * Połączenie strony MiCall z telefonem (window.MiCallAndroid).
 * Polecenia działają tylko z sekretem, który dostaje główna strona MiCall — ramki (np. YouTube) go nie znają.
 */
final class Bridge {
    private final Web a;
    private final String cid;
    volatile String nonce = U.randHex(16);

    Bridge(Web a, String cid) {
        this.a = a;
        this.cid = cid == null ? "" : cid;
    }

    void newNonce() { nonce = U.randHex(16); }

    private boolean ok(String n) { return n != null && n.equals(nonce); }

    @JavascriptInterface public String device() { return U.device(a); }

    @JavascriptInterface public String version() { return U.verName(a); }

    @JavascriptInterface public long code() { return U.verCode(a); }

    @JavascriptInterface public String model() { return android.os.Build.MODEL == null ? "Android" : android.os.Build.MODEL; }

    @JavascriptInterface public String bubbleCid() { return cid; }

    @JavascriptInterface public String status() {
        try { return Setup.status(a).toString(); } catch (Exception e) { return "{}"; }
    }

    @JavascriptInterface public void setToken(String n, String tok) {
        if (!ok(n)) return;
        String t = tok == null ? "" : tok.trim();
        if (!t.equals(U.token(a))) {
            U.p(a).edit().putString("token", t).putLong("since", 0).apply();
            MiService.kick();
        }
        if (t.isEmpty()) {
            MiService.stop(a);
            Notif.clearAll(a);
        } else {
            MiService.start(a);
        }
    }

    @JavascriptInterface public void fix(String n, String what) {
        if (ok(n) && what != null) a.runOnUiThread(() -> Setup.open(a, what));
    }

    @JavascriptInterface public boolean bubble(String n, String json) {
        if (!ok(n)) return false;
        try { return Notif.openBubble(a, new JSONObject(json)); } catch (Exception e) { return false; }
    }

    @JavascriptInterface public void inCall(String n, boolean on) { if (ok(n)) MiService.setCall(a, on); }

    @JavascriptInterface public void clear(String n, String c) { if (ok(n) && c != null) Notif.clear(a, c); }

    @JavascriptInterface public void checkUpdate(String n) {
        if (ok(n)) new Thread(() -> Updater.check(a, true), "upd").start();
    }

    /** Z dymka: otwórz tę rozmowę w pełnej aplikacji. */
    @JavascriptInterface public void openApp(String n, String c) {
        if (!ok(n)) return;
        a.runOnUiThread(() -> {
            Intent i = new Intent(a, MainActivity.class).setAction(Intent.ACTION_VIEW).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            if (c != null && !c.isEmpty()) i.putExtra("open", c);
            a.startActivity(i);
        });
    }

    @JavascriptInterface public void openUrl(String n, String url) {
        if (!ok(n) || url == null || !(url.startsWith("https://") || url.startsWith("http://"))) return;
        a.runOnUiThread(() -> {
            try { a.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url))); } catch (Exception ignored) { }
        });
    }
}
