package pl.micall.app;

import android.animation.ObjectAnimator;
import android.animation.PropertyValuesHolder;
import android.animation.ValueAnimator;
import android.app.Activity;
import android.app.KeyguardManager;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Przychodzące połączenie na pełnym ekranie: zdjęcie, imię, Odrzuć / Odbierz. */
public class CallActivity extends Activity {
    static volatile CallActivity cur;
    String cid = "";
    private final Handler h = new Handler(Looper.getMainLooper());

    int dp(float v) { return Math.round(v * getResources().getDisplayMetrics().density); }

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        cur = this;
        setShowWhenLocked(true);
        setTurnScreenOn(true);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        show(getIntent());
    }

    @Override protected void onNewIntent(Intent i) {
        super.onNewIntent(i);
        setIntent(i);
        show(i);
    }

    void show(Intent i) {
        cid = String.valueOf(i.getStringExtra("c"));
        final String title = i.getStringExtra("ti") == null ? "MiCall" : i.getStringExtra("ti");
        final String name = i.getStringExtra("n") == null ? title : i.getStringExtra("n");
        final String av = i.getStringExtra("i"), uid = i.getStringExtra("a");

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setGravity(Gravity.CENTER_HORIZONTAL);
        col.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[]{0xFF3B1C7A, 0xFF0B0616}));
        col.setPadding(dp(24), dp(96), dp(24), dp(64));

        final ImageView img = new ImageView(this);
        img.setImageBitmap(U.letter(name, uid));
        col.addView(img, new LinearLayout.LayoutParams(dp(150), dp(150)));
        ObjectAnimator pulse = ObjectAnimator.ofPropertyValuesHolder(img,
                PropertyValuesHolder.ofFloat(View.SCALE_X, 1f, 1.07f), PropertyValuesHolder.ofFloat(View.SCALE_Y, 1f, 1.07f));
        pulse.setDuration(700);
        pulse.setRepeatCount(ValueAnimator.INFINITE);
        pulse.setRepeatMode(ValueAnimator.REVERSE);
        pulse.start();

        TextView t = new TextView(this);
        t.setText(title);
        t.setTextColor(Color.WHITE);
        t.setTextSize(30);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams tl = new LinearLayout.LayoutParams(-1, -2);
        tl.topMargin = dp(28);
        col.addView(t, tl);

        TextView sub = new TextView(this);
        sub.setText("📞 Dzwoni do Ciebie w MiCall…");
        sub.setTextColor(0xFFC4B5FD);
        sub.setTextSize(17);
        sub.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams sl = new LinearLayout.LayoutParams(-1, -2);
        sl.topMargin = dp(8);
        col.addView(sub, sl);

        View space = new View(this);
        col.addView(space, new LinearLayout.LayoutParams(-1, 0, 1f));

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER);
        row.addView(button(0xFFEF4444, "✖", "Odrzuć", v -> decline()), new LinearLayout.LayoutParams(0, -2, 1f));
        row.addView(button(0xFF22C55E, "📞", "Odbierz", v -> answer()), new LinearLayout.LayoutParams(0, -2, 1f));
        col.addView(row, new LinearLayout.LayoutParams(-1, -2));
        setContentView(col);

        new Thread(() -> {
            final Bitmap bm = U.avatar(getApplicationContext(), av, name, uid);
            runOnUiThread(() -> img.setImageBitmap(bm));
        }, "call-av").start();
        h.removeCallbacksAndMessages(null);
        h.postDelayed(this::finish, 45000);
    }

    View button(int color, String icon, String label, View.OnClickListener on) {
        LinearLayout b = new LinearLayout(this);
        b.setOrientation(LinearLayout.VERTICAL);
        b.setGravity(Gravity.CENTER_HORIZONTAL);
        TextView circle = new TextView(this);
        circle.setText(icon);
        circle.setTextSize(30);
        circle.setTextColor(Color.WHITE);
        circle.setGravity(Gravity.CENTER);
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.OVAL);
        g.setColor(color);
        circle.setBackground(g);
        circle.setOnClickListener(on);
        b.addView(circle, new LinearLayout.LayoutParams(dp(76), dp(76)));
        TextView l = new TextView(this);
        l.setText(label);
        l.setTextColor(0xFFE9E3FF);
        l.setTextSize(15);
        l.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams ll = new LinearLayout.LayoutParams(-2, -2);
        ll.topMargin = dp(10);
        b.addView(l, ll);
        FrameLayout wrap = new FrameLayout(this);
        wrap.addView(b, new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER));
        return wrap;
    }

    void answer() {
        Notif.nm(this).cancel("call" + cid, Notif.ID_CALL);
        KeyguardManager km = getSystemService(KeyguardManager.class);
        if (km != null && km.isKeyguardLocked()) km.requestDismissKeyguard(this, null);
        startActivity(new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .putExtra("open", cid).putExtra("answer", true));
        finish();
    }

    void decline() {
        Notif.callEnd(this, cid);
        finish();
    }

    static void end(String cid) {
        CallActivity a = cur;
        if (a != null && cid != null && cid.equals(a.cid)) a.runOnUiThread(a::finish);
    }

    @Override protected void onDestroy() {
        if (cur == this) cur = null;
        h.removeCallbacksAndMessages(null);
        super.onDestroy();
    }
}
