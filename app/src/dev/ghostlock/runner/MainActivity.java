package dev.ghostlock.runner;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.text.InputType;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayDeque;

/** UI for the GhostLock runner. The exploit runs in ExploitService (foreground-service oom_score_adj). */
public final class MainActivity extends Activity {

    private static final int BG = 0xFF0F172A;
    private static final int PANEL = 0xFF1E293B;
    private static final int BORDER = 0xFF334155;
    private static final int TEXT = 0xFFE2E8F0;
    private static final int MUTED = 0xFF94A3B8;
    private static final int DIM = 0xFF64748B;
    private static final int OK = 0xFF4ADE80;
    private static final int WARN = 0xFFFBBF24;
    private static final int BAD = 0xFFF87171;

    private static final int MAX_LINES = 600;

    private final ArrayDeque<String> lines = new ArrayDeque<String>();
    private final Object refreshLock = new Object();
    private TextView status;
    private TextView banner;
    private TextView console;
    private ScrollView scroller;
    private Button netButton;
    private volatile boolean poller;
    private volatile boolean refreshing;
    private volatile long shownLogLen = -1;
    private volatile long lastRootProbe;
    private volatile boolean lastRootOk;
    /** True once the console holds the user's own output instead of the payload log. */
    private volatile boolean consoleOwned;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // Keep the display awake while the user reads the console.
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        Runner.wipeStaleStateIfNewBoot(this);
        setContentView(buildUi());
        append("payload: " + Payload.dir(this) + "/" + Payload.NAME);
        refreshStatusAsync();
        startPoller();
        if (ExploitService.netShellOn(this)) {
            // net_wanted.txt says the user wants the shell, but the
            // listener died with the previous process. Start the service
            // (null action) so it binds again.
            startService(new Intent(this, ExploitService.class));
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshStatusAsync();
        startPoller();
    }

    @Override
    protected void onDestroy() {
        poller = false;
        super.onDestroy();
    }

    // ---------------------------------------------------------------- ui

    private View buildUi() {
        int pad = dp(28);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(BG);
        root.setPadding(pad, pad, pad, pad);

        TextView title = new TextView(this);
        title.setText(R.string.app_name);
        title.setTextSize(22);
        title.setTextColor(TEXT);
        title.setPadding(0, 0, 0, dp(8));

        status = new TextView(this);
        status.setTextSize(16);
        status.setTypeface(Typeface.DEFAULT_BOLD);
        status.setText("checking root");
        status.setTextColor(MUTED);
        status.setPadding(dp(16), dp(12), dp(16), dp(12));
        status.setBackground(box(PANEL, BORDER));

        banner = new TextView(this);
        banner.setTextSize(32);
        banner.setTypeface(Typeface.DEFAULT_BOLD);
        banner.setGravity(android.view.Gravity.CENTER);
        banner.setPadding(0, dp(28), 0, dp(28));
        banner.setVisibility(View.GONE);
        try {
            android.graphics.drawable.Drawable ghost =
                    getDrawable(R.drawable.ic_launcher_foreground);
            banner.setCompoundDrawablesWithIntrinsicBounds(null, ghost, null, null);
            banner.setCompoundDrawablePadding(dp(10));
        } catch (Throwable ignored) {
        }

        console = new TextView(this);
        console.setTextColor(MUTED);
        console.setTextSize(12);
        console.setTypeface(Typeface.MONOSPACE);
        console.setPadding(dp(4), dp(8), dp(4), dp(8));

        scroller = new ScrollView(this);
        scroller.setBackground(box(0xFF0B1120, BORDER));
        scroller.addView(console);
        LinearLayout.LayoutParams scrollerParams =
                new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f);
        scrollerParams.topMargin = dp(12);
        scrollerParams.bottomMargin = dp(12);

        LinearLayout buttons = new LinearLayout(this);
        buttons.setOrientation(LinearLayout.HORIZONTAL);

        buttons.addView(button("Get root", new Runnable() {
            @Override
            public void run() {
                confirmRun();
            }
        }), weight());
        buttons.addView(button("Root shell", new Runnable() {
            @Override
            public void run() {
                shellPrompt();
            }
        }), weight());
        netButton = button("Network shell", new Runnable() {
            @Override
            public void run() {
                toggleNetShell();
            }
        });
        buttons.addView(netButton, weight());
        buttons.addView(button("Clear", new Runnable() {
            @Override
            public void run() {
                clearLog();
            }
        }), weight());

