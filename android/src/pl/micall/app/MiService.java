package pl.micall.app;

import android.Manifest;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.net.ConnectivityManager;
import android.net.Network;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/**
 * Działanie w tle: jedno stałe połączenie z centralą MiCall (bez Google/Firebase).
 * Centrala wysyła nim nowe wiadomości, połączenia i „przeczytane” — telefon pokazuje je od razu.
 */
public class MiService extends Service {
    static volatile MiService inst;
    static volatile boolean inCall;

    private final Object lock = new Object();
    private volatile boolean run;
    private volatile HttpURLConnection conn;
    private volatile long lastData;
    private Thread th;
    private ConnectivityManager.NetworkCallback netCb;
    private volatile Network lastNet;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Runnable updTick = new Runnable() {
        @Override public void run() {
            new Thread(() -> Updater.check(MiService.this, false), "upd").start();
            main.postDelayed(this, 6 * 3600 * 1000L);
        }
    };

    static void start(Context c) {
        if (U.token(c).isEmpty()) return;
        try {
            Intent i = new Intent(c, MiService.class);
            c.startForegroundService(i);
        } catch (Exception e) {
            Log.w(U.TAG, "start usługi: " + e);
        }
    }

    static void stop(Context c) {
        try { c.stopService(new Intent(c, MiService.class)); } catch (Exception ignored) { }
    }

    static void setCall(Context c, boolean on) {
        inCall = on;
        MiService s = inst;
        if (s != null) s.main.post(s::fg);
        else if (on) start(c);
    }

    /** Coś się zmieniło (sieć, logowanie) — połącz się od nowa. */
    static void kick() {
        MiService s = inst;
        if (s == null) return;
        HttpURLConnection h = s.conn;
        if (h != null) new Thread(h::disconnect).start();
        synchronized (s.lock) { s.lock.notifyAll(); }
    }

    @Override public void onCreate() {
        super.onCreate();
        inst = this;
        Notif.channels(this);
        fg();
        run = true;
        th = new Thread(this::loop, "micall-stream");
        th.start();
        try {
            ConnectivityManager cm = getSystemService(ConnectivityManager.class);
            netCb = new ConnectivityManager.NetworkCallback() {
                @Override public void onAvailable(Network n) {
                    Network prev = lastNet;
                    lastNet = n;
                    if (prev != null && !prev.equals(n)) kick();
                    else synchronized (lock) { lock.notifyAll(); }
                }
            };
            cm.registerDefaultNetworkCallback(netCb);
        } catch (Exception e) {
            Log.w(U.TAG, "sieć: " + e);
        }
        main.postDelayed(updTick, 90 * 1000L);
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        fg();
        if (U.token(this).isEmpty()) {
            stopSelf();
            return START_NOT_STICKY;
        }
        return START_STICKY;
    }

    void fg() {
        try {
            if (Build.VERSION.SDK_INT >= 34) {
                int t = ServiceInfo.FOREGROUND_SERVICE_TYPE_REMOTE_MESSAGING;
                boolean mic = inCall && checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED;
                if (mic) t |= ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE;
                try {
                    startForeground(Notif.ID_FG, Notif.service(this, inCall), t);
                } catch (Exception e) {
                    Log.w(U.TAG, "usługa z mikrofonem: " + e);
                    startForeground(Notif.ID_FG, Notif.service(this, inCall), ServiceInfo.FOREGROUND_SERVICE_TYPE_REMOTE_MESSAGING);
                }
            } else {
                startForeground(Notif.ID_FG, Notif.service(this, inCall));
            }
        } catch (Exception e) {
            Log.e(U.TAG, "nie mogę działać w tle", e);
        }
    }

    @Override public void onDestroy() {
        run = false;
        inst = null;
        main.removeCallbacks(updTick);
        kick();
        synchronized (lock) { lock.notifyAll(); }
        try {
            if (netCb != null) getSystemService(ConnectivityManager.class).unregisterNetworkCallback(netCb);
        } catch (Exception ignored) { }
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }

    private void loop() {
        long wait = 2000;
        while (run) {
            String tok = U.token(this);
            if (tok.isEmpty()) break;
            long t0 = System.currentTimeMillis();
            try {
                stream();
            } catch (U.HttpError e) {
                if (e.code == 401) {
                    Log.w(U.TAG, "sesja wygasła");
                    U.p(this).edit().remove("token").apply();
                    break;
                }
                Log.w(U.TAG, "strumień: " + e);
            } catch (Exception e) {
                if (run) Log.w(U.TAG, "strumień: " + e);
            }
            conn = null;
            if (!run) break;
            if (System.currentTimeMillis() - t0 > 60000) wait = 2000; // było połączone dłużej — od razu ponów
            synchronized (lock) {
                try { lock.wait(wait); } catch (InterruptedException ignored) { }
            }
            wait = Math.min(wait * 2, 60000);
        }
        main.post(this::stopSelf);
    }

    private void stream() throws IOException {
        long since = U.p(this).getLong("since", 0);
        String path = "/api/push/stream?device=" + U.device(this) + "&since=" + since
                + "&name=" + URLEncoder.encode(Build.MODEL == null ? "Android" : Build.MODEL, "UTF-8");
        HttpURLConnection h = U.open(this, "GET", path, 20000);
        h.setReadTimeout(130000); // centrala co 45 s wysyła „ping”
        h.setRequestProperty("Accept", "text/event-stream");
        conn = h;
        try {
            int code = h.getResponseCode();
            if (code != 200) throw new U.HttpError(code, "HTTP " + code);
            BufferedReader r = new BufferedReader(new InputStreamReader(h.getInputStream(), StandardCharsets.UTF_8));
            StringBuilder data = new StringBuilder();
            String id = null, line;
            while (run && (line = r.readLine()) != null) {
                lastData = System.currentTimeMillis();
                if (line.isEmpty()) {
                    if (data.length() > 0) {
                        handle(data.toString());
                        if (id != null) {
                            try { U.p(this).edit().putLong("since", Long.parseLong(id)).apply(); } catch (NumberFormatException ignored) { }
                        }
                    }
                    data.setLength(0);
                    id = null;
                } else if (line.startsWith("id:")) {
                    id = line.substring(3).trim();
                } else if (line.startsWith("data:")) {
                    if (data.length() > 0) data.append('\n');
                    data.append(line.substring(5).trim());
                }
            }
        } finally {
            h.disconnect();
        }
    }

    private void handle(String json) {
        try {
            JSONObject o = new JSONObject(json);
            String t = o.optString("t"), cid = o.optString("c");
            switch (t) {
                case "msg": Notif.message(this, o); break;
                case "call": Notif.call(this, o); break;
                case "callend": Notif.callEnd(this, cid); break;
                case "missed": Notif.missed(this, o); break;
                case "clear": Notif.clear(this, cid); break;
                case "logout":
                    U.p(this).edit().remove("token").apply();
                    run = false;
                    break;
                default: break;
            }
        } catch (Exception e) {
            Log.e(U.TAG, "zdarzenie: " + json, e);
        }
    }
}
