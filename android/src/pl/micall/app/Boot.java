package pl.micall.app;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Po włączeniu telefonu i po aktualizacji MiCall — znowu działa w tle. */
public class Boot extends BroadcastReceiver {
    @Override public void onReceive(Context c, Intent i) {
        String a = i.getAction();
        if (Intent.ACTION_BOOT_COMPLETED.equals(a) || Intent.ACTION_MY_PACKAGE_REPLACED.equals(a)) {
            MiService.start(c.getApplicationContext());
        }
    }
}
