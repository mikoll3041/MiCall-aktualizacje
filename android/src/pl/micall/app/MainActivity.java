package pl.micall.app;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;

import org.json.JSONObject;

/** Główne okno MiCall. */
public class MainActivity extends Web {
    @Override protected void onCreate(Bundle b) {
        // test w emulatorze (GitHub Actions): centrala na 127.0.0.1 — inne adresy są ignorowane
        String base = getIntent().getStringExtra("base");
        if (base != null && (base.startsWith("http://127.0.0.1:") || base.startsWith("http://localhost:"))) {
            U.p(this).edit().putString("base", base).commit();
        }
        super.onCreate(b);
        Notif.channels(this);
        String cid = getIntent().getStringExtra("open");
        if (cid != null && getIntent().getBooleanExtra("answer", false)) Notif.callEnd(this, cid);
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
                && !U.p(this).getBoolean("askedNotif", false)) {
            U.p(this).edit().putBoolean("askedNotif", true).apply();
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, RC_NOTIF);
        }
        MiService.start(this);
    }

    @Override String url(Intent i) {
        String u = U.base(this) + "/";
        String cid = i.getStringExtra("open");
        if (cid != null) u += "?open=" + Uri.encode(cid) + (i.getBooleanExtra("answer", false) ? "&answer=1" : "");
        return u;
    }

    @Override protected void onNewIntent(Intent i) {
        super.onNewIntent(i);
        setIntent(i);
        final String cid = i.getStringExtra("open");
        if (cid == null || wv == null) return;
        final boolean ans = i.getBooleanExtra("answer", false);
        if (ans) Notif.callEnd(this, cid);
        final String u = url(i);
        String js = "(typeof Push!=='undefined'&&typeof S!=='undefined'&&S.me)?(Push.open(" + JSONObject.quote(cid) + "," + ans + ",false),'1'):'0'";
        wv.evaluateJavascript(js, r -> { if (!"\"1\"".equals(r) && wv != null) wv.loadUrl(u); });
    }
}
