package dev.ghostlock.runner;

import android.content.Context;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.Charset;
import java.util.Enumeration;

/** TCP shell: app-context commands until ghost_rootd answers, then uid 0. */
final class RootServer {

    static final int PORT = 4444;
    private static final int MAX_CLIENTS = 8;
    private static volatile boolean running;
    private static volatile boolean serving;
    private static ServerSocket server;
    private static Thread thread;
    private static final java.util.Set<Socket> clients =
            java.util.Collections.synchronizedSet(new java.util.HashSet<Socket>());

    private RootServer() {
    }

    static synchronized boolean start(final Context ctx, final Runner.Log log) {
        // running is not enough: an accept error leaves the socket open
        // with no accept loop, and returning early would never rebind.
        if (running && serving && server != null && !server.isClosed()) {
            return true;
        }
        closeServer();
        ServerSocket bound = null;
        try {
            bound = new ServerSocket();
            bound.setReuseAddress(true);
            bound.bind(new java.net.InetSocketAddress(PORT));
        } catch (IOException e) {
            if (bound != null) {
                try {
                    bound.close();
                } catch (IOException ignored) {
                }
            }
            safeLog(log, "network shell failed: " + e);
            return false;
        }
        final ServerSocket srv = bound;
        server = srv;
        running = true;
        serving = true;
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    safeLog(log, "network shell listening on port " + PORT);
                    for (String ip : lanIps()) {
                        safeLog(log, "  connect: nc " + ip + " " + PORT);
                    }
                    safeLog(log, "WARNING: unauthenticated shell on your LAN");
                    while (!srv.isClosed()) {
                        final Socket s;
                        try {
                            s = srv.accept();
                        } catch (IOException e) {
                            if (!srv.isClosed()) {
                                safeLog(log, "accept error: " + e);
                            }
                            break;
                        }
                        synchronized (clients) {
                            if (srv.isClosed() || clients.size() >= MAX_CLIENTS) {
                                try {
                                    s.close();
                                } catch (IOException ignored) {
                                }
                                continue;
                            }
                            clients.add(s);
                        }
                        new Thread(new Runnable() {
                            @Override
                            public void run() {
                                try {
                                    if (!srv.isClosed()) {
                                        handle(ctx, s, log);
                                    }
                                } finally {
                                    clients.remove(s);
                                    try {
                                        s.close();
                                    } catch (IOException ignored) {
                                    }
                                }
                            }
                        }, "root-client").start();
                    }
                } finally {
                    try {
                        srv.close();
                    } catch (IOException ignored) {
                    }
                    // Only the current generation may clear the state.
                    if (server == srv) {
                        serving = false;
                        running = false;
                        server = null;
                    }
                }
            }
        }, "root-server");
        t.setDaemon(true);
        thread = t;
        t.start();
        return true;
    }

    private static void closeServer() {
        ServerSocket srv = server;
        server = null;
        running = false;
        serving = false;
        thread = null;
        if (srv != null) {
            try {
                srv.close();
            } catch (IOException ignored) {
            }
        }
        synchronized (clients) {
            for (Socket s : clients) {
                try {
                    s.close();
                } catch (IOException ignored) {
                }
            }
            clients.clear();
        }
    }

    private static void safeLog(Runner.Log log, String s) {
        try {
            log.line(s);
        } catch (Throwable ignored) {
        }
    }

    /** True while a socket is bound and the accept loop is running. */
    static boolean listening() {
        ServerSocket srv = server;
        return running && serving && srv != null && !srv.isClosed();
    }

    static synchronized void stop() {
        Thread t = thread;
        closeServer();
        // The accept loop wakes on the closed socket. It never takes the
        // class lock, so this join cannot deadlock.
        if (t != null && t != Thread.currentThread()) {
            try {
                t.join(500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private static void handle(Context ctx, Socket s, Runner.Log log) {
        String peer = s.getInetAddress().getHostAddress();
        safeLog(log, "client connected: " + peer);
        boolean root = Runner.channelAnswersAsRoot(ctx, 3_000);
        try {
            s.setSoTimeout(0);
            BufferedReader in = new BufferedReader(new InputStreamReader(
                    s.getInputStream(), Charset.forName("UTF-8")));
            OutputStreamWriter out = new OutputStreamWriter(s.getOutputStream(),
                    Charset.forName("UTF-8"));
            out.write("ghost shell: " + (root
                    ? "ROOT (via ghost_rootd, uid 0)"
                    : "app context (no root yet, runs as this app's uid)")
                    + "\none command per line, 'exit' to quit\n"
                    + "cwd: " + Payload.dir(ctx).getAbsolutePath() + "\n"
                    + "WARNING: unauthenticated. Anyone on the LAN can use this\n");
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
                if (!Runner.fitsMailbox(line)) {
                    out.write("command too long (max " + Runner.MAX_CMD_BYTES
                            + " bytes)\n---\n");
                    out.flush();
                    continue;
                }
                if (!root) {
                    // The user may have just rooted from here.
                    root = Runner.channelAnswersAsRoot(ctx, 2_000);
                    if (root) {
                        out.write("[ghost_rootd appeared. Switching to uid 0]\n");
                        out.flush();
                    }
                }
                String result;
                if (root) {
                    try {
                        result = Runner.rootCmd(ctx, line, 60_000);
                    } catch (Exception e) {
                        String m = e.getMessage();
                        result = "error: " + (m != null ? m : e.toString()) + "\n";
                    }
                    if (result.trim().length() == 0) {
                        // The command may still be running as root. Never
                        // repeat it in app context: a destructive line
                        // would execute twice.
                        result = "(no output yet; the root command may still be running)\n";
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
            safeLog(log, "client disconnected: " + peer);
        }
    }

    /** Runs one command as this app's UID via /system/bin/sh. */
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
                p.destroyForcibly();
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
