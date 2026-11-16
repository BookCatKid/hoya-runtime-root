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

/**
 * TCP -> root mailbox proxy. One client at a time; each line the client sends is
 * dropped into files/rootcmd and the matching rootout block is returned.
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
        try {
            s.setSoTimeout(0);
            BufferedReader in = new BufferedReader(new InputStreamReader(
                    s.getInputStream(), Charset.forName("UTF-8")));
            OutputStreamWriter out = new OutputStreamWriter(s.getOutputStream(),
                    Charset.forName("UTF-8"));
            out.write("ghost root shell — one command per line, 'exit' to quit\n");
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
                String result;
                try {
                    result = Runner.rootCmd(ctx, line, 30_000, null);
                } catch (Exception e) {
                    result = "error: " + e + "\n";
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
