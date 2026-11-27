package pl.micall.app;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.util.Log;
import android.util.LruCache;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;

/** Wspólne drobiazgi: ustawienia, adres centrali, zapytania HTTP, awatary. */
final class U {
    static final String TAG = "MiCall";
    static final String BASE = "https://sandra221-20221.wykr.es";
    static final int ACCENT = 0xFF8B5CF6;
    static final SecureRandom RND = new SecureRandom();
    private static final LruCache<String, Bitmap> AV = new LruCache<>(60);

    private U() {}

    static SharedPreferences p(Context c) {
        return c.getApplicationContext().getSharedPreferences("micall", Context.MODE_PRIVATE);
    }

    static String base(Context c) { return p(c).getString("base", BASE); }

    static String token(Context c) { return p(c).getString("token", ""); }

    static String device(Context c) {
        String d = p(c).getString("dev", "");
        if (d.length() < 6) {
            d = randHex(8);
            p(c).edit().putString("dev", d).apply();
        }
        return d;
    }

    static String randHex(int bytes) {
        byte[] b = new byte[bytes];
        RND.nextBytes(b);
        StringBuilder s = new StringBuilder();
        for (byte x : b) s.append(String.format("%02x", x & 0xff));
        return s.toString();
    }

    static PackageInfo pkg(Context c) {
        try {
            return c.getPackageManager().getPackageInfo(c.getPackageName(), 0);
        } catch (Exception e) {
            return null;
        }
    }

    static String verName(Context c) {
        PackageInfo i = pkg(c);
        return i != null && i.versionName != null ? i.versionName : "?";
    }

    @SuppressWarnings("deprecation")
    static long verCode(Context c) {
        PackageInfo i = pkg(c);
        if (i == null) return 0;
        return android.os.Build.VERSION.SDK_INT >= 28 ? i.getLongVersionCode() : i.versionCode;
    }

    static String ua(Context c) { return "MiCallAndroid/" + verName(c); }

    /** Adres względny centrali → pełny. */
    static String abs(Context c, String u) {
        if (u == null || u.isEmpty()) return "";
        if (u.startsWith("https://") || u.startsWith("http://")) return u;
        if (u.startsWith("/")) return base(c) + u;
        return "";
    }

    static int hash(String s) { return s == null ? 0 : s.hashCode() & 0x7fffffff; }

    static HttpURLConnection open(Context c, String method, String path, int timeout) throws IOException {
        String full = abs(c, path);
        HttpURLConnection h = (HttpURLConnection) new URL(full).openConnection();
        h.setRequestMethod(method);
        h.setConnectTimeout(timeout);
        h.setReadTimeout(timeout);
        h.setRequestProperty("User-Agent", ua(c));
        String t = token(c);
        // sesja tylko do własnej centrali (nigdy do obcych adresów, np. zdjęć z internetu)
        if (!t.isEmpty() && (full + "/").startsWith(base(c) + "/")) h.setRequestProperty("Authorization", "Bearer " + t);
        return h;
    }

    static final class HttpError extends IOException {
        final int code;
        HttpError(int code, String msg) { super(msg); this.code = code; }
    }

    /** Zapytanie do API centrali (JSON). */
    static String http(Context c, String method, String path, JSONObject body, int timeout) throws IOException {
        HttpURLConnection h = open(c, method, path, timeout);
        try {
            if (body != null) {
                h.setDoOutput(true);
                h.setRequestProperty("Content-Type", "application/json");
                byte[] b = body.toString().getBytes(StandardCharsets.UTF_8);
                h.setFixedLengthStreamingMode(b.length);
                OutputStream o = h.getOutputStream();
                o.write(b);
                o.close();
            }
            int code = h.getResponseCode();
            InputStream in = code < 400 ? h.getInputStream() : h.getErrorStream();
            String txt = in == null ? "" : new String(readAll(in, 4 << 20), StandardCharsets.UTF_8);
            if (code / 100 != 2) throw new HttpError(code, "HTTP " + code + " " + txt);
            return txt;
        } finally {
            h.disconnect();
        }
    }

    static byte[] readAll(InputStream in, int max) throws IOException {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        byte[] b = new byte[16384];
        int n;
        while ((n = in.read(b)) > 0) {
            o.write(b, 0, n);
            if (o.size() > max) throw new IOException("za duże");
        }
        in.close();
        return o.toByteArray();
    }

