package com.doktorigi.slate

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.view.accessibility.AccessibilityEvent

/**
 * Fire OS hardwires Home to Amazon's launcher, won't let it be disabled, and handles the
 * remote's Home key before accessibility key filters see it. So: whenever Amazon's home
 * screen comes up, bring Slate up over it. Only enabled on Fire TV (via adb); harmless elsewhere.
 * ponytail: Android's 5 s post-Home app-switch lock means Amazon's screen shows ~4 s first;
 * only a system-signed app (STOP_APP_SWITCHES) could avoid that.
 */
class HomeGuard : AccessibilityService() {
    override fun onAccessibilityEvent(e: AccessibilityEvent) {
        if (e.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        if (e.packageName != "com.amazon.tv.launcher" || e.className?.contains("HomeActivity") != true) return
        startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    override fun onInterrupt() {}
}
