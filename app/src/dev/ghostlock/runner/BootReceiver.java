package dev.ghostlock.runner;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import java.io.File;
import java.io.FileOutputStream;
import java.io.RandomAccessFile;

/**
 * Auto-root on boot. The exploit is per-boot and panics sometimes, so the
 * reliable product shape is: install once, every boot gets a fresh lottery
 * ticket until ghost_rootd answers. Capped so a panic-loop can't brick the
 * device — after AUTO_MAX starts it waits for a manual tap.
 */
public final class BootReceiver extends BroadcastReceiver {

    static final String COUNTER_FILE = "auto_runs.txt";
    static final int AUTO_MAX = 5;

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || !Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) {
            return;
        }
        // Already rooted this boot? nothing to do.
        if (Runner.rootDaemonPid(context) > 0) {
            return;
        }
        int runs = readCounter(context);
        if (runs >= AUTO_MAX) {
            ExploitService.setStatus(context, "auto-root paused after " + AUTO_MAX
                    + " tries — tap Get root to try again");
            return;
        }
        writeCounter(context, runs + 1);
        Intent i = new Intent(context, ExploitService.class)
                .setAction(ExploitService.ACTION_RUN)
                .putExtra("auto", true);
        if (android.os.Build.VERSION.SDK_INT >= 26) {
            context.startForegroundService(i);
        } else {
            context.startService(i);
        }
    }

    static int readCounter(Context ctx) {
        try {
            RandomAccessFile f = new RandomAccessFile(
                    new File(Payload.dir(ctx), COUNTER_FILE), "r");
            String s = f.readLine();
            f.close();
            return Integer.parseInt(s.trim());
        } catch (Exception e) {
            return 0;
        }
    }

    static void writeCounter(Context ctx, int n) {
        try {
            FileOutputStream f = new FileOutputStream(
                    new File(Payload.dir(ctx), COUNTER_FILE), false);
            f.write((n + "\n").getBytes("UTF-8"));
            f.close();
        } catch (Exception ignored) {
        }
    }

    static void resetCounter(Context ctx) {
        writeCounter(ctx, 0);
    }
}
