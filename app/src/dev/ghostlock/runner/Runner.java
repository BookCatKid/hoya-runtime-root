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

    /** Env for the slide_dep root path. */
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

    /** Set oom_score_adj to the value the spray counts are calibrated for. */
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

    /** Last non-empty line of the run log, ANSI escapes stripped. */
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

    /** Size of ghost_run.log, or -1 when absent. */
    static long runLogLength(Context ctx) {
        File log = runLog(ctx);
        return log.exists() ? log.length() : -1L;
    }

    /**
     * Last maxLines lines of the run log, ANSI stripped, oldest first. Reads
     * only the final 256 KB so an 800 KB log doesn't stall the UI thread.
     */
    static String runLogTail(Context ctx, int maxLines) {
        File log = runLog(ctx);
        if (!log.exists()) {
            return "";
        }
        try {
            RandomAccessFile f = new RandomAccessFile(log, "r");
            try {
                long len = f.length();
                long start = Math.max(0, len - 262144);
                f.seek(start);
                byte[] raw = new byte[(int) (len - start)];
                f.readFully(raw);
                String[] all = new String(raw, Charset.forName("UTF-8")).split("\n");
                int from = Math.max(0, all.length - maxLines);
                StringBuilder sb = new StringBuilder();
                for (int i = from; i < all.length; i++) {
                    sb.append(stripAnsi(all[i])).append('\n');
                }
                return sb.toString();
            } finally {
                f.close();
            }
        } catch (Exception e) {
            return "";
        }
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

    /** Wipe root marker files when boot_id changes. */
    static void wipeStaleStateIfNewBoot(Context ctx) {
        try {
            File dir = Payload.dir(ctx);
            File bootFile = new File(dir, "boot_id.txt");
            String cur = readFile(new File("/proc/sys/kernel/random/boot_id"));
            String prev = readFile(bootFile);
            if (cur.length() == 0) {
                return;
            }
            if (prev.length() == 0) {
                // First run ever. Nothing to compare, just record.
                writeFile(bootFile, cur);
                return;
            }
            if (cur.equals(prev)) {
                return;
            }
            // Boot changed: everything root-related is genuinely stale:
            // a daemon from the previous boot cannot survive a reboot.
            for (String n : new String[]{"rootcmd", "rootout", "rootd.pid",
                    "ROOTED", "exploit_status.txt"}) {
                new File(dir, n).delete();
            }
            writeFile(bootFile, cur);
        } catch (Exception ignored) {
        }
    }

    /** Read a file via stream; /proc entries report length 0. */
    private static String readFile(File f) {
        try {
            java.io.FileInputStream in = new java.io.FileInputStream(f);
            try {
                java.io.ByteArrayOutputStream buf = new java.io.ByteArrayOutputStream();
                byte[] chunk = new byte[512];
                int n;
                while ((n = in.read(chunk)) > 0) {
                    buf.write(chunk, 0, n);
                }
                return new String(buf.toByteArray(), "UTF-8").trim();
            } finally {
                in.close();
            }
        } catch (Exception e) {
            return "";
        }
    }

    private static void writeFile(File f, String s) {
        try {
            java.io.FileOutputStream o = new java.io.FileOutputStream(f, false);
            o.write(s.getBytes("UTF-8"));
            o.close();
        } catch (Exception ignored) {
        }
    }

    /** True when /proc/<pid>/comm is ghost_rootd. */
    static boolean daemonAlive(int pid) {
        if (pid <= 0) {
            return false;
        }
        try {
            byte[] b = new byte[32];
            java.io.FileInputStream f = new java.io.FileInputStream(
                    "/proc/" + pid + "/comm");
            int n = f.read(b);
            f.close();
            return n > 0 && new String(b, 0, n, "UTF-8").trim()
                    .equals("ghost_rootd");
        } catch (Exception e) {
            return false;
        }
    }

    /** True when the mailbox answers as uid 0. */
    static boolean channelAnswersAsRoot(Context ctx, Log log) {
        return channelAnswersAsRoot(ctx, log, 8_000);
    }

    static boolean channelAnswersAsRoot(Context ctx, Log log, long timeoutMs) {
        try {
            String out = rootCmd(ctx, "id", timeoutMs, null);
            return out.contains("uid=0");
        } catch (Exception e) {
            return false;
        }
    }

    /** Writes a script to files/rootcmd and waits for ghost_rootd output. */
    static String rootCmd(Context ctx, String script, long timeoutMs, Log log)
            throws IOException, InterruptedException {
        File dir = Payload.dir(ctx);
        File cmd = new File(dir, "rootcmd");
        File out = new File(dir, "rootout");

        final long baseLen = out.exists() ? out.length() : 0L;

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
            sb.append(readAppended(out, baseLen));
            if (sb.indexOf("[rc=") >= 0) {
                return sb.toString();
            }
        }
        return sb.toString();
    }

    /** Contents of f appended after offset `from`, or "" if unreadable. */
    private static String readAppended(File f, long from) {
        try {
            if (!f.exists()) {
                return "";
            }
            RandomAccessFile r = new RandomAccessFile(f, "r");
            try {
                long len = r.length();
                if (len <= from) {
                    return "";
                }
                r.seek(from);
                byte[] raw = new byte[(int) (len - from)];
                r.readFully(raw);
                return new String(raw, Charset.forName("UTF-8"));
            } finally {
                r.close();
            }
        } catch (Exception e) {
            return "";
        }
    }
}
