package pl.micall.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Person;
import android.app.RemoteInput;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ShortcutInfo;
import android.content.pm.ShortcutManager;
import android.graphics.Bitmap;
import android.graphics.drawable.Icon;
import android.media.AudioAttributes;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.Build;
import android.util.Log;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Powiadomienia jak w Messengerze: rozmowy, dymki czatu, połączenia. */
final class Notif {
    static final String CH_MSG = "msg2", CH_CALL = "calls2", CH_BG = "bg", CH_UPD = "upd";
    static final int ID_FG = 1, ID_MSG = 2, ID_CALL = 3, ID_MISS = 4, ID_UPD = 5;

    private Notif() {}

    static final class Msg {
        String text, uid, name, avatar;
        long ts;
        boolean me;
    }

    static final class Conv {
        String cid, title, mid = "";
        boolean group;
        Bitmap av;       // zdjęcie rozmowy (osoba albo litera grupy)
        Icon icon;       // ikona dymka / skrótu
        final List<Msg> msgs = new ArrayList<>();
    }

    private static final Map<String, Conv> CONVS = new HashMap<>();
    private static final Map<String, Bitmap> PEOPLE = new HashMap<>();

    static NotificationManager nm(Context c) { return (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE); }

    static int mut() { return Build.VERSION.SDK_INT >= 31 ? PendingIntent.FLAG_MUTABLE : 0; }

    static void channels(Context c) {
        NotificationManager m = nm(c);
        NotificationChannel msg = new NotificationChannel(CH_MSG, "Wiadomości", NotificationManager.IMPORTANCE_HIGH);
        msg.setDescription("Nowe wiadomości — z dymkami czatu jak w Messengerze");
        msg.enableVibration(true);
        msg.enableLights(true);
        msg.setLightColor(U.ACCENT);
        msg.setShowBadge(true);
        if (Build.VERSION.SDK_INT >= 29) msg.setAllowBubbles(true);
        m.createNotificationChannel(msg);

        NotificationChannel call = new NotificationChannel(CH_CALL, "Połączenia", NotificationManager.IMPORTANCE_HIGH);
        call.setDescription("Ktoś do Ciebie dzwoni w MiCall");
        call.setSound(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE),
                new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build());
        call.enableVibration(true);
        call.setVibrationPattern(new long[]{0, 900, 700, 900, 700, 900, 700});
        call.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
        m.createNotificationChannel(call);

        NotificationChannel bg = new NotificationChannel(CH_BG, "Działanie w tle", NotificationManager.IMPORTANCE_MIN);
        bg.setDescription("Stałe powiadomienie „MiCall działa w tle” — dzięki niemu wiadomości przychodzą od razu. Możesz je tu ukryć.");
        bg.setShowBadge(false);
        m.createNotificationChannel(bg);

