package pl.micall.app;

import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInstaller;
import android.content.pm.PackageManager;
import android.os.Build;
import android.util.Log;

import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.security.MessageDigest;

/**
 * Automatyczne aktualizacje: centrala ma nową wersję → pobierz, sprawdź sumę, zainstaluj.
 * Pierwszy raz Android pyta („Zainstalować aktualizację?”), potem MiCall aktualizuje się sam, bez pytania.
 */
final class Updater {
    private static volatile boolean busy;

    private Updater() {}

    /** force — sprawdź teraz (przycisk w aplikacji); bez tego najwyżej co 3 godziny i nie w trakcie używania. */
    static void check(Context ctx, boolean force) {
        Context c = ctx.getApplicationContext();
        if (busy || U.token(c).isEmpty()) return;
        long last = U.p(c).getLong("updAt", 0);
        if (!force && System.currentTimeMillis() - last < 3 * 3600 * 1000L) return;
        busy = true;
        try {
            U.p(c).edit().putLong("updAt", System.currentTimeMillis()).apply();
            JSONObject info = new JSONObject(U.http(c, "GET", "/api/info", null, 15000));
            JSONObject a = info.optJSONObject("android");
            if (a == null) return;
            long code = a.optLong("code");
            String sha = a.optString("sha256");
            if (code <= U.verCode(c) || sha.length() != 64) return;
            if (!force && (Web.visible > 0 || MiService.inCall)) {
                U.p(c).edit().putLong("updAt", 0).apply(); // spróbuj, gdy nie będziesz z niej korzystać
                return;
            }
            File f = new File(c.getCacheDir(), "aktualizacja.apk");
            HttpURLConnection h = U.open(c, "GET", "/api/update/android", 60000);
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            try {
                if (h.getResponseCode() != 200) return;
                InputStream in = h.getInputStream();
                OutputStream o = new FileOutputStream(f);
                byte[] b = new byte[65536];
                int n;
                long tot = 0;
                while ((n = in.read(b)) > 0) {
                    o.write(b, 0, n);
                    md.update(b, 0, n);
                    tot += n;
                    if (tot > 100L << 20) throw new Exception("za duży plik");
                }
                o.close();
                in.close();
            } finally {
                h.disconnect();
            }
            StringBuilder s = new StringBuilder();
            for (byte x : md.digest()) s.append(String.format("%02x", x & 0xff));
            if (!s.toString().equals(sha)) {
                Log.w(U.TAG, "aktualizacja: zła suma");
                f.delete();
                return;
            }
            install(c, f);
        } catch (Exception e) {
            Log.w(U.TAG, "aktualizacja: " + e);
        } finally {
            busy = false;
        }
    }

    static void install(Context c, File f) throws Exception {
        PackageInstaller pi = c.getPackageManager().getPackageInstaller();
        PackageInstaller.SessionParams sp = new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
        sp.setAppPackageName(c.getPackageName());
        sp.setInstallReason(PackageManager.INSTALL_REASON_USER);
        if (Build.VERSION.SDK_INT >= 31) sp.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED);
        int id = pi.createSession(sp);
        PackageInstaller.Session s = pi.openSession(id);
        try {
            OutputStream o = s.openWrite("micall.apk", 0, f.length());
            InputStream in = new FileInputStream(f);
            byte[] b = new byte[65536];
            int n;
            while ((n = in.read(b)) > 0) o.write(b, 0, n);
            in.close();
            s.fsync(o);
            o.close();
            Intent it = new Intent(c, Actions.class).setAction(Actions.INSTALL);
            PendingIntent p = PendingIntent.getBroadcast(c, id, it, PendingIntent.FLAG_UPDATE_CURRENT | Notif.mut());
            s.commit(p.getIntentSender());
        } finally {
            s.close();
        }
    }

    @SuppressWarnings("deprecation")
    static void onStatus(Context c, Intent i) {
        int st = i.getIntExtra(PackageInstaller.EXTRA_STATUS, -999);
        if (st == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            Intent confirm = i.getParcelableExtra(Intent.EXTRA_INTENT);
            if (confirm == null) return;
            confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            if (Web.visible > 0) {
                try {
                    c.startActivity(confirm);
                    return;
                } catch (Exception e) {
                    Log.w(U.TAG, "instalacja: " + e);
                }
            }
            Notif.updateReady(c, confirm);
        } else if (st != PackageInstaller.STATUS_SUCCESS) {
            Log.w(U.TAG, "instalacja: " + st + " " + i.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE));
        }
    }
}
