package com.qnxcraft.blackterm.terminal;

import android.os.Handler;
import android.os.Looper;

import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Core terminal emulator handling PTY process and VT100/ANSI escape sequences.
 */
public class TerminalEmulator {

    public interface TerminalListener {
        void onScreenUpdate();
        void onTitleChanged(String title);
    }

    private int columns;
    private int rows;
    private char[][] screen;
    private int[] fgColors;
    private int[] bgColors;
    private int cursorRow;
    private int cursorCol;
    private boolean cursorVisible = true;

    private Process process;
    private OutputStream processInput;
    private InputStream processOutput;
    private Thread readerThread;
    private volatile boolean running = false;
    private boolean localEchoEnabled = true;
    private String preferredShell = "auto";

    private TerminalListener listener;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    // ANSI escape sequence state
    private static final int STATE_NORMAL = 0;
    private static final int STATE_ESCAPE = 1;
    private static final int STATE_CSI = 2;
    private static final int STATE_OSC = 3;
    private int escapeState = STATE_NORMAL;
    private StringBuilder escapeBuffer = new StringBuilder();

    // Scrollback
    private char[][] scrollbackBuffer;
    private int scrollbackSize = 1000;
    private int scrollbackCount = 0;

    // Attributes
    private int currentFgColor = 0xFF00FF00; // Green
    private int currentBgColor = 0xFF000000; // Black
    private boolean boldMode = false;
    private boolean reverseMode = false;

    // Saved cursor position
    private int savedCursorRow = 0;
    private int savedCursorCol = 0;

    public TerminalEmulator(int columns, int rows) {
        this.columns = columns;
        this.rows = rows;
        this.screen = new char[rows][columns];
        this.fgColors = new int[rows * columns];
        this.bgColors = new int[rows * columns];
        clearScreen();
    }

    public void setListener(TerminalListener listener) {
        this.listener = listener;
    }

    public void setPreferredShell(String preferredShell) {
        if (preferredShell == null || preferredShell.trim().length() == 0) {
            this.preferredShell = "auto";
        } else {
            this.preferredShell = preferredShell.trim();
        }
    }

    public void start() {
        List<String[]> candidates = buildShellCandidates();

        StringBuilder failures = new StringBuilder();
        for (int i = 0; i < candidates.size(); i++) {
            String[] cmd = candidates.get(i);
            try {
                ProcessBuilder pb = new ProcessBuilder(cmd);
                Map<String, String> env = pb.environment();
                env.put("TERM", "xterm");
                env.put("PATH", "/system/bin:/system/xbin:/sbin:/vendor/bin:/bin:/usr/bin");
                env.put("COLUMNS", String.valueOf(columns));
                env.put("LINES", String.valueOf(rows));
                env.put("PS1", "blackterm$ ");
                pb.directory(new java.io.File("/"));
                pb.redirectErrorStream(true);

                process = pb.start();
                processInput = process.getOutputStream();
                processOutput = process.getInputStream();
                running = true;

                readerThread = new Thread(new Runnable() {
                    @Override
                    public void run() {
                        readProcessOutput();
                    }
                }, "TerminalReader");
                readerThread.setDaemon(true);
                readerThread.start();

                Thread watcher = new Thread(new Runnable() {
                    @Override
                    public void run() {
                        try {
                            int exitCode = process.waitFor();
                            if (running) {
                                appendText("\r\n[Shell exited: " + exitCode + "]\r\n");
                            }
                        } catch (InterruptedException ignored) {
                            Thread.currentThread().interrupt();
                        }
                    }
                }, "TerminalProcessWatcher");
                watcher.setDaemon(true);
                watcher.start();

                appendText("[Started shell: " + joinCommand(cmd) + "]\r\n");
                return;
            } catch (IOException e) {
                failures.append(joinCommand(cmd))
                        .append(" -> ")
                        .append(e.getClass().getSimpleName())
                        .append(": ")
                        .append(e.getMessage())
                        .append("\n");
            } catch (SecurityException e) {
                failures.append(joinCommand(cmd))
                        .append(" -> ")
                        .append(e.getClass().getSimpleName())
                        .append(": ")
                        .append(e.getMessage())
                        .append("\n");
            }
        }

        appendText("Failed to start shell. Tried commands:\r\n" + failures.toString() + "\r\n");
    }

