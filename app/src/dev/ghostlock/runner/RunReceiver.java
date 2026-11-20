package dev.ghostlock.runner;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Headless trigger: am broadcast -a dev.ghostlock.runner.RUN */
public final class RunReceiver extends BroadcastReceiver {

    /**
     * The manifest sets android:permission="android.permission.DUMP" on
     * this receiver, so only adb shell and root can deliver the trigger.
     * Any other app on the device is rejected by the system before this
     * runs: a run is probabilistic and a panic reboots the box.
     */
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
