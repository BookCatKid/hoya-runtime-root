package dev.ghostlock.runner;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Headless trigger: am broadcast -a dev.ghostlock.runner.RUN [--es run 1] */
public final class RunReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        Intent i = new Intent(context, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        if (intent.hasExtra("run")) {
            i.putExtra("run", intent.getBooleanExtra("run", true));
        } else {
            i.putExtra("run", true);
        }
        context.startActivity(i);
    }
}