    public void stop() {
        running = false;
        if (process != null) {
            process.destroy();
            process = null;
        }
        if (readerThread != null) {
            readerThread.interrupt();
            readerThread = null;
        }
    }

    public void reset() {
        stop();
        clearScreen();
        cursorRow = 0;
        cursorCol = 0;
        escapeState = STATE_NORMAL;
        escapeBuffer.setLength(0);
        notifyUpdate();
    }

    public void resize(int newColumns, int newRows) {
        if (newColumns == columns && newRows == rows) return;

        char[][] newScreen = new char[newRows][newColumns];
        int[] newFgColors = new int[newRows * newColumns];
        int[] newBgColors = new int[newRows * newColumns];

        int copyRows = Math.min(rows, newRows);
        int copyCols = Math.min(columns, newColumns);

        for (int r = 0; r < copyRows; r++) {
            System.arraycopy(screen[r], 0, newScreen[r], 0, copyCols);
            for (int c = copyCols; c < newColumns; c++) {
                newScreen[r][c] = ' ';
            }
        }
        for (int r = copyRows; r < newRows; r++) {
            for (int c = 0; c < newColumns; c++) {
                newScreen[r][c] = ' ';
            }
        }

        java.util.Arrays.fill(newFgColors, currentFgColor);
        java.util.Arrays.fill(newBgColors, currentBgColor);

        columns = newColumns;
        rows = newRows;
        screen = newScreen;
        fgColors = newFgColors;
        bgColors = newBgColors;

        if (cursorRow >= rows) cursorRow = rows - 1;
        if (cursorCol >= columns) cursorCol = columns - 1;

        notifyUpdate();
    }

    public void sendText(String text) {
        if (text == null || text.length() == 0) {
            return;
        }

        if (localEchoEnabled) {
            appendLocalEcho(text);
        }

        if (processInput != null && running) {
            try {
                processInput.write(text.getBytes("UTF-8"));
                processInput.flush();
            } catch (IOException e) {
                appendText("\r\n[Input write failed: " + e.getMessage() + "]\r\n");
            }
        } else {
            appendText("\r\n[Shell is not running]\r\n");
        }
    }

    public void sendKeyCode(int keyCode) {
        switch (keyCode) {
            case android.view.KeyEvent.KEYCODE_TAB:
                sendText("\t");
                break;
            case android.view.KeyEvent.KEYCODE_ESCAPE:
                sendText("\033");
                break;
            case android.view.KeyEvent.KEYCODE_ENTER:
                sendText("\n");
                break;
            case android.view.KeyEvent.KEYCODE_DEL:
                sendText("\177");
                break;
            case android.view.KeyEvent.KEYCODE_DPAD_UP:
                sendText("\033[A");
                break;
            case android.view.KeyEvent.KEYCODE_DPAD_DOWN:
                sendText("\033[B");
                break;
            case android.view.KeyEvent.KEYCODE_DPAD_RIGHT:
                sendText("\033[C");
                break;
            case android.view.KeyEvent.KEYCODE_DPAD_LEFT:
                sendText("\033[D");
                break;
        }
    }

    public void sendControlKey(char keyChar) {
        char upper = Character.toUpperCase(keyChar);
        if (upper >= 'A' && upper <= 'Z') {
            sendText(String.valueOf((char) (upper - 'A' + 1)));
        } else if (upper == ' ') {
            sendText(String.valueOf((char) 0));
        }
    }

    private void readProcessOutput() {
        byte[] buffer = new byte[4096];
        try {
            while (running) {
                int bytesRead = processOutput.read(buffer);
                if (bytesRead == -1) {
                    break;
                }
                final String data = new String(buffer, 0, bytesRead, "UTF-8");
                processTerminalData(data);
            }
        } catch (IOException e) {
            if (running) {
                appendText("\r\n[Process terminated]\r\n");
            }
        }
        running = false;
    }

    private synchronized void processTerminalData(String data) {
        for (int i = 0; i < data.length(); i++) {
            char c = data.charAt(i);
            processChar(c);
        }
        notifyUpdate();
    }

