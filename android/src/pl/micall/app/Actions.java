package pl.micall.app;

import android.app.RemoteInput;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.util.Log;

import org.json.JSONObject;

/** Przyciski w powiadomieniach: Odpowiedz, Przeczytane, Odrzuć połączenie; wynik instalacji aktualizacji. */
public class Actions extends BroadcastReceiver {
    static final String REPLY = "pl.micall.app.REPLY", READ = "pl.micall.app.READ", DISMISS = "pl.micall.app.DISMISS",
            DECLINE = "pl.micall.app.DECLINE", INSTALL = "pl.micall.app.INSTALL";

    @Override public void onReceive(Context ctx, Intent i) {
        final Context c = ctx.getApplicationContext();
        String a = i.getAction();
        final String cid = i.getStringExtra("c");
        if (a == null) return;
        switch (a) {
            case REPLY: {
                Bundle r = RemoteInput.getResultsFromIntent(i);
                CharSequence cs = r == null ? null : r.getCharSequence("reply");
                final String text = cs == null ? "" : cs.toString().trim();
                if (text.isEmpty() || cid == null) return;
                final PendingResult pr = goAsync();
                new Thread(() -> {
                    try {
                        JSONObject b = new JSONObject();
                        b.put("content", text);
                        b.put("nonce", U.randHex(8));
                        U.http(c, "POST", "/api/channels/" + cid + "/messages", b, 9000);
                        Notif.replied(c, cid, text);
                    } catch (Exception e) {
                        Log.w(U.TAG, "odpowiedź: " + e);
                        Notif.replied(c, cid, "⚠️ Nie wysłano: " + text);
                    } finally {
                        pr.finish();
                    }
                }, "reply").start();
                break;
            }
            case READ: {
                if (cid == null) return;
                final String mid = Notif.lastMid(cid);
                Notif.clear(c, cid);
                final PendingResult pr = goAsync();
                new Thread(() -> {
                    try {
                        JSONObject b = new JSONObject();
                        b.put("messageId", mid);
                        U.http(c, "POST", "/api/channels/" + cid + "/ack", b, 9000);
                    } catch (Exception e) {
                        Log.w(U.TAG, "przeczytane: " + e);
                    } finally {
                        pr.finish();
                    }
                }, "ack").start();
                break;
            }
            case DISMISS:
                if (cid != null) Notif.forget(cid);
                break;
            case DECLINE:
                if (cid != null) Notif.callEnd(c, cid);
                break;
            case INSTALL:
                Updater.onStatus(c, i);
                break;
            default:
                break;
        }
    }
}
