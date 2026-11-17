package dev.ghostlock.runner;

import android.content.Context;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.Charset;
import java.util.Enumeration;

/**
 * TCP shell for debugging. It is NOT a root-only channel:
 *
 *  - when ghost_rootd is alive, every line goes through the root mailbox
 *    (files/rootcmd -> files/rootout) and runs as uid 0;
 *  - otherwise every line is executed by /system/bin/sh in this app's own
 *    UID — the same app context the payload itself runs in — so the exploit
 *    can be driven (and debugged) before anything is rooted, like the diag
 *    c2 agent.
 *
 * It also picks up root mid-session: if the daemon starts answering while a
 * client is connected, the next command goes through the mailbox.
 * Unauthenticated: only start it when the user asks for it.
 */
final class RootServer {

    private static final int PORT = 4444;
    private static volatile boolean running;
    private static ServerSocket server;

    private RootServer() {
    }

    static synchronized boolean isRunning() {
        return running;
    }

    static synchronized void start(final Context ctx, final Runner.Log log) {
        if (running) {
            return;
        }
        running = true;
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    server = new ServerSocket(PORT);
                    log.line("network shell listening on port " + PORT);
                    for (String ip : lanIps()) {
                        log.line("  connect:  nc " + ip + " " + PORT);
                    }
                    log.line("WARNING: unauthenticated root shell on your LAN");
                } catch (IOException e) {
                    log.line("network shell failed: " + e);
                    running = false;
                    return;
                }
                while (running) {
                    try {
                        final Socket s = server.accept();
                        new Thread(new Runnable() {
                            @Override
                            public void run() {
                                handle(ctx, s, log);
                            }
                        }, "root-client").start();
                    } catch (IOException e) {
                        if (running) {
                            log.line("accept error: " + e);
                        }
                    }
                }
            }
        }, "root-server");
        t.setDaemon(true);
        t.start();
    }

    static synchronized void stop() {
        running = false;
        try {
            if (server != null) {
                server.close();
            }
        } catch (IOException ignored) {
        }
    }

    private static void handle(Context ctx, Socket s, Runner.Log log) {
        String peer = s.getInetAddress().getHostAddress();
        log.line("client connected: " + peer);
        boolean root = Runner.channelAnswersAsRoot(ctx, null, 3_000);
        try {
            s.setSoTimeout(0);
            BufferedReader in = new BufferedReader(new InputStreamReader(
                    s.getInputStream(), Charset.forName("UTF-8")));
            OutputStreamWriter out = new OutputStreamWriter(s.getOutputStream(),
                    Charset.forName("UTF-8"));
            out.write("ghost shell — " + (root
                    ? "ROOT (via ghost_rootd, uid 0)"
                    : "app context (no root yet, runs as this app's uid)")
                    + "\none command per line, 'exit' to quit\n"
                    + "cwd: " + Payload.dir(ctx).getAbsolutePath() + "\n"
                    + "WARNING: unauthenticated — anyone on the LAN can use this\n");
            out.flush();
            String line;
            while ((line = in.readLine()) != null) {
                line = line.trim();
                if (line.equalsIgnoreCase("exit") || line.equalsIgnoreCase("quit")) {
                    break;
                }
                if (line.length() == 0) {
                    continue;
                }
                if (!root) {
                    // Cheap re-check: the user may have just rooted from here.
                    root = Runner.channelAnswersAsRoot(ctx, null, 2_000);
                    if (root) {
                        out.write("[ghost_rootd appeared — switching to uid 0]\n");
                        out.flush();
                    }
                }
                String result;
                if (root) {
                    try {
                        result = Runner.rootCmd(ctx, line, 60_000, null);
                    } catch (Exception e) {
                        result = "error: " + e + "\n";
                    }
                    if (result.trim().length() == 0) {
                        root = false;
                        result = "(no response — root daemon stopped answering; "
                                + "falling back to app context)\n" + execLocal(ctx, line);
                    }
                } else {
                    result = execLocal(ctx, line);
                }
                out.write(result);
                out.write("---\n");
                out.flush();
            }
        } catch (IOException ignored) {
        } finally {
            try {
                s.close();
            } catch (IOException ignored) {
            }
            log.line("client disconnected: " + peer);
        }
    }

    /**
     * Runs one command as this app's own UID via /system/bin/sh. This is the
     * context the payload runs in, so it can drive ghostpw before root exists.
     */
    private static String execLocal(Context ctx, String cmd) {
        try {
            ProcessBuilder pb = new ProcessBuilder("/system/bin/sh", "-c", cmd);
            pb.directory(Payload.dir(ctx));
            pb.redirectErrorStream(true);
            final Process p = pb.start();
            final java.io.ByteArrayOutputStream buf = new java.io.ByteArrayOutputStream();
            Thread reader = new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        java.io.InputStream is = p.getInputStream();
                        byte[] b = new byte[4096];
                        int n;
                        while ((n = is.read(b)) > 0) {
                            buf.write(b, 0, n);
                        }
                    } catch (IOException ignored) {
                    }
                }
            }, "shell-read");
            reader.setDaemon(true);
            reader.start();
            boolean finished = p.waitFor(30, java.util.concurrent.TimeUnit.SECONDS);
            if (!finished) {
                p.destroy();
                reader.join(1000);
                return new String(buf.toByteArray(), "UTF-8") + "\n(timed out after 30s)\n";
            }
            reader.join(2000);
            return new String(buf.toByteArray(), "UTF-8")
                    + "[rc=" + p.exitValue() + "]\n";
        } catch (Throwable t) {
            return "error: " + t + "\n";
        }
    }

    static java.util.List<String> lanIps() {
        java.util.List<String> ips = new java.util.ArrayList<String>();
        try {
            Enumeration<NetworkInterface> ifs = NetworkInterface.getNetworkInterfaces();
            while (ifs.hasMoreElements()) {
                NetworkInterface ni = ifs.nextElement();
                if (ni.isLoopback() || !ni.isUp()) {
                    continue;
                }
                String name = ni.getName();
                if (name.startsWith("wlan") || name.startsWith("eth") || name.startsWith("ap")) {
                    Enumeration<InetAddress> addrs = ni.getInetAddresses();
                    while (addrs.hasMoreElements()) {
                        InetAddress a = addrs.nextElement();
                        if (a instanceof java.net.Inet4Address) {
                            ips.add(a.getHostAddress());
                        }
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return ips;
    }
}