    private void processChar(char c) {
        switch (escapeState) {
            case STATE_NORMAL:
                if (c == '\033') {
                    escapeState = STATE_ESCAPE;
                    escapeBuffer.setLength(0);
                } else if (c == '\r') {
                    cursorCol = 0;
                } else if (c == '\n') {
                    cursorCol = 0;
                    newLine();
                } else if (c == '\b') {
                    if (cursorCol > 0) cursorCol--;
                } else if (c == '\t') {
                    int nextTab = ((cursorCol / 8) + 1) * 8;
                    cursorCol = Math.min(nextTab, columns - 1);
                } else if (c == '\007') {
                    // Bell - ignore
                } else if (c >= ' ' && c != 0x7f) {
                    putChar(c);
                }
                break;

            case STATE_ESCAPE:
                if (c == '[') {
                    escapeState = STATE_CSI;
                    escapeBuffer.setLength(0);
                } else if (c == ']') {
                    escapeState = STATE_OSC;
                    escapeBuffer.setLength(0);
                } else if (c == '(') {
                    // Character set designation, ignore next char
                    escapeState = STATE_NORMAL;
                } else if (c == ')') {
                    escapeState = STATE_NORMAL;
                } else if (c == '7') {
                    // Save cursor
                    savedCursorRow = cursorRow;
                    savedCursorCol = cursorCol;
                    escapeState = STATE_NORMAL;
                } else if (c == '8') {
                    // Restore cursor
                    cursorRow = savedCursorRow;
                    cursorCol = savedCursorCol;
                    escapeState = STATE_NORMAL;
                } else if (c == 'D') {
                    // Scroll down
                    newLine();
                    escapeState = STATE_NORMAL;
                } else if (c == 'M') {
                    // Scroll up
                    if (cursorRow > 0) {
                        cursorRow--;
                    } else {
                        scrollDown();
                    }
                    escapeState = STATE_NORMAL;
                } else if (c == 'c') {
                    // Reset
                    clearScreen();
                    cursorRow = 0;
                    cursorCol = 0;
                    escapeState = STATE_NORMAL;
                } else {
                    escapeState = STATE_NORMAL;
                }
                break;

            case STATE_CSI:
                if (c >= 0x40 && c <= 0x7e) {
                    escapeBuffer.append(c);
                    handleCSI(escapeBuffer.toString());
                    escapeState = STATE_NORMAL;
                } else {
                    escapeBuffer.append(c);
                }
                break;

            case STATE_OSC:
                if (c == '\007' || c == '\033') {
                    handleOSC(escapeBuffer.toString());
                    escapeState = STATE_NORMAL;
                } else {
                    escapeBuffer.append(c);
                }
                break;
        }
    }

    private void handleCSI(String seq) {
        if (seq.isEmpty()) return;

        char command = seq.charAt(seq.length() - 1);
        String params = seq.substring(0, seq.length() - 1);
        int[] args = parseCSIArgs(params);

        switch (command) {
            case 'A': // Cursor up
                cursorRow = Math.max(0, cursorRow - Math.max(1, getArg(args, 0, 1)));
                break;
            case 'B': // Cursor down
                cursorRow = Math.min(rows - 1, cursorRow + Math.max(1, getArg(args, 0, 1)));
                break;
            case 'C': // Cursor right
                cursorCol = Math.min(columns - 1, cursorCol + Math.max(1, getArg(args, 0, 1)));
                break;
            case 'D': // Cursor left
                cursorCol = Math.max(0, cursorCol - Math.max(1, getArg(args, 0, 1)));
                break;
            case 'H': // Cursor position
            case 'f':
                cursorRow = Math.min(rows - 1, Math.max(0, getArg(args, 0, 1) - 1));
                cursorCol = Math.min(columns - 1, Math.max(0, getArg(args, 1, 1) - 1));
                break;
            case 'J': // Erase display
                eraseDisplay(getArg(args, 0, 0));
                break;
            case 'K': // Erase line
                eraseLine(getArg(args, 0, 0));
                break;
            case 'L': // Insert lines
                insertLines(Math.max(1, getArg(args, 0, 1)));
                break;
            case 'M': // Delete lines
                deleteLines(Math.max(1, getArg(args, 0, 1)));
                break;
            case 'P': // Delete characters
                deleteChars(Math.max(1, getArg(args, 0, 1)));
                break;
            case 'm': // SGR - Select Graphic Rendition
                handleSGR(args);
                break;
            case 'r': // Set scrolling region
                // Simplified - ignore for now
                break;
            case 'h': // Set mode
            case 'l': // Reset mode
                // Handle cursor visibility
                if (params.equals("?25")) {
                    cursorVisible = (command == 'h');
                }
                break;
            case 'd': // Cursor to row
                cursorRow = Math.min(rows - 1, Math.max(0, getArg(args, 0, 1) - 1));
                break;
            case 'G': // Cursor to column
                cursorCol = Math.min(columns - 1, Math.max(0, getArg(args, 0, 1) - 1));
                break;
            case '@': // Insert characters
                insertChars(Math.max(1, getArg(args, 0, 1)));
                break;
            case 'X': // Erase characters
                eraseChars(Math.max(1, getArg(args, 0, 1)));
                break;
        }
    }

