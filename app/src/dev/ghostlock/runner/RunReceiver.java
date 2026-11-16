package dev.ghostlock.runner;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * Headless trigger: am broadcast -a dev.ghostlock.runner.RUN
 *
 * Starts the exploit service directly — deliberately NOT via MainActivity.
 * The activity's RenderThread aborts with EGL_BAD_ALLOC when it draws while
 * the display is off, which kills the whole process (and the exploit with
 * it). The service has no UI and is immune.
 */
public final class RunReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        Intent i = new Intent(context, ExploitService.class)
                .setAction(ExploitService.ACTION_RUN);
        if (android.os.Build.VERSION.SDK_INT >= 26) {
            context.startForegroundService(i);
        } else {
            context.startService(i);
        }
    }
}
