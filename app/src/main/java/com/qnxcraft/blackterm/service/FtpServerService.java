package com.qnxcraft.blackterm.service;

import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Environment;
import android.os.IBinder;
import android.preference.PreferenceManager;
import android.util.Log;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.text.SimpleDateFormat;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Lightweight FTP server service.
 * Implements core FTP commands for file transfer on BlackBerry Passport.
 */
public class FtpServerService extends Service {

    private static final String TAG = "FtpServerService";

    private ServerSocket serverSocket;
    private volatile boolean running = false;
    private Thread acceptThread;
    private ExecutorService clientExecutor;

    private String username;
    private String password;
    private int port;
    private String rootDirectory;

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
        port = Integer.parseInt(prefs.getString("ftp_port", "2121"));
        username = prefs.getString("ftp_username", "admin");
        password = prefs.getString("ftp_password", "blackterm");
        rootDirectory = Environment.getExternalStorageDirectory().getAbsolutePath();

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
                    Log.i(TAG, "FTP Server started on port " + port);
                    Log.i(TAG, "Root directory: " + rootDirectory);

                    while (running) {
                        try {
                            Socket client = serverSocket.accept();
                            clientExecutor.execute(new FtpClientHandler(
                                    client, username, password, rootDirectory));
                        } catch (IOException e) {
                            if (running) {
                                Log.e(TAG, "Error accepting FTP connection", e);
                            }
                        }
                    }
                } catch (IOException e) {
                    Log.e(TAG, "Failed to start FTP server on port " + port, e);
                }
            }
        }, "FTPAccept");
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
        Log.i(TAG, "FTP Server stopped");
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    public static void start(Context context) {
        context.startService(new Intent(context, FtpServerService.class));
    }

    public static void stop(Context context) {
        context.stopService(new Intent(context, FtpServerService.class));
    }

    /**
     * Handles an individual FTP client connection.
     * Implements essential FTP commands: USER, PASS, PWD, CWD, LIST, RETR, STOR, etc.
     */
    private static class FtpClientHandler implements Runnable {
        private final Socket controlSocket;
        private final String validUsername;
        private final String validPassword;
        private final String rootDir;

        private PrintWriter controlOut;
        private BufferedReader controlIn;
        private String currentDir;
        private boolean authenticated = false;
        private boolean usernameOk = false;
        private ServerSocket dataServerSocket;
        private String dataHost;
        private int dataPort;
        private boolean passiveMode = false;
        private String renameFrom;

        FtpClientHandler(Socket socket, String username, String password, String rootDir) {
            this.controlSocket = socket;
            this.validUsername = username;
            this.validPassword = password;
            this.rootDir = rootDir;
            this.currentDir = "/";
        }

        @Override
        public void run() {
            try {
                controlSocket.setSoTimeout(300000);
                controlIn = new BufferedReader(new InputStreamReader(controlSocket.getInputStream()));
                controlOut = new PrintWriter(controlSocket.getOutputStream(), true);

                sendResponse(220, "BlackTerm FTP Server Ready");

                String line;
                while ((line = controlIn.readLine()) != null) {
                    handleCommand(line.trim());
                }
            } catch (IOException e) {
                Log.d(TAG, "FTP client disconnected: " + e.getMessage());
            } finally {
                cleanup();
            }
        }

        private void handleCommand(String line) throws IOException {
            if (line.isEmpty()) return;

            String command;
            String args = "";
            int spaceIdx = line.indexOf(' ');
            if (spaceIdx > 0) {
                command = line.substring(0, spaceIdx).toUpperCase();
                args = line.substring(spaceIdx + 1);
            } else {
                command = line.toUpperCase();
            }

            switch (command) {
                case "USER":
                    handleUser(args);
                    break;
                case "PASS":
                    handlePass(args);
                    break;
                case "SYST":
                    sendResponse(215, "UNIX Type: L8");
                    break;
                case "FEAT":
                    controlOut.println("211-Features:");
                    controlOut.println(" PASV");
                    controlOut.println(" SIZE");
                    controlOut.println(" UTF8");
                    controlOut.println("211 End");
                    break;
                case "PWD":
                case "XPWD":
                    if (requireAuth()) sendResponse(257, "\"" + currentDir + "\"");
                    break;
                case "CWD":
                case "XCWD":
                    if (requireAuth()) handleCwd(args);
                    break;
                case "CDUP":
                case "XCUP":
                    if (requireAuth()) handleCdup();
                    break;
                case "TYPE":
                    sendResponse(200, "Type set to " + args);
                    break;
                case "PASV":
                    if (requireAuth()) handlePasv();
                    break;
                case "PORT":
                    if (requireAuth()) handlePort(args);
                    break;
                case "LIST":
                    if (requireAuth()) handleList(args);
                    break;
                case "NLST":
                    if (requireAuth()) handleNlst(args);
                    break;
                case "RETR":
                    if (requireAuth()) handleRetr(args);
                    break;
                case "STOR":
                    if (requireAuth()) handleStor(args);
                    break;
                case "DELE":
                    if (requireAuth()) handleDele(args);
                    break;
                case "MKD":
                case "XMKD":
                    if (requireAuth()) handleMkd(args);
                    break;
                case "RMD":
                case "XRMD":
                    if (requireAuth()) handleRmd(args);
                    break;
                case "RNFR":
                    if (requireAuth()) handleRnfr(args);
                    break;
                case "RNTO":
                    if (requireAuth()) handleRnto(args);
                    break;
                case "SIZE":
                    if (requireAuth()) handleSize(args);
                    break;
                case "NOOP":
                    sendResponse(200, "OK");
                    break;
                case "QUIT":
                    sendResponse(221, "Goodbye");
                    controlSocket.close();
                    break;
                default:
                    sendResponse(502, "Command not implemented");
                    break;
            }
        }

        private void handleUser(String user) {
            if (validUsername.equals(user)) {
                usernameOk = true;
                sendResponse(331, "Password required");
            } else {
                sendResponse(530, "Invalid username");
            }
        }

        private void handlePass(String pass) {
            if (usernameOk && validPassword.equals(pass)) {
                authenticated = true;
                sendResponse(230, "Login successful");
            } else {
                sendResponse(530, "Authentication failed");
            }
        }

        private boolean requireAuth() {
            if (!authenticated) {
                sendResponse(530, "Please login first");
                return false;
            }
            return true;
        }

        private void handleCwd(String path) {
            File target = resolveFile(path);
            if (target.isDirectory()) {
                currentDir = getRelativePath(target);
                sendResponse(250, "Directory changed to " + currentDir);
            } else {
                sendResponse(550, "Directory not found");
            }
        }

        private void handleCdup() {
            if (!"/".equals(currentDir)) {
                File parent = new File(rootDir + currentDir).getParentFile();
                if (parent != null) {
                    currentDir = getRelativePath(parent);
                }
            }
            sendResponse(250, "Directory changed to " + currentDir);
        }

        private void handlePasv() throws IOException {
            if (dataServerSocket != null) {
                try { dataServerSocket.close(); } catch (IOException e) {}
            }
            dataServerSocket = new ServerSocket(0);
            int pasvPort = dataServerSocket.getLocalPort();
            String ip = controlSocket.getLocalAddress().getHostAddress().replace('.', ',');
            int p1 = pasvPort / 256;
            int p2 = pasvPort % 256;
            passiveMode = true;
            sendResponse(227, "Entering Passive Mode (" + ip + "," + p1 + "," + p2 + ")");
        }

        private void handlePort(String args) {
            String[] parts = args.split(",");
            if (parts.length == 6) {
                dataHost = parts[0] + "." + parts[1] + "." + parts[2] + "." + parts[3];
                dataPort = Integer.parseInt(parts[4]) * 256 + Integer.parseInt(parts[5]);
                passiveMode = false;
                sendResponse(200, "PORT command successful");
            } else {
                sendResponse(501, "Invalid PORT command");
            }
        }

        private Socket getDataConnection() throws IOException {
            if (passiveMode && dataServerSocket != null) {
                dataServerSocket.setSoTimeout(30000);
                Socket s = dataServerSocket.accept();
                dataServerSocket.close();
                dataServerSocket = null;
                return s;
            } else if (dataHost != null) {
                return new Socket(dataHost, dataPort);
            }
            throw new IOException("No data connection available");
        }

        private void handleList(String path) throws IOException {
            File dir = path.isEmpty() ? new File(rootDir + currentDir) : resolveFile(path);
            if (!dir.isDirectory()) {
                sendResponse(550, "Not a directory");
                return;
            }

            sendResponse(150, "Opening data connection for LIST");
            Socket dataSocket = getDataConnection();
            PrintWriter dataOut = new PrintWriter(dataSocket.getOutputStream(), true);

            File[] files = dir.listFiles();
            if (files != null) {
                SimpleDateFormat sdf = new SimpleDateFormat("MMM dd HH:mm", Locale.US);
                for (File f : files) {
                    String perms = f.isDirectory() ? "drwxr-xr-x" : "-rw-r--r--";
                    String size = String.valueOf(f.length());
                    String date = sdf.format(new Date(f.lastModified()));
                    dataOut.println(String.format("%s 1 ftp ftp %8s %s %s",
                            perms, size, date, f.getName()));
                }
            }

            dataOut.close();
            dataSocket.close();
            sendResponse(226, "Transfer complete");
        }

        private void handleNlst(String path) throws IOException {
            File dir = path.isEmpty() ? new File(rootDir + currentDir) : resolveFile(path);
            if (!dir.isDirectory()) {
                sendResponse(550, "Not a directory");
                return;
            }

            sendResponse(150, "Opening data connection");
            Socket dataSocket = getDataConnection();
            PrintWriter dataOut = new PrintWriter(dataSocket.getOutputStream(), true);

            File[] files = dir.listFiles();
            if (files != null) {
                for (File f : files) {
                    dataOut.println(f.getName());
                }
            }

            dataOut.close();
            dataSocket.close();
            sendResponse(226, "Transfer complete");
        }

        private void handleRetr(String filename) throws IOException {
            File file = resolveFile(filename);
            if (!file.isFile() || !file.canRead()) {
                sendResponse(550, "File not found or not readable");
                return;
            }

            sendResponse(150, "Opening data connection for " + filename);
            Socket dataSocket = getDataConnection();
            OutputStream dataOut = dataSocket.getOutputStream();

            FileInputStream fis = new FileInputStream(file);
            byte[] buffer = new byte[8192];
            int len;
            while ((len = fis.read(buffer)) != -1) {
                dataOut.write(buffer, 0, len);
            }
            fis.close();

            dataOut.close();
            dataSocket.close();
            sendResponse(226, "Transfer complete");
        }

        private void handleStor(String filename) throws IOException {
            File file = resolveFile(filename);

            sendResponse(150, "Opening data connection for " + filename);
            Socket dataSocket = getDataConnection();
            InputStream dataIn = dataSocket.getInputStream();

            FileOutputStream fos = new FileOutputStream(file);
            byte[] buffer = new byte[8192];
            int len;
            while ((len = dataIn.read(buffer)) != -1) {
                fos.write(buffer, 0, len);
            }
            fos.close();

            dataIn.close();
            dataSocket.close();
            sendResponse(226, "Transfer complete");
        }

        private void handleDele(String filename) {
            File file = resolveFile(filename);
            if (file.isFile() && file.delete()) {
                sendResponse(250, "File deleted");
            } else {
                sendResponse(550, "Delete failed");
            }
        }

        private void handleMkd(String dirname) {
            File dir = resolveFile(dirname);
            if (dir.mkdir()) {
                sendResponse(257, "\"" + getRelativePath(dir) + "\" created");
            } else {
                sendResponse(550, "Failed to create directory");
            }
        }

        private void handleRmd(String dirname) {
            File dir = resolveFile(dirname);
            if (dir.isDirectory() && dir.delete()) {
                sendResponse(250, "Directory removed");
            } else {
                sendResponse(550, "Failed to remove directory");
            }
        }

        private void handleRnfr(String filename) {
            File file = resolveFile(filename);
            if (file.exists()) {
                renameFrom = file.getAbsolutePath();
                sendResponse(350, "Ready for RNTO");
            } else {
                sendResponse(550, "File not found");
            }
        }

        private void handleRnto(String filename) {
            if (renameFrom == null) {
                sendResponse(503, "RNFR required first");
                return;
            }
            File from = new File(renameFrom);
            File to = resolveFile(filename);
            if (from.renameTo(to)) {
                sendResponse(250, "Rename successful");
            } else {
                sendResponse(550, "Rename failed");
            }
            renameFrom = null;
        }

        private void handleSize(String filename) {
            File file = resolveFile(filename);
            if (file.isFile()) {
                sendResponse(213, String.valueOf(file.length()));
            } else {
                sendResponse(550, "File not found");
            }
        }

        private File resolveFile(String path) {
            if (path.startsWith("/")) {
                // Ensure the resolved path stays within rootDir
                File resolved = new File(rootDir, path).getAbsoluteFile();
                if (!resolved.getAbsolutePath().startsWith(rootDir)) {
                    return new File(rootDir);
                }
                return resolved;
            }
            File resolved = new File(rootDir + currentDir, path).getAbsoluteFile();
            if (!resolved.getAbsolutePath().startsWith(rootDir)) {
                return new File(rootDir);
            }
            return resolved;
        }

        private String getRelativePath(File file) {
            String abs = file.getAbsolutePath();
            if (abs.startsWith(rootDir)) {
                String rel = abs.substring(rootDir.length());
                if (rel.isEmpty()) return "/";
                if (!rel.startsWith("/")) rel = "/" + rel;
                return rel;
            }
            return "/";
        }

        private void sendResponse(int code, String message) {
            controlOut.println(code + " " + message);
        }

        private void cleanup() {
            try {
                if (dataServerSocket != null) dataServerSocket.close();
            } catch (IOException e) {}
            try {
                controlSocket.close();
            } catch (IOException e) {}
        }
    }
}