    private void handleSGR(int[] args) {
        if (args.length == 0) {
            // Reset
            currentFgColor = 0xFF00FF00;
            currentBgColor = 0xFF000000;
            boldMode = false;
            reverseMode = false;
            return;
        }

        for (int i = 0; i < args.length; i++) {
            int code = args[i];
            switch (code) {
                case 0: // Reset
                    currentFgColor = 0xFF00FF00;
                    currentBgColor = 0xFF000000;
                    boldMode = false;
                    reverseMode = false;
                    break;
                case 1: boldMode = true; break;
                case 7: reverseMode = true; break;
                case 22: boldMode = false; break;
                case 27: reverseMode = false; break;
                case 30: currentFgColor = 0xFF000000; break;
                case 31: currentFgColor = 0xFFCC0000; break;
                case 32: currentFgColor = 0xFF00CC00; break;
                case 33: currentFgColor = 0xFFCCCC00; break;
                case 34: currentFgColor = 0xFF0000CC; break;
                case 35: currentFgColor = 0xFFCC00CC; break;
                case 36: currentFgColor = 0xFF00CCCC; break;
                case 37: currentFgColor = 0xFFCCCCCC; break;
                case 39: currentFgColor = 0xFF00FF00; break; // Default fg
                case 40: currentBgColor = 0xFF000000; break;
                case 41: currentBgColor = 0xFFCC0000; break;
                case 42: currentBgColor = 0xFF00CC00; break;
                case 43: currentBgColor = 0xFFCCCC00; break;
                case 44: currentBgColor = 0xFF0000CC; break;
                case 45: currentBgColor = 0xFFCC00CC; break;
                case 46: currentBgColor = 0xFF00CCCC; break;
                case 47: currentBgColor = 0xFFCCCCCC; break;
                case 49: currentBgColor = 0xFF000000; break; // Default bg
                // Bright colors
                case 90: currentFgColor = 0xFF555555; break;
                case 91: currentFgColor = 0xFFFF5555; break;
                case 92: currentFgColor = 0xFF55FF55; break;
                case 93: currentFgColor = 0xFFFFFF55; break;
                case 94: currentFgColor = 0xFF5555FF; break;
                case 95: currentFgColor = 0xFFFF55FF; break;
                case 96: currentFgColor = 0xFF55FFFF; break;
                case 97: currentFgColor = 0xFFFFFFFF; break;
            }
        }
    }

    private void handleOSC(String data) {
        // OSC 0 or 2 = set title
        int semicolon = data.indexOf(';');
        if (semicolon >= 0) {
            try {
                int code = Integer.parseInt(data.substring(0, semicolon));
                String text = data.substring(semicolon + 1);
                if ((code == 0 || code == 2) && listener != null) {
                    final String title = text;
                    mainHandler.post(new Runnable() {
                        @Override
                        public void run() {
                            listener.onTitleChanged(title);
                        }
                    });
                }
            } catch (NumberFormatException e) {
                // Ignore
            }
        }
    }

    private int[] parseCSIArgs(String params) {
        if (params.isEmpty() || params.startsWith("?")) {
            return new int[0];
        }
        String[] parts = params.split(";");
        int[] result = new int[parts.length];
        for (int i = 0; i < parts.length; i++) {
            try {
                result[i] = Integer.parseInt(parts[i]);
            } catch (NumberFormatException e) {
                result[i] = 0;
            }
        }
        return result;
    }

    private int getArg(int[] args, int index, int defaultVal) {
        if (args == null || index >= args.length || args[index] == 0) {
            return defaultVal;
        }
        return args[index];
    }

    private void putChar(char c) {
        if (cursorCol >= columns) {
            cursorCol = 0;
            newLine();
        }
        screen[cursorRow][cursorCol] = c;
        int idx = cursorRow * columns + cursorCol;
        fgColors[idx] = reverseMode ? currentBgColor : currentFgColor;
        bgColors[idx] = reverseMode ? currentFgColor : currentBgColor;
        cursorCol++;
    }

