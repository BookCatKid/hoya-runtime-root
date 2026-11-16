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

/**
 * UI for the GhostLock runner. The exploit itself runs in ExploitService so the
 * process sits at the foreground-service oom_score_adj the spray is tuned for —
 * a visible activity would force adj=0 and every attempt would miss.
 */
public final class MainActivity extends Activity implements Runner.Log {

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
    private TextView status;
    private TextView console;
    private ScrollView scroller;
    private Button netButton;
    private volatile boolean poller;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        setContentView(buildUi());
        append("GhostLock Runner · Echo Show 15 (hoya)");
        append("payload: " + Payload.dir(this) + "/" + Payload.NAME);
        refreshStatus();
        if (getIntent() != null && getIntent().getBooleanExtra("run", false)) {
            startRunAndLeave();
        }
        startPoller();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshStatus();
        startPoller();
    }

    // ---------------------------------------------------------------- ui

    private View buildUi() {
        int pad = dp(28);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(BG);
        root.setPadding(pad, pad, pad, pad);

        TextView title = new TextView(this);
        title.setText("GhostLock Runner");
        title.setTextSize(26);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setTextColor(TEXT);

        TextView sub = new TextView(this);
        sub.setText("hoya · API " + android.os.Build.VERSION.SDK_INT + " · " + android.os.Build.ID);
        sub.setTextColor(DIM);
        sub.setTextSize(13);
        sub.setPadding(0, 0, 0, dp(12));

        status = new TextView(this);
        status.setTextSize(18);
        status.setTypeface(Typeface.DEFAULT_BOLD);
        status.setTextColor(WARN);
        status.setPadding(dp(16), dp(12), dp(16), dp(12));
        status.setBackground(box(PANEL, BORDER));

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
        root.addView(sub);
        root.addView(status);
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

    private static GradientDrawable box(int fill, int stroke) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(fill);
        d.setCornerRadius(10);
        d.setStroke(1, stroke);
        return d;
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    // ------------------------------------------------------------- status

    private void setStatus(final String text, final int color) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                status.setText(text);
                status.setTextColor(color);
            }
        });
    }

    @Override
    public void line(final String s) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                appendNow(s);
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
        synchronized (lines) {
            lines.clear();
            console.setText("");
        }
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

    private void refreshStatus() {
        String s = ExploitService.status(this);
        if (s.startsWith("ROOTED")) {
            setStatus(s, OK);
        } else if (s.startsWith("failed") || s.startsWith("start failed")) {
            setStatus(s, BAD);
        } else if (s.startsWith("running")) {
            setStatus(s, WARN);
        } else {
            int pid = Runner.rootDaemonPid(this);
            if (pid > 0 && Runner.channelAnswersAsRoot(this, null)) {
                setStatus("ROOTED · ghost_rootd pid " + pid, OK);
            } else {
                setStatus("not rooted — tap Get root", BAD);
            }
        }
        updateNetButton();
        // Tail of the payload log so the screen isn't empty.
        String tail = Runner.lastLogLine(this);
        if (tail.length() > 0 && !s.startsWith("ROOTED")) {
            append("  … " + tail);
        }
    }

    private void startPoller() {
        if (poller) {
            return;
        }
        poller = true;
        new Thread(new Runnable() {
            @Override
            public void run() {
                while (true) {
                    try {
                        Thread.sleep(3000);
                    } catch (InterruptedException e) {
                        return;
                    }
                    if (!isFinishing() && !isDestroyed()) {
                        refreshStatus();
                    }
                }
            }
        }, "ghost-poll").start();
    }

    private void confirmRun() {
        if (Runner.rootDaemonPid(this) > 0) {
            new AlertDialog.Builder(this)
                    .setTitle("Re-run the exploit?")
                    .setMessage("A root daemon already exists. Each run leaves parked kernel "
                            + "threads behind; repeated runs without a reboot can destabilise "
                            + "the device.\n\nReboot first if this device has run the exploit "
                            + "many times.")
                    .setPositiveButton("Run anyway", (d, w) -> startRunAndLeave())
                    .setNegativeButton("Cancel", null)
                    .show();
        } else {
            startRunAndLeave();
        }
    }

    /**
     * Hand the run to the foreground service and get out of the way: a visible
     * activity pins the process at oom_score_adj=0 and the exploit misses.
     */
    private void startRunAndLeave() {
        append("starting exploit service — leaving this screen on purpose");
        append("(a visible app forces oom_score_adj=0; the exploit needs ~200)");
        Intent i = new Intent(this, ExploitService.class)
                .setAction(ExploitService.ACTION_RUN);
        if (android.os.Build.VERSION.SDK_INT >= 26) {
            startForegroundService(i);
        } else {
            startService(i);
        }
        // Move to background so AMS drops us to the service adj class.
        moveTaskToBack(true);
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
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        updateNetButton();
                        refreshStatus();
                    }
                });
            }
        }).start();
    }

    private void updateNetButton() {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                netButton.setText(ExploitService.netShellWanted()
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
                .setMessage("Script goes to files/rootcmd, output comes back from files/rootout. "
                        + "For pm/dumpsys prefix with: runcon u:r:shell:s0")
                .setView(input)
                .setPositiveButton("Run", (d, w) -> shell(input.getText().toString()))
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void shell(final String script) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                append("$ " + script.replace("\n", "\n  "));
                try {
                    String out = Runner.rootCmd(MainActivity.this, script, 30_000,
                            MainActivity.this);
                    for (String l : out.split("\n")) {
                        append("  " + l);
                    }
                } catch (Exception e) {
                    append("shell error: " + e);
                }
            }
        }, "ghost-shell").start();
    }
}