        root.addView(title);
        root.addView(status);
        root.addView(banner);
        root.addView(scroller, scrollerParams);
        root.addView(buttons);
        return root;
    }

    private Button button(String label, final Runnable action) {
        Button b = new Button(this);
        b.setText(label);
        b.setTextSize(15);
        b.setAllCaps(false);
        b.setTextColor(TEXT);
        b.setBackground(box(PANEL, BORDER));
        b.setPadding(0, dp(10), 0, dp(10));
        b.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                action.run();
            }
        });
        return b;
    }

    private LinearLayout.LayoutParams weight() {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        p.rightMargin = dp(4);
        p.leftMargin = dp(4);
        return p;
    }

    private GradientDrawable box(int fill, int stroke) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(fill);
        d.setCornerRadius(dp(10));
        d.setStroke(1, stroke);
        return d;
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    // ------------------------------------------------------------- status

    private void showBanner(final String text, final int color) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                banner.setText(text);
                banner.setTextColor(color);
                banner.setVisibility(View.VISIBLE);
            }
        });
    }

    private void hideBanner() {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                banner.setVisibility(View.GONE);
            }
        });
    }

    private void setStatus(final String text, final int color) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                status.setText(text);
                status.setTextColor(color);
            }
        });
    }

    private void append(final String s) {
        writeLogFile(s);
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                appendNow(s);
            }
        });
    }

    private void appendNow(String s) {
        synchronized (lines) {
            lines.addLast(s);
            if (lines.size() > MAX_LINES) {
                lines.removeFirst();
            }
            StringBuilder sb = new StringBuilder();
            for (String l : lines) {
                sb.append(l).append('\n');
            }
            console.setText(sb);
        }
        scroller.fullScroll(View.FOCUS_DOWN);
    }

    private void clearLog() {
        consoleOwned = true;
        synchronized (lines) {
            lines.clear();
            console.setText("");
        }
        shownLogLen = Runner.runLogLength(this);
    }

    private void writeLogFile(String s) {
        try {
            java.io.FileOutputStream f = new java.io.FileOutputStream(
                    new java.io.File(Payload.dir(this), "runner_ui.log"), true);
            try {
                f.write((s + "\n").getBytes("UTF-8"));
            } finally {
                f.close();
            }
        } catch (Exception ignored) {
        }
    }

    // -------------------------------------------------------------- flow

    /** The mailbox check blocks; keep refreshStatus off the UI thread. */
    private void refreshStatusAsync() {
        synchronized (refreshLock) {
            if (refreshing) {
                return;
            }
            refreshing = true;
        }
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    refreshStatus();
                } finally {
                    refreshing = false;
                }
            }
        }, "ghost-refresh").start();
    }

    private void refreshStatus() {
        String s = ExploitService.status(this);
        if (s.startsWith("running") && ExploitService.exploitActive()) {
            setStatus(s, WARN);
            hideBanner();
        } else {
            // A probe with no daemon costs the full timeout, so probe at
            // most every 10s. Never write the result back to the status
            // file: a single slow probe would wipe a real ROOTED record.
            long now = System.currentTimeMillis();
            if (lastRootProbe == 0 || now - lastRootProbe >= 10_000) {
                lastRootProbe = now;
                lastRootOk = Runner.channelAnswersAsRoot(this, 5_000);
            }
            if (lastRootOk) {
                int pid = Runner.rootDaemonPid(this);
                setStatus("ROOTED" + (pid > 0 ? " · pid " + pid : ""), OK);
                showBanner("ROOTED!", OK);
            } else if (Runner.rootMarkerExists(this)) {
                setStatus("root daemon not answering. Tap Get root", WARN);
                hideBanner();
            } else if (ExploitService.isFailure(s)) {
                setStatus(s, BAD);
                showBanner("exploit failed", WARN);
            } else {
                setStatus("not rooted. Tap Get root", BAD);
                hideBanner();
            }
        }
        updateNetButton();
        // Mirror the payload log while a run is live, and once on open.
        // After that the console belongs to the user (shell output, Clear).
        if (!consoleOwned && (ExploitService.exploitActive() || shownLogLen < 0)) {
            long logLen = Runner.runLogLength(this);
            if (logLen > 0 && logLen != shownLogLen) {
                shownLogLen = logLen;
                showPayloadLog(Runner.runLogTail(this, 300));
            }
        }
    }

    /** Replace the console with the payload log tail. */
    private void showPayloadLog(final String tail) {
        if (tail.length() == 0) {
            return;
        }
        final String[] body = tail.split("\n");
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                synchronized (lines) {
                    lines.clear();
                    lines.add("payload: " + Payload.dir(MainActivity.this) + "/"
                            + Payload.NAME);
                    for (String l : body) {
                        if (l.length() > 0) {
                            lines.add("  " + l);
                        }
                    }
                    while (lines.size() > MAX_LINES) {
                        lines.removeFirst();
                    }
                    StringBuilder sb = new StringBuilder();
                    for (String l : lines) {
                        sb.append(l).append('\n');
                    }
                    console.setText(sb);
                }
                scroller.fullScroll(View.FOCUS_DOWN);
            }
        });
    }

    private void startPoller() {
        if (poller) {
            return;
        }
        poller = true;
        new Thread(new Runnable() {
            @Override
            public void run() {
                while (poller) {
                    try {
                        Thread.sleep(3000);
                    } catch (InterruptedException e) {
                        return;
                    }
                    if (isFinishing() || isDestroyed()) {
                        return;
                    }
                    refreshStatusAsync();
                }
            }
        }, "ghost-poll").start();
    }

    private void confirmRun() {
        if (ExploitService.exploitActive()) {
            setStatus("run already in progress", WARN);
            return;
        }
        new Thread(new Runnable() {
            @Override
            public void run() {
                final boolean rooted = Runner.channelAnswersAsRoot(
                        MainActivity.this, 3_000);
                final String s = ExploitService.status(MainActivity.this);
                final boolean failed = ExploitService.isFailure(s);
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        if (isFinishing() || isDestroyed()) {
                            return;
                        }
                        if (rooted) {
                            showRerunDialog();
                        } else if (failed) {
                            showFailedRetryDialog();
                        } else {
                            startRunAndLeave();
                        }
                    }
                });
            }
        }, "ghost-confirm").start();
    }

    private void showRerunDialog() {
        new AlertDialog.Builder(this)
                .setTitle("Re-run the exploit?")
                .setMessage("A root daemon already exists. Each run leaves parked kernel "
                        + "threads behind; repeated runs without a reboot can destabilise "
                        + "the device.\n\nReboot first if this device has run the exploit "
                        + "many times.")
                .setPositiveButton("Run anyway", (d, w) -> startRunAndLeave())
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void showFailedRetryDialog() {
        new AlertDialog.Builder(this)
                .setTitle("Last run failed")
                .setMessage("Reboot before retrying. Failed runs leave parked kernel "
                        + "threads, and stacking runs makes panics likelier.")
                .setPositiveButton("Run anyway", (d, w) -> startRunAndLeave())
                .setNegativeButton("Cancel", null)
                .show();
    }

    /** Hand the run to the foreground service and exit. */
    private void startRunAndLeave() {
        consoleOwned = false;
        shownLogLen = -1;
        Intent i = new Intent(this, ExploitService.class)
                .setAction(ExploitService.ACTION_RUN);
        if (android.os.Build.VERSION.SDK_INT >= 26) {
            startForegroundService(i);
        } else {
            startService(i);
        }
        // A stopped-but-alive activity can still draw a frame as the display
        // dozes; RenderThread aborts with EGL_BAD_ALLOC on this device.
        finish();
    }

    // ----------------------------------------------------------- shells

    private void toggleNetShell() {
        Intent i = new Intent(this, ExploitService.class)
                .setAction(ExploitService.ACTION_NET);
        if (android.os.Build.VERSION.SDK_INT >= 26) {
            startForegroundService(i);
        } else {
            startService(i);
        }
        // Reflect actual state after the service applies it.
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    Thread.sleep(800);
                } catch (InterruptedException ignored) {
                }
                updateNetButton();
                refreshStatusAsync();
            }
        }).start();
    }

    private void updateNetButton() {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                netButton.setText(RootServer.listening()
                        ? "Stop net shell" : "Network shell");
            }
        });
    }

    private void shellPrompt() {
        final EditText input = new EditText(this);
        input.setTextColor(TEXT);
        input.setHintTextColor(DIM);
        input.setHint("id");
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        input.setMinLines(3);
        input.setTypeface(Typeface.MONOSPACE);
        input.setBackground(box(PANEL, BORDER));
        input.setPadding(dp(12), dp(12), dp(12), dp(12));

        new AlertDialog.Builder(this)
                .setTitle("Run as root")
                .setView(input)
                .setPositiveButton("Run", (d, w) -> shell(input.getText().toString()))
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void shell(final String script) {
        if (script.trim().length() == 0) {
            return;
        }
        consoleOwned = true;
        new Thread(new Runnable() {
            @Override
            public void run() {
                append("$ " + script.replace("\n", "\n  "));
                // Nothing answers the mailbox without a live daemon. Say so
                // instead of blocking the probe timeout and printing nothing.
                if (!Runner.channelAnswersAsRoot(MainActivity.this, 3_000)) {
                    append("  not rooted. Tap Get root first");
                    return;
                }
                try {
                    String out = Runner.rootCmd(MainActivity.this, script, 30_000);
                    if (out.trim().length() == 0) {
                        append("  (no response)");
                        return;
                    }
                    for (String l : out.split("\n")) {
                        append("  " + l);
                    }
                    if (out.indexOf("[rc=") < 0) {
                        append("  (timed out; the command may still be running)");
                    }
                } catch (Exception e) {
                    append("shell error: " + e);
                }
            }
        }, "ghost-shell").start();
    }
}