    private void newLine() {
        if (cursorRow < rows - 1) {
            cursorRow++;
        } else {
            scrollUp();
        }
    }

    private void scrollUp() {
        System.arraycopy(screen, 1, screen, 0, rows - 1);
        screen[rows - 1] = new char[columns];
        java.util.Arrays.fill(screen[rows - 1], ' ');

        System.arraycopy(fgColors, columns, fgColors, 0, (rows - 1) * columns);
        System.arraycopy(bgColors, columns, bgColors, 0, (rows - 1) * columns);
        int start = (rows - 1) * columns;
        java.util.Arrays.fill(fgColors, start, start + columns, currentFgColor);
        java.util.Arrays.fill(bgColors, start, start + columns, currentBgColor);
    }

    private void scrollDown() {
        System.arraycopy(screen, 0, screen, 1, rows - 1);
        screen[0] = new char[columns];
        java.util.Arrays.fill(screen[0], ' ');

        System.arraycopy(fgColors, 0, fgColors, columns, (rows - 1) * columns);
        System.arraycopy(bgColors, 0, bgColors, columns, (rows - 1) * columns);
        java.util.Arrays.fill(fgColors, 0, columns, currentFgColor);
        java.util.Arrays.fill(bgColors, 0, columns, currentBgColor);
    }

    private void clearScreen() {
        for (int r = 0; r < rows; r++) {
            screen[r] = new char[columns];
            java.util.Arrays.fill(screen[r], ' ');
        }
        java.util.Arrays.fill(fgColors, currentFgColor);
        java.util.Arrays.fill(bgColors, currentBgColor);
    }

    private void eraseDisplay(int mode) {
        switch (mode) {
            case 0: // Erase from cursor to end
                eraseLine(0);
                for (int r = cursorRow + 1; r < rows; r++) {
                    java.util.Arrays.fill(screen[r], ' ');
                    int start = r * columns;
                    java.util.Arrays.fill(fgColors, start, start + columns, currentFgColor);
                    java.util.Arrays.fill(bgColors, start, start + columns, currentBgColor);
                }
                break;
            case 1: // Erase from start to cursor
                for (int r = 0; r < cursorRow; r++) {
                    java.util.Arrays.fill(screen[r], ' ');
                    int start = r * columns;
                    java.util.Arrays.fill(fgColors, start, start + columns, currentFgColor);
                    java.util.Arrays.fill(bgColors, start, start + columns, currentBgColor);
                }
                for (int c = 0; c <= cursorCol && c < columns; c++) {
                    screen[cursorRow][c] = ' ';
                }
                break;
            case 2: // Erase entire display
                clearScreen();
                break;
        }
    }

    private void eraseLine(int mode) {
        switch (mode) {
            case 0: // From cursor to end of line
                for (int c = cursorCol; c < columns; c++) {
                    screen[cursorRow][c] = ' ';
                    int idx = cursorRow * columns + c;
                    fgColors[idx] = currentFgColor;
                    bgColors[idx] = currentBgColor;
                }
                break;
            case 1: // From start to cursor
                for (int c = 0; c <= cursorCol && c < columns; c++) {
                    screen[cursorRow][c] = ' ';
                    int idx = cursorRow * columns + c;
                    fgColors[idx] = currentFgColor;
                    bgColors[idx] = currentBgColor;
                }
                break;
            case 2: // Entire line
                java.util.Arrays.fill(screen[cursorRow], ' ');
                int start = cursorRow * columns;
                java.util.Arrays.fill(fgColors, start, start + columns, currentFgColor);
                java.util.Arrays.fill(bgColors, start, start + columns, currentBgColor);
                break;
        }
    }

    private void insertLines(int count) {
        for (int i = 0; i < count && cursorRow + i < rows; i++) {
            System.arraycopy(screen, cursorRow, screen, cursorRow + 1, rows - cursorRow - 1);
            screen[cursorRow] = new char[columns];
            java.util.Arrays.fill(screen[cursorRow], ' ');
        }
    }

    private void deleteLines(int count) {
        for (int i = 0; i < count && cursorRow < rows; i++) {
            System.arraycopy(screen, cursorRow + 1, screen, cursorRow, rows - cursorRow - 1);
            screen[rows - 1] = new char[columns];
            java.util.Arrays.fill(screen[rows - 1], ' ');
        }
    }

