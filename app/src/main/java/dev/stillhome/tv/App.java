package dev.stillhome.tv;

import android.app.Application;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Printer;

/** Gives Prefs a context and loads the fonts before any screen draws. */
public final class App extends Application {
    @Override
    public void onCreate() {
        super.onCreate();
        Prefs.init(this);
        Ui.init(this);
        if ((getApplicationInfo().flags & android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0) watchMainThread();
    }

    private volatile long messageStartedAt;
    private volatile String message = "";

    /**
     * Debug builds: any main-thread message over 48 ms (three frames) is logged with its target and callback, and a
     * watchdog thread logs where the main thread is once a message has run for 150 ms.
     */
    private void watchMainThread() {
        Looper.getMainLooper().setMessageLogging(new Printer() {
            @Override public void println(String x) {
                if (x.startsWith(">>>>> Dispatch")) {
                    message = x;
                    messageStartedAt = SystemClock.uptimeMillis();
                } else if (x.startsWith("<<<<< Finished")) {
                    long ms = SystemClock.uptimeMillis() - messageStartedAt;
                    messageStartedAt = 0;
                    if (ms >= 48) AppLog.w("main thread busy " + ms + " ms: " + (message.length() > 15 ? message.substring(15) : message));
                }
            }
        });
        final Thread mainThread = Looper.getMainLooper().getThread();
        Thread watchdog = new Thread(() -> {
            long reportedFor = 0;
            while (true) {
                try {
                    Thread.sleep(50);
                } catch (InterruptedException e) {
                    return;
                }
                long started = messageStartedAt;
                if (started == 0 || started == reportedFor || SystemClock.uptimeMillis() - started < 150) continue;
                reportedFor = started;
                StringBuilder sb = new StringBuilder("main thread stuck in: ");
                StackTraceElement[] stack = mainThread.getStackTrace();
                for (int i = 0; i < Math.min(14, stack.length); i++) sb.append(" | ").append(stack[i]);
                AppLog.w(sb.toString());
            }
        }, "main-watchdog");
        watchdog.setDaemon(true);
        watchdog.start();
    }
}
