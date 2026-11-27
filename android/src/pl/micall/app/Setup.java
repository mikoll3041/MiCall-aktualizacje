package pl.micall.app;

import android.Manifest;
import android.app.Activity;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.PowerManager;
import android.provider.Settings;

import org.json.JSONException;
import org.json.JSONObject;

/** Ustawienia telefonu potrzebne, żeby MiCall działał jak Messenger (powiadomienia, dymki, praca w tle). */
final class Setup {
    private Setup() {}

    static JSONObject status(Context c) throws JSONException {
        JSONObject o = new JSONObject();
        NotificationManager nm = Notif.nm(c);
        Notif.channels(c);
        boolean notif = nm.areNotificationsEnabled();
        NotificationChannel ch = nm.getNotificationChannel(Notif.CH_MSG);
        if (ch != null && ch.getImportance() == NotificationManager.IMPORTANCE_NONE) notif = false;
        o.put("notif", notif);
        String bub = "unsupported";
        if (Build.VERSION.SDK_INT >= 31) {
            int p = nm.getBubblePreference();
            bub = p == NotificationManager.BUBBLE_PREFERENCE_ALL ? "all" : p == NotificationManager.BUBBLE_PREFERENCE_SELECTED ? "selected" : "none";
        } else if (Build.VERSION.SDK_INT == 30) {
            bub = Notif.areBubblesAllowed30(nm) ? "all" : "none";
        }
        o.put("bubbles", bub);
        if (ch != null && Build.VERSION.SDK_INT >= 30 && !ch.canBubble() && bub.equals("all")) o.put("bubbles", "channel");
        int global = -1;
        try { global = Settings.Secure.getInt(c.getContentResolver(), "notification_bubbles", -1); } catch (Exception ignored) { }
        if (global == -1) try { global = Settings.Global.getInt(c.getContentResolver(), "notification_bubbles", -1); } catch (Exception ignored) { }
        o.put("bubblesGlobal", global);
        PowerManager pm = c.getSystemService(PowerManager.class);
        o.put("battery", pm != null && pm.isIgnoringBatteryOptimizations(c.getPackageName()));
        o.put("install", c.getPackageManager().canRequestPackageInstalls());
        o.put("fsi", Build.VERSION.SDK_INT < 34 || nm.canUseFullScreenIntent());
        o.put("mic", c.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED);
        o.put("service", MiService.inst != null);
        o.put("sdk", Build.VERSION.SDK_INT);
        o.put("maker", String.valueOf(Build.MANUFACTURER));
        o.put("version", U.verName(c));
        o.put("code", U.verCode(c));
        return o;
    }

    static void open(Activity a, String what) {
        String pkg = a.getPackageName();
        Uri pu = Uri.parse("package:" + pkg);
        Intent i;
        switch (what) {
            case "notif":
                if (Build.VERSION.SDK_INT >= 33 && a.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
                        && U.p(a).getInt("notifAsk", 0) < 2) {
                    U.p(a).edit().putInt("notifAsk", U.p(a).getInt("notifAsk", 0) + 1).apply();
                    a.requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, Web.RC_NOTIF);
                    return;
                }
                i = new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, pkg);
                break;
            case "bubbles":
                i = Build.VERSION.SDK_INT >= 30
                        ? new Intent(Settings.ACTION_APP_NOTIFICATION_BUBBLE_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, pkg)
                        : new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, pkg);
                break;
            case "channel":
                i = new Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, pkg)
                        .putExtra(Settings.EXTRA_CHANNEL_ID, Notif.CH_MSG);
                break;
            case "battery":
                i = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, pu);
                break;
            case "install":
                i = new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, pu);
                break;
            case "fsi":
                i = Build.VERSION.SDK_INT >= 34 ? new Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, pu)
                        : new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, pu);
                break;
            case "mic":
                if (a.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED && U.p(a).getInt("micAsk", 0) < 2) {
                    U.p(a).edit().putInt("micAsk", U.p(a).getInt("micAsk", 0) + 1).apply();
                    a.requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, Web.RC_PERM + 100);
                    return;
                }
                i = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, pu);
                break;
            default:
                i = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, pu);
        }
        try {
            a.startActivity(i);
        } catch (Exception e) {
            try { a.startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, pu)); } catch (Exception ignored) { }
        }
    }
}