    static String sha256(byte[] b) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(b);
            StringBuilder s = new StringBuilder();
            for (byte x : d) s.append(String.format("%02x", x & 0xff));
            return s.toString();
        } catch (Exception e) {
            return "";
        }
    }

    /* ---------- awatary ---------- */

    static final int[] COLORS = {0xFF8B5CF6, 0xFFEC4899, 0xFF3B82F6, 0xFF10B981, 0xFFF59E0B, 0xFFEF4444, 0xFF06B6D4, 0xFFA855F7};

    /** Kółko z pierwszą literą (gdy ktoś nie ma zdjęcia albo nie da się go pobrać). */
    static Bitmap letter(String name, String key) {
        int s = 192;
        Bitmap b = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888);
        Canvas cv = new Canvas(b);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setColor(COLORS[hash(key == null || key.isEmpty() ? name : key) % COLORS.length]);
        cv.drawCircle(s / 2f, s / 2f, s / 2f, p);
        String l = name == null || name.trim().isEmpty() ? "?" : name.trim().substring(0, name.trim().offsetByCodePoints(0, 1)).toUpperCase();
        p.setColor(Color.WHITE);
        p.setTextSize(s * 0.46f);
        p.setFakeBoldText(true);
        p.setTextAlign(Paint.Align.CENTER);
        Paint.FontMetrics fm = p.getFontMetrics();
        cv.drawText(l, s / 2f, s / 2f - (fm.ascent + fm.descent) / 2f, p);
        return b;
    }

    static Bitmap circle(Bitmap src) {
        int s = 192;
        Bitmap out = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888);
        Canvas cv = new Canvas(out);
        int w = src.getWidth(), h = src.getHeight(), m = Math.min(w, h);
        Rect from = new Rect((w - m) / 2, (h - m) / 2, (w - m) / 2 + m, (h - m) / 2 + m);
        Bitmap sq = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888);
        new Canvas(sq).drawBitmap(src, from, new RectF(0, 0, s, s), new Paint(Paint.FILTER_BITMAP_FLAG));
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setShader(new BitmapShader(sq, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP));
        cv.drawCircle(s / 2f, s / 2f, s / 2f, p);
        return out;
    }

    /** Ikona „adaptacyjna” (dymek, skrót rozmowy): zdjęcie w środkowych 2/3, system przycina je do kółka. */
    static Bitmap adaptive(Bitmap round) {
        int s = 324, in = 216;
        Bitmap b = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888);
        Canvas cv = new Canvas(b);
        cv.drawColor(0xFF20103B);
        int o = (s - in) / 2;
        cv.drawBitmap(round, null, new RectF(o, o, o + in, o + in), new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG));
        return b;
    }

    /** Okrągły awatar (pamięć + dysk). Wywoływać poza wątkiem głównym. */
    static Bitmap avatar(Context c, String url, String name, String key) {
        String u = abs(c, url);
        if (u.isEmpty()) return letter(name, key);
        Bitmap m = AV.get(u);
        if (m != null) return m;
        File dir = new File(c.getCacheDir(), "av");
        dir.mkdirs();
        File f = new File(dir, sha256(u.getBytes(StandardCharsets.UTF_8)).substring(0, 32) + ".png");
        Bitmap b = null;
        if (f.exists() && System.currentTimeMillis() - f.lastModified() < 3L * 86400000L) b = BitmapFactory.decodeFile(f.getPath());
        if (b == null) {
            try {
                HttpURLConnection h = open(c, "GET", u, 10000);
                try {
                    if (h.getResponseCode() == 200) {
                        byte[] data = readAll(h.getInputStream(), 8 << 20);
                        BitmapFactory.Options o = new BitmapFactory.Options();
                        o.inJustDecodeBounds = true;
                        BitmapFactory.decodeByteArray(data, 0, data.length, o);
                        int ss = 1;
                        while (Math.min(o.outWidth, o.outHeight) / (ss * 2) >= 192) ss *= 2;
                        o = new BitmapFactory.Options();
                        o.inSampleSize = ss;
                        Bitmap raw = BitmapFactory.decodeByteArray(data, 0, data.length, o);
                        if (raw != null) {
                            b = circle(raw);
                            FileOutputStream fo = new FileOutputStream(f);
                            b.compress(Bitmap.CompressFormat.PNG, 100, fo);
                            fo.close();
                        }
                    }
                } finally {
                    h.disconnect();
                }
            } catch (Exception e) {
                Log.w(TAG, "awatar: " + e);
            }
        }
        if (b == null) return letter(name, key);
        AV.put(u, b);
        return b;
    }
}
