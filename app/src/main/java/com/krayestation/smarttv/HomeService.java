package com.krayestation.smarttv;

import android.accessibilityservice.AccessibilityService;
import android.content.Intent;
import android.os.SystemClock;
import android.view.accessibility.AccessibilityEvent;

/**
 * Fire OS won't let a third-party app become the real home app, so this
 * service watches for Amazon's home screen coming to the front (which is what
 * the remote's Home button does) and immediately brings Smart TV back on top.
 *
 * Turn it on in Settings > Accessibility > Smart TV Home button.
 */
public class HomeService extends AccessibilityService {

    /** Set when you pick "Fire TV Home" so Amazon's screen is allowed to stay. */
    static volatile boolean bypass = false;

    private long lastLaunch = 0;

    @Override
    public void onAccessibilityEvent(AccessibilityEvent e) {
        if (e == null || e.getEventType() != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return;
        CharSequence p = e.getPackageName();
        if (p == null) return;
        String pkg = p.toString();

        if (pkg.equals(MainActivity.AMAZON_HOME)) {
            if (bypass) return;
            long now = SystemClock.uptimeMillis();
            if (now - lastLaunch < 800) return;
            lastLaunch = now;
            Intent i = new Intent(this, MainActivity.class);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
            try { startActivity(i); } catch (Exception ignored) { }
        } else if (!pkg.equals(getPackageName())
                && !pkg.startsWith("com.android.systemui")
                && !pkg.startsWith("com.amazon.tv.settings")
                && !pkg.equals("android")) {
            // You left Amazon's home for a real app: Home returns to Smart TV again.
            bypass = false;
        }
    }

    @Override
    public void onInterrupt() { }
}
