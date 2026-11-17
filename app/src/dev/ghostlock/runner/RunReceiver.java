package dev.ghostlock.runner;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Headless trigger: am broadcast -a dev.ghostlock.runner.RUN */
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