    private void deleteChars(int count) {
        int end = Math.min(cursorCol + count, columns);
        System.arraycopy(screen[cursorRow], end, screen[cursorRow], cursorCol, columns - end);
        for (int c = columns - count; c < columns; c++) {
            if (c >= 0) screen[cursorRow][c] = ' ';
        }
    }

    private void insertChars(int count) {
        int src = cursorCol;
        int dst = Math.min(cursorCol + count, columns);
        int len = columns - dst;
        if (len > 0) {
            System.arraycopy(screen[cursorRow], src, screen[cursorRow], dst, len);
        }
        for (int c = cursorCol; c < dst && c < columns; c++) {
            screen[cursorRow][c] = ' ';
        }
    }

    private void eraseChars(int count) {
        for (int c = cursorCol; c < cursorCol + count && c < columns; c++) {
            screen[cursorRow][c] = ' ';
        }
    }

    private void appendText(final String text) {
        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                for (int i = 0; i < text.length(); i++) {
                    processChar(text.charAt(i));
                }
                notifyUpdate();
            }
        });
    }

    private void appendLocalEcho(String text) {
        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                for (int i = 0; i < text.length(); i++) {
                    char c = text.charAt(i);
                    if (c == '\n') {
                        processChar('\r');
                        processChar('\n');
                    } else if (c == '\r') {
                        processChar('\r');
                    } else if (c == '\t') {
                        // Let the shell completion result render instead of expanding a local tab.
                    } else if (c == '\b' || c == 0x7f) {
                        applyLocalBackspace();
                    } else if (c >= ' ' && c != 0x7f) {
                        processChar(c);
                    }
                }
                notifyUpdate();
            }
        });
    }

    private void applyLocalBackspace() {
        if (cursorCol > 0) {
            cursorCol--;
            screen[cursorRow][cursorCol] = ' ';
            int idx = cursorRow * columns + cursorCol;
            fgColors[idx] = reverseMode ? currentBgColor : currentFgColor;
            bgColors[idx] = reverseMode ? currentFgColor : currentBgColor;
        }
    }

    private void notifyUpdate() {
        if (listener != null) {
            mainHandler.post(new Runnable() {
                @Override
                public void run() {
                    listener.onScreenUpdate();
                }
            });
        }
    }

    private String joinCommand(String[] cmd) {
        if (cmd == null || cmd.length == 0) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < cmd.length; i++) {
            if (i > 0) {
                sb.append(' ');
            }
            sb.append(cmd[i]);
        }
        return sb.toString();
    }

    private List<String[]> buildShellCandidates() {
        List<String[]> candidates = new ArrayList<String[]>();
        if (!"auto".equals(preferredShell)) {
            addShellVariants(candidates, preferredShell);
        }

        addShellVariants(candidates, "/system/bin/bash");
        addShellVariants(candidates, "/system/xbin/bash");
        addShellVariants(candidates, "/bin/bash");
        addShellVariants(candidates, "bash");
        addShellVariants(candidates, "/system/bin/zsh");
        addShellVariants(candidates, "/system/xbin/zsh");
        addShellVariants(candidates, "/bin/zsh");
        addShellVariants(candidates, "zsh");
        addShellVariants(candidates, "/system/bin/sh");
        addShellVariants(candidates, "/system/xbin/sh");
        addShellVariants(candidates, "/bin/sh");
        addShellVariants(candidates, "sh");

        return candidates;
    }

    private void addShellVariants(List<String[]> candidates, String shell) {
        if (shell == null || shell.trim().length() == 0) {
            return;
        }

        String value = shell.trim();
        addCandidateIfMissing(candidates, new String[] {value, "-i"});
        addCandidateIfMissing(candidates, new String[] {value});
    }

    private void addCandidateIfMissing(List<String[]> candidates, String[] candidate) {
        String joined = joinCommand(candidate);
        for (int i = 0; i < candidates.size(); i++) {
            if (joinCommand(candidates.get(i)).equals(joined)) {
                return;
            }
        }
        candidates.add(candidate);
    }

    // Getters
    public int getColumns() { return columns; }
    public int getRows() { return rows; }
    public char[][] getScreen() { return screen; }
    public int[] getFgColors() { return fgColors; }
    public int[] getBgColors() { return bgColors; }
    public int getCursorRow() { return cursorRow; }
    public int getCursorCol() { return cursorCol; }
    public boolean isCursorVisible() { return cursorVisible; }
    public boolean isRunning() { return running; }
}