        NotificationChannel upd = new NotificationChannel(CH_UPD, "Aktualizacje", NotificationManager.IMPORTANCE_DEFAULT);
        upd.setShowBadge(false);
        m.createNotificationChannel(upd);
    }

    static PendingIntent openApp(Context c, String cid, boolean answer, int rc) {
        Intent i = new Intent(c, MainActivity.class).setAction(Intent.ACTION_VIEW)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        if (cid != null) i.putExtra("open", cid);
        if (answer) i.putExtra("answer", true);
        i.setData(Uri.parse("micall://open/" + (cid == null ? "" : cid) + (answer ? "/answer" : "")));
        return PendingIntent.getActivity(c, rc, i, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    static PendingIntent action(Context c, String act, String cid, boolean mutable) {
        Intent i = new Intent(c, Actions.class).setAction(act).putExtra("c", cid)
                .setData(Uri.parse("micall://" + act + "/" + cid));
        return PendingIntent.getBroadcast(c, U.hash(act + cid), i,
                PendingIntent.FLAG_UPDATE_CURRENT | (mutable ? mut() : PendingIntent.FLAG_IMMUTABLE));
    }

    /** Stałe powiadomienie usługi w tle. */
    static Notification service(Context c, boolean inCall) {
        Notification.Builder b = new Notification.Builder(c, CH_BG)
                .setSmallIcon(R.drawable.ic_stat)
                .setColor(U.ACCENT)
                .setOngoing(true)
                .setShowWhen(false)
                .setCategory(Notification.CATEGORY_SERVICE)
                .setContentIntent(openApp(c, null, false, 1));
        if (inCall) b.setContentTitle("🎙️ Jesteś w rozmowie").setContentText("Dotknij, aby wrócić do MiCall");
        else b.setContentTitle("MiCall działa w tle").setContentText("Wiadomości i połączenia przyjdą od razu");
        return b.build();
    }

    static Person me() {
        return new Person.Builder().setName("Ty").setKey("me").build();
    }

    static Person person(Context c, Msg m) {
        Bitmap b;
        synchronized (PEOPLE) { b = PEOPLE.get(m.uid + "|" + m.avatar); }
        if (b == null) {
            b = U.avatar(c, m.avatar, m.name, m.uid);
            synchronized (PEOPLE) { PEOPLE.put(m.uid + "|" + m.avatar, b); }
        }
        return new Person.Builder().setName(m.name).setKey(m.uid == null || m.uid.isEmpty() ? m.name : m.uid)
                .setIcon(Icon.createWithBitmap(b)).build();
    }

    static Conv conv(String cid) {
        synchronized (CONVS) {
            Conv v = CONVS.get(cid);
            if (v == null) {
                v = new Conv();
                v.cid = cid;
                CONVS.put(cid, v);
            }
            return v;
        }
    }

    /** Skrót rozmowy (wymagany przez Androida do sekcji „Rozmowy” i dymków). */
    static void shortcut(Context c, Conv v, Person p) {
        try {
            ShortcutManager sm = c.getSystemService(ShortcutManager.class);
            if (sm == null) return;
            Intent it = new Intent(c, MainActivity.class).setAction(Intent.ACTION_VIEW).putExtra("open", v.cid);
            ShortcutInfo.Builder sb = new ShortcutInfo.Builder(c, v.cid)
                    .setShortLabel(v.title.isEmpty() ? "MiCall" : v.title)
                    .setLongLabel(v.title.isEmpty() ? "MiCall" : v.title)
                    .setIcon(v.icon)
                    .setIntent(it);
            if (Build.VERSION.SDK_INT >= 30) sb.setLongLived(true);
            if (Build.VERSION.SDK_INT >= 29 && p != null) sb.setPerson(p);
            sb.setCategories(Collections.singleton("pl.micall.app.ROZMOWA"));
            ShortcutInfo si = sb.build();
            if (Build.VERSION.SDK_INT >= 30) sm.pushDynamicShortcut(si);
            else {
                List<ShortcutInfo> l = new ArrayList<>();
                l.add(si);
                if (sm.getDynamicShortcuts().size() >= sm.getMaxShortcutCountPerActivity()) sm.removeAllDynamicShortcuts();
                sm.addDynamicShortcuts(l);
            }
        } catch (Exception e) {
            Log.w(U.TAG, "skrót: " + e);
        }
    }

    static void prepare(Context c, Conv v, String title, boolean group, String avatar, String uid, String name) {
        v.title = title == null ? "" : title;
        v.group = group;
        if (v.av == null || v.icon == null) {
            v.av = group ? U.letter(v.title.replace("#", ""), v.cid) : U.avatar(c, avatar, name, uid);
            v.icon = Icon.createWithAdaptiveBitmap(U.adaptive(v.av));
        }
    }

    /** Nowa wiadomość z centrali. */
    static void message(Context c, JSONObject o) {
        String cid = o.optString("c");
        if (cid.isEmpty()) return;
        boolean dm = o.optBoolean("dm", true), grp = o.optBoolean("grp", false);
        boolean group = !dm || grp;
        Msg m = new Msg();
        m.name = o.optString("n", o.optString("ti", "MiCall"));
        m.uid = o.optString("a");
        m.avatar = o.optString("i");
        m.text = o.optString("b", "Nowa wiadomość");
        m.ts = o.optLong("ts", System.currentTimeMillis());
        String cv = o.optString("cv");
        Conv v = conv(cid);
        synchronized (v) {
            prepare(c, v, group ? (cv.isEmpty() ? m.name : cv) : m.name, group, m.avatar, m.uid, m.name);
            v.msgs.add(m);
            while (v.msgs.size() > 8) v.msgs.remove(0);
            String mid = o.optString("mid");
            if (!mid.isEmpty()) v.mid = mid;
            post(c, v, true, false);
        }
    }

    /** Ja odpisałem z powiadomienia — dopisz moją wiadomość (bez dźwięku). */
    static void replied(Context c, String cid, String text) {
        Conv v = conv(cid);
        synchronized (v) {
            if (v.title == null) return;
            Msg m = new Msg();
            m.me = true;
            m.text = text;
            m.ts = System.currentTimeMillis();
            v.msgs.add(m);
            while (v.msgs.size() > 8) v.msgs.remove(0);
            post(c, v, false, false);
        }
    }

    static String bubbleUrl(Context c, String cid) { return U.base(c) + "/?bubble=" + Uri.encode(cid); }

    static boolean bubblesAllowed(Context c) {
        if (Build.VERSION.SDK_INT < 30) return false;
        NotificationManager m = nm(c);
        if (Build.VERSION.SDK_INT >= 31) return m.getBubblePreference() != NotificationManager.BUBBLE_PREFERENCE_NONE;
        return areBubblesAllowed30(m);
    }

    @SuppressWarnings("deprecation")
    static boolean areBubblesAllowed30(NotificationManager m) { return m.areBubblesAllowed(); }

    static void post(Context c, Conv v, boolean alert, boolean expand) {
        try {
            Person p = null;
            for (int i = v.msgs.size() - 1; i >= 0 && p == null; i--) if (!v.msgs.get(i).me) p = person(c, v.msgs.get(i));
            shortcut(c, v, v.group ? null : p);
            Notification.MessagingStyle st = new Notification.MessagingStyle(me());
            if (v.group) st.setConversationTitle(v.title);
            st.setGroupConversation(v.group);
            for (Msg m : v.msgs) st.addMessage(new Notification.MessagingStyle.Message(m.text, m.ts, m.me ? null : person(c, m)));

            RemoteInput ri = new RemoteInput.Builder("reply").setLabel("Odpowiedz…").build();
            Notification.Action reply = new Notification.Action.Builder(Icon.createWithResource(c, R.drawable.ic_stat), "Odpowiedz", action(c, Actions.REPLY, v.cid, true))
                    .addRemoteInput(ri).setAllowGeneratedReplies(true).setSemanticAction(Notification.Action.SEMANTIC_ACTION_REPLY).build();
            Notification.Action read = new Notification.Action.Builder(Icon.createWithResource(c, R.drawable.ic_stat), "Przeczytane", action(c, Actions.READ, v.cid, false))
                    .setSemanticAction(Notification.Action.SEMANTIC_ACTION_MARK_AS_READ).build();
            Msg last = v.msgs.isEmpty() ? null : v.msgs.get(v.msgs.size() - 1);
            Notification.Builder b = new Notification.Builder(c, CH_MSG)
                    .setSmallIcon(R.drawable.ic_stat)
                    .setColor(U.ACCENT)
                    .setContentTitle(v.title)
                    .setContentText(last == null ? "" : last.text)
                    .setStyle(st)
                    .setShortcutId(v.cid)
                    .setCategory(Notification.CATEGORY_MESSAGE)
                    .setContentIntent(openApp(c, v.cid, false, U.hash("open" + v.cid)))
                    .setDeleteIntent(action(c, Actions.DISMISS, v.cid, false))
                    .setAutoCancel(true)
                    .setOnlyAlertOnce(!alert)
                    .setShowWhen(true)
                    .setWhen(last == null ? System.currentTimeMillis() : last.ts)
                    .addAction(reply)
                    .addAction(read);
            if (!alert && Build.VERSION.SDK_INT >= 31) b.setSilent(true);
            if (Build.VERSION.SDK_INT >= 30) {
                Intent bi = new Intent(c, BubbleActivity.class).setAction(Intent.ACTION_VIEW)
                        .setData(Uri.parse(bubbleUrl(c, v.cid))).putExtra("cid", v.cid);
                PendingIntent bp = PendingIntent.getActivity(c, U.hash("bubble" + v.cid), bi, PendingIntent.FLAG_UPDATE_CURRENT | mut());
                Notification.BubbleMetadata md = new Notification.BubbleMetadata.Builder(bp, v.icon)
                        .setDesiredHeight(680)
                        .setAutoExpandBubble(expand)
                        .setSuppressNotification(expand)
                        .build();
                b.setBubbleMetadata(md);
            }
            nm(c).notify(v.cid, ID_MSG, b.build());
        } catch (Exception e) {
            Log.e(U.TAG, "powiadomienie", e);
        }
    }

    /** „Otwórz jako dymek czatu” z aplikacji. */
    static boolean openBubble(Context c, JSONObject o) {
        if (!bubblesAllowed(c)) return false;
        String cid = o.optString("c");
        if (cid.isEmpty()) return false;
        Conv v = conv(cid);
        synchronized (v) {
            prepare(c, v, o.optString("ti"), o.optBoolean("grp"), o.optString("i"), o.optString("a"), o.optString("ti"));
            if (v.msgs.isEmpty()) {
                Msg m = new Msg();
                m.name = v.title;
                m.uid = o.optString("a");
                m.avatar = o.optString("i");
                m.text = "💬";
                m.ts = System.currentTimeMillis();
                v.msgs.add(m);
            }
            post(c, v, false, true);
        }
        return true;
    }

    static void clear(Context c, String cid) {
        nm(c).cancel(cid, ID_MSG);
        nm(c).cancel("miss" + cid, ID_MISS);
        forget(cid);
    }

    static void forget(String cid) {
        Conv v;
        synchronized (CONVS) { v = CONVS.get(cid); }
        if (v != null) synchronized (v) { v.msgs.clear(); }
    }

    static String lastMid(String cid) {
        Conv v;
        synchronized (CONVS) { v = CONVS.get(cid); }
        return v == null ? "" : v.mid;
    }

    static void clearAll(Context c) {
        nm(c).cancelAll();
        synchronized (CONVS) { CONVS.clear(); }
    }

    /* ---------- połączenia ---------- */

    static void call(Context c, JSONObject o) {
        String cid = o.optString("c");
        if (cid.isEmpty()) return;
        String title = o.optString("ti", "MiCall"), name = o.optString("n", title), av = o.optString("i"), uid = o.optString("a");
        Bitmap b = U.avatar(c, av, name, uid);
        Intent ci = new Intent(c, CallActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_NO_USER_ACTION)
                .setData(Uri.parse("micall://call/" + cid))
                .putExtra("c", cid).putExtra("ti", title).putExtra("i", av).putExtra("a", uid).putExtra("n", name);
        PendingIntent full = PendingIntent.getActivity(c, U.hash("call" + cid), ci, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        PendingIntent answer = openApp(c, cid, true, U.hash("answer" + cid));
        PendingIntent decline = action(c, Actions.DECLINE, cid, false);
        Notification.Builder nb = new Notification.Builder(c, CH_CALL)
                .setSmallIcon(R.drawable.ic_stat)
                .setColor(U.ACCENT)
                .setContentTitle(title)
                .setContentText("📞 Dzwoni do Ciebie w MiCall")
                .setLargeIcon(b)
                .setCategory(Notification.CATEGORY_CALL)
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .setOngoing(true)
                .setAutoCancel(true)
                .setTimeoutAfter(45000)
                .setFullScreenIntent(full, true)
                .setContentIntent(full);
        Notification n = null;
        if (Build.VERSION.SDK_INT >= 31) {
            try {
                Person p = new Person.Builder().setName(name).setIcon(Icon.createWithBitmap(b)).setImportant(true).build();
                nb.setStyle(Notification.CallStyle.forIncomingCall(p, decline, answer));
                n = nb.build();
            } catch (Exception e) {
                Log.w(U.TAG, "CallStyle: " + e);
                nb.setStyle(null);
            }
        }
        if (n == null) {
            nb.addAction(new Notification.Action.Builder(Icon.createWithResource(c, R.drawable.ic_stat), "✖ Odrzuć", decline).build());
            nb.addAction(new Notification.Action.Builder(Icon.createWithResource(c, R.drawable.ic_stat), "📞 Odbierz", answer).build());
            n = nb.build();
        }
        n.flags |= Notification.FLAG_INSISTENT;
        nm(c).notify("call" + cid, ID_CALL, n);
    }

    static void callEnd(Context c, String cid) {
        nm(c).cancel("call" + cid, ID_CALL);
        CallActivity.end(cid);
    }

    static void missed(Context c, JSONObject o) {
        String cid = o.optString("c");
        if (cid.isEmpty()) return;
        callEnd(c, cid);
        String name = o.optString("n", o.optString("ti", "MiCall"));
        Bitmap b = U.avatar(c, o.optString("i"), name, o.optString("a"));
        Notification n = new Notification.Builder(c, CH_MSG)
                .setSmallIcon(R.drawable.ic_stat)
                .setColor(U.ACCENT)
                .setContentTitle(o.optString("ti", name))
                .setContentText("📵 Nieodebrane połączenie")
                .setLargeIcon(b)
                .setCategory(Notification.CATEGORY_MISSED_CALL)
                .setAutoCancel(true)
                .setWhen(o.optLong("ts", System.currentTimeMillis()))
                .setShowWhen(true)
                .setContentIntent(openApp(c, cid, false, U.hash("open" + cid)))
                .addAction(new Notification.Action.Builder(Icon.createWithResource(c, R.drawable.ic_stat), "📞 Oddzwoń", openApp(c, cid, true, U.hash("back" + cid))).build())
                .build();
        nm(c).notify("miss" + cid, ID_MISS, n);
    }

    /** Nowa wersja czeka na jedno dotknięcie (tylko pierwsza aktualizacja — potem instalują się same). */
    static void updateReady(Context c, Intent confirm) {
        PendingIntent p = PendingIntent.getActivity(c, 77, confirm, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification n = new Notification.Builder(c, CH_UPD)
                .setSmallIcon(R.drawable.ic_stat)
                .setColor(U.ACCENT)
                .setContentTitle("Nowa wersja MiCall")
                .setContentText("Dotknij, aby zainstalować — następne zainstalują się same")
                .setAutoCancel(true)
                .setContentIntent(p)
                .build();
        nm(c).notify(ID_UPD, n);
    }
}
