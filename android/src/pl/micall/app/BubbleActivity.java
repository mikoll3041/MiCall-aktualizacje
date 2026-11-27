package pl.micall.app;

import android.content.Intent;
import android.net.Uri;

/** Dymek czatu — jedna rozmowa w małym okienku nad innymi aplikacjami (jak w Messengerze). */
public class BubbleActivity extends Web {
    @Override String cid() {
        Intent i = getIntent();
        String c = i.getStringExtra("cid");
        Uri d = i.getData();
        if (c == null && d != null) c = d.getQueryParameter("bubble");
        return c == null ? "" : c;
    }

    @Override String url(Intent i) { return Notif.bubbleUrl(this, cid()); }

    @Override void leave() { finish(); }
}
