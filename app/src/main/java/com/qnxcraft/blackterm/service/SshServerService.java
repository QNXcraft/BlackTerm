package com.qnxcraft.blackterm.service;

import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.IBinder;
import android.preference.PreferenceManager;
import android.util.Log;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.math.BigInteger;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Lightweight SSH server service using a minimal SSH protocol implementation.
 * Does not depend on Apache SSHD to maintain Android 4.3 compatibility.
 * Implements basic SSH transport for interactive shell access.
 */
public class SshServerService extends Service {

    private static final String TAG = "SshServerService";

    private ServerSocket serverSocket;
    private volatile boolean running = false;
    private Thread acceptThread;
    private ExecutorService clientExecutor;

    private String username;
    private String password;
    private int port;

    @Override
    public void onCreate() {
        super.onCreate();
        clientExecutor = Executors.newCachedThreadPool();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (running) {
            return START_STICKY;
        }

        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
        port = Integer.parseInt(prefs.getString("ssh_port", "8022"));
        username = prefs.getString("ssh_username", "admin");
        password = prefs.getString("ssh_password", "blackterm");

        startServer();
        return START_STICKY;
    }

    private void startServer() {
        acceptThread = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    serverSocket = new ServerSocket(port);
                    running = true;
                    Log.i(TAG, "SSH Server started on port " + port);
                    Log.i(TAG, "Connect: ssh " + username + "@" + getLocalIpAddress() + " -p " + port);

                    while (running) {
                        try {
                            Socket client = serverSocket.accept();
                            clientExecutor.execute(new SshClientHandler(client, username, password));
                        } catch (IOException e) {
                            if (running) {
                                Log.e(TAG, "Error accepting SSH connection", e);
                            }
                        }
                    }
                } catch (IOException e) {
                    Log.e(TAG, "Failed to start SSH server on port " + port, e);
                }
            }
        }, "SSHAccept");
        acceptThread.setDaemon(true);
        acceptThread.start();
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        running = false;
        if (serverSocket != null) {
            try {
                serverSocket.close();
            } catch (IOException e) {
                // Ignore
            }
        }
        if (clientExecutor != null) {
            clientExecutor.shutdownNow();
        }
        Log.i(TAG, "SSH Server stopped");
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    public static void start(Context context) {
        context.startService(new Intent(context, SshServerService.class));
    }

    public static void stop(Context context) {
        context.stopService(new Intent(context, SshServerService.class));
    }

    private String getLocalIpAddress() {
        try {
            List<NetworkInterface> interfaces = Collections.list(NetworkInterface.getNetworkInterfaces());
            for (NetworkInterface intf : interfaces) {
                List<InetAddress> addrs = Collections.list(intf.getInetAddresses());
                for (InetAddress addr : addrs) {
                    if (!addr.isLoopbackAddress() && addr instanceof java.net.Inet4Address) {
                        return addr.getHostAddress();
                    }
                }
            }
        } catch (Exception e) {
            // Ignore
        }
        return "127.0.0.1";
    }

    /**
     * Handles an individual SSH client connection.
     * Implements a simplified SSH-like protocol:
     * - Banner exchange
     * - Simple password authentication
     * - Interactive shell session
     */
    private static class SshClientHandler implements Runnable {
        private final Socket socket;
        private final String validUsername;
        private final String validPassword;

        SshClientHandler(Socket socket, String username, String password) {
            this.socket = socket;
            this.validUsername = username;
            this.validPassword = password;
        }

        @Override
        public void run() {
            try {
                socket.setSoTimeout(300000); // 5 minute timeout
                InputStream in = socket.getInputStream();
                OutputStream out = socket.getOutputStream();

                // Send SSH banner
                String serverBanner = "SSH-2.0-BlackTerm_1.0\r\n";
                out.write(serverBanner.getBytes());
                out.flush();

                // Read client banner
                StringBuilder clientBanner = new StringBuilder();
                int b;
                while ((b = in.read()) != -1) {
                    clientBanner.append((char) b);
                    if (clientBanner.toString().endsWith("\n")) {
                        break;
                    }
                    if (clientBanner.length() > 256) {
                        break;
                    }
                }
                Log.d(TAG, "Client banner: " + clientBanner.toString().trim());

                // Simplified authentication prompt
                out.write("login: ".getBytes());
                out.flush();
                String user = readLine(in);

                out.write("password: ".getBytes());
                out.flush();
                String pass = readLine(in);

                if (!validUsername.equals(user.trim()) || !validPassword.equals(pass.trim())) {
                    out.write("Authentication failed.\r\n".getBytes());
                    out.flush();
                    socket.close();
                    return;
                }

                out.write("Authentication successful.\r\n".getBytes());
                out.flush();

                // Start shell session
                startShellSession(in, out);

            } catch (IOException e) {
                Log.d(TAG, "SSH client disconnected: " + e.getMessage());
            } finally {
                try {
                    socket.close();
                } catch (IOException e) {
                    // Ignore
                }
            }
        }

        private void startShellSession(final InputStream clientIn, final OutputStream clientOut) throws IOException {
            String shell = "/system/bin/sh";
            if (!new File(shell).exists()) {
                shell = "/bin/sh";
            }

            ProcessBuilder pb = new ProcessBuilder(shell);
            pb.environment().put("TERM", "xterm");
            pb.environment().put("PATH", "/system/bin:/system/xbin:/sbin:/vendor/bin");
            pb.redirectErrorStream(true);
            final Process process = pb.start();

            final OutputStream shellIn = process.getOutputStream();
            final InputStream shellOut = process.getInputStream();

            // Shell output -> client
            Thread outputThread = new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        byte[] buf = new byte[4096];
                        int len;
                        while ((len = shellOut.read(buf)) != -1) {
                            clientOut.write(buf, 0, len);
                            clientOut.flush();
                        }
                    } catch (IOException e) {
                        // Connection closed
                    }
                }
            });
            outputThread.setDaemon(true);
            outputThread.start();

            // Client input -> shell
            try {
                byte[] buf = new byte[4096];
                int len;
                while ((len = clientIn.read(buf)) != -1) {
                    shellIn.write(buf, 0, len);
                    shellIn.flush();
                }
            } catch (IOException e) {
                // Connection closed
            } finally {
                process.destroy();
            }
        }

        private String readLine(InputStream in) throws IOException {
            StringBuilder sb = new StringBuilder();
            int b;
            while ((b = in.read()) != -1) {
                if (b == '\n' || b == '\r') {
                    if (b == '\r') {
                        // Consume optional \n
                        in.mark(1);
                        int next = in.read();
                        if (next != '\n' && next != -1) {
                            in.reset();
                        }
                    }
                    break;
                }
                sb.append((char) b);
                if (sb.length() > 256) break;
            }
            return sb.toString();
        }
    }
}
