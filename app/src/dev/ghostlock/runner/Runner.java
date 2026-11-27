package dev.ghostlock.runner;

import android.content.Context;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.Charset;

/** Drives the ghostpw payload and the root command mailbox it leaves behind. */
final class Runner {

    interface Log {
        void line(String s);
    }

    /** Env for the slide_dep root path. The ADB-over-TCP flags are added from
     *  the user's explicit choice (see adbMode); they are NOT hardcoded here,
     *  because enabling network ADB is a trust-boundary decision. */
    private static final String[][] EXPLOIT_ENV = {
            {"GHOST_STAGE", "slide_dep"},
            {"GHOST_WAITER_CORE", "2"},
            {"GHOST_PARK", "1"},
            {"GHOST_FOPS_TRUE", "1"},
    };

    /** ADB-over-TCP choice, persisted so the service can read it at run time. */
    static final String PREFS = "ghost_prefs";
    static final String KEY_ADB_MODE = "adb_mode";
    static final String ADB_OFF = "off";
    static final String ADB_REBOOT = "reboot"; // service.adb.tcp.port only (until reboot)

    /** Current ADB-over-TCP mode; defaults to "until reboot". */
    static String adbMode(Context ctx) {
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(KEY_ADB_MODE, ADB_REBOOT);
    }

    static void setAdbMode(Context ctx, String mode) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putString(KEY_ADB_MODE, mode).apply();
    }

    static final int EXPLOIT_TIMEOUT_MS = 300_000;
    static final String RUN_LOG = "ghost_run.log";
    /** Max bytes of one mailbox script. ghost_rootd accepts 64 KiB; this
     *  keeps a single command line well inside the network shell's limit. */
    static final int MAX_CMD_BYTES = 4000;

    /** True when the script fits one mailbox write. */
    static boolean fitsMailbox(String script) {
        return script.getBytes(Charset.forName("UTF-8")).length <= MAX_CMD_BYTES;
    }

    /** A command that timed out and may still be running in ghost_rootd. */
    private static boolean outstanding;
    private static long outstandingBase;

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
        // Enable ADB over TCP only per the user's explicit choice. "Until
        // reboot" sets the non-persistent service.adb.tcp.port, which a reboot
        // clears; "Off" passes nothing and keeps the classic kernel-SID nc
        // shell only. There is deliberately no persistent ("at boot") choice:
        // the app would have no way to clear persist.adb.tcp.port from the
        // kernel-SID shell, so an auto-start-on-boot opt-in the app cannot
        // revoke is left out of this change.
        if (ADB_REBOOT.equals(adbMode(ctx))) {
            pb.environment().put("GHOST_ENABLE_ADB", "1");
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
     * only the final 256 KB so a large log doesn't stall the refresh thread.
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

    static String stripAnsi(String s) {
        return s == null ? "" : s.replaceAll("\\x1b\\[[0-9;]*m", "");
    }

    static boolean channelAnswersAsRoot(Context ctx, long timeoutMs) {
        try {
            String out = rootCmd(ctx, "id", timeoutMs);
            return out.contains("uid=0");
        } catch (Exception e) {
            return false;
        }
    }

    /** True when ghost_rootd has written its ROOTED marker. */
    static boolean rootMarkerExists(Context ctx) {
        return new File(Payload.dir(ctx), "ROOTED").exists();
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
            // Never touch root state, or record a boot_id, while an exploit run
            // is in progress: the slide stage spoofs /proc/.../boot_id, so a
            // read now could store the fake value and make the next start wrongly
            // wipe a daemon that is actually alive. Also leave it alone while
            // ghost_rootd is still running — a live daemon means the state is
            // current regardless of what boot_id says.
            if (ExploitService.exploitActive() || rootDaemonAlive(dir)) {
                return;
            }
            String cur = readFile(new File("/proc/sys/kernel/random/boot_id"));
            String prev = readFile(bootFile);
            if (cur.length() == 0) {
                return;
            }
            if (prev.length() == 0) {
                // No boot record. If a root marker exists, the record was
                // lost and the marker may be from an earlier boot.
                if (new File(dir, "ROOTED").exists()
                        || new File(dir, "rootd.pid").exists()) {
                    for (String n : new String[]{"rootcmd", "rootout",
                            "rootd.pid", "ROOTED", "exploit_status.txt"}) {
                        new File(dir, n).delete();
                    }
                }
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

    /** True when rootd.pid names a process that is still alive (/proc/<pid>). */
    private static boolean rootDaemonAlive(File dir) {
        String pid = readFile(new File(dir, "rootd.pid"));
        if (pid.length() == 0) {
            return false;
        }
        try {
            int p = Integer.parseInt(pid.trim());
            return p > 0 && new File("/proc/" + p).exists();
        } catch (NumberFormatException e) {
            return false;
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

    /** Writes a script to files/rootcmd and waits for ghost_rootd output. */
    static synchronized String rootCmd(Context ctx, String script, long timeoutMs)
            throws IOException, InterruptedException {
        byte[] body = script.getBytes("UTF-8");
        if (body.length > MAX_CMD_BYTES) {
            throw new IOException("command too long (max " + MAX_CMD_BYTES + " bytes)");
        }
        File dir = Payload.dir(ctx);
        File cmd = new File(dir, "rootcmd");
        File out = new File(dir, "rootout");

        // ghost_rootd runs one command at a time and reports completion
        // with [rc=]. A command that timed out may still be running, and
        // its [rc=] would otherwise be read as this command's result.
        if (outstanding) {
            long drainUntil = System.currentTimeMillis() + 5_000;
            while (true) {
                if (out.length() < outstandingBase) {
                    // rootout was truncated: a new daemon started.
                    outstanding = false;
                    break;
                }
                if (readAppended(out, outstandingBase).indexOf("[rc=") >= 0) {
                    outstanding = false;
                    break;
                }
                if (System.currentTimeMillis() >= drainUntil) {
                    throw new IOException("previous root command is still running");
                }
                Thread.sleep(300);
            }
        }

        final long baseLen = out.exists() ? out.length() : 0L;

        // ghost_rootd renames rootcmd away before reading it, so a rename
        // into place is atomic on both sides: the daemon never sees a
        // half-written script and never truncates one the app is writing.
        File tmp = new File(dir, "rootcmd.tmp");
        FileOutputStream os = new FileOutputStream(tmp, false);
        try {
            os.write(body);
            if (!script.endsWith("\n")) {
                os.write('\n');
            }
            os.flush();
        } finally {
            os.close();
        }
        if (!tmp.renameTo(cmd)) {
            // Leave the temp file for the next attempt rather than
            // executing a script the daemon cannot see.
            throw new IOException("cannot hand off rootcmd");
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
        outstanding = true;
        outstandingBase = baseLen;
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
