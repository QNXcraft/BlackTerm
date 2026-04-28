package com.qnxcraft.blackterm;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.preference.PreferenceManager;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MenuItem;
import android.view.View;
import android.view.WindowManager;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.Toast;
import android.widget.PopupMenu;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;

import com.qnxcraft.blackterm.terminal.TerminalEmulator;
import com.qnxcraft.blackterm.terminal.TerminalView;

import java.util.ArrayList;
import java.util.List;

public class TerminalActivity extends Activity implements SharedPreferences.OnSharedPreferenceChangeListener {

    private String extraBinPath = null;

    private static final int MENU_NEW_TAB = 1;
    private static final int MENU_CLOSE_TAB = 2;
    private static final int MENU_RESTART_TAB = 3;
    private static final int MENU_TOGGLE_KEYBOARD = 4;
    private static final int MENU_PASTE = 5;
    private static final int MENU_SETTINGS = 6;
    private static final int MENU_ABOUT = 7;

    private SharedPreferences prefs;
    private LinearLayout rootLayout;
    private LinearLayout tabStrip;
    private LinearLayout buttonBar;
    private FrameLayout terminalContainer;
    private ImageButton burgerButton;
    private boolean shiftPressed = false;
    private boolean capsLockOn = false;
    private boolean ctrlPressed = false;
    private Button shiftButton;
    private Button capsButton;
    private Button ctrlButton;
    private final List<TerminalSession> sessions = new ArrayList<TerminalSession>();
    private int activeSessionIndex = -1;
    private int nextSessionId = 1;

    private static final class TerminalSession {
        final int id;
        final TerminalEmulator emulator;
        final TerminalView view;
        final Button tabButton;

        TerminalSession(int id, TerminalEmulator emulator, TerminalView view, Button tabButton) {
            this.id = id;
            this.emulator = emulator;
            this.view = view;
            this.tabButton = tabButton;
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        prefs = PreferenceManager.getDefaultSharedPreferences(this);
        prefs.registerOnSharedPreferenceChangeListener(this);

        rootLayout = new LinearLayout(this);
        rootLayout.setOrientation(LinearLayout.VERTICAL);
        rootLayout.setBackgroundColor(Color.BLACK);

        rootLayout.addView(createTabBar());

        terminalContainer = new FrameLayout(this);
        terminalContainer.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1.0f));
        rootLayout.addView(terminalContainer);

        buttonBar = createButtonBar();
        rootLayout.addView(buttonBar);

        setContentView(rootLayout);

        setupBashWrapper();
        applyPreferences(prefs);
        createNewSession();
    }

    /**
     * Creates a tiny bash wrapper script in the app's private bin directory so that
     * typing `bash` in the terminal finds an executable instead of returning "not found".
     * The wrapper simply exec's the system sh with interactive mode.
     */
    private void setupBashWrapper() {
        File binDir = new File(getFilesDir(), "bin");
        if (!binDir.exists() && !binDir.mkdirs()) {
            return;
        }
        extraBinPath = binDir.getAbsolutePath();

        String[] wrappers = {"bash", "zsh"};
        for (String name : wrappers) {
            File wrapper = new File(binDir, name);
            if (!wrapper.exists()) {
                try {
                    FileOutputStream fos = new FileOutputStream(wrapper);
                    fos.write(("#!/system/bin/sh\nexec /system/bin/sh -i \"$@\"\n").getBytes("UTF-8"));
                    fos.close();
                    wrapper.setExecutable(true, false);
                } catch (IOException ignored) {}
            }
        }
    }

    private View createTabBar() {
        LinearLayout topBar = new LinearLayout(this);
        topBar.setOrientation(LinearLayout.HORIZONTAL);
        topBar.setBackgroundColor(Color.parseColor("#111827"));
        topBar.setGravity(Gravity.CENTER_VERTICAL);
        topBar.setPadding(dpToPx(4), dpToPx(4), dpToPx(4), dpToPx(2));

        HorizontalScrollView scrollView = new HorizontalScrollView(this);
        scrollView.setHorizontalScrollBarEnabled(false);
        scrollView.setLayoutParams(new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1.0f));

        tabStrip = new LinearLayout(this);
        tabStrip.setOrientation(LinearLayout.HORIZONTAL);
        scrollView.addView(tabStrip, new HorizontalScrollView.LayoutParams(
                HorizontalScrollView.LayoutParams.WRAP_CONTENT,
                HorizontalScrollView.LayoutParams.WRAP_CONTENT));
        topBar.addView(scrollView);

        Button addTabButton = new Button(this);
        addTabButton.setText("+");
        addTabButton.setTextColor(Color.WHITE);
        addTabButton.setAllCaps(false);
        addTabButton.setBackgroundColor(Color.parseColor("#0f3460"));
        addTabButton.setLayoutParams(new LinearLayout.LayoutParams(dpToPx(44), dpToPx(36)));
        addTabButton.setFocusable(false);
        addTabButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                createNewSession();
            }
        });
        topBar.addView(addTabButton);

        return topBar;
    }

    private LinearLayout createButtonBar() {
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setBackgroundColor(Color.parseColor("#1a1a2e"));
        bar.setGravity(Gravity.CENTER_VERTICAL);
        int padding = dpToPx(4);
        bar.setPadding(padding, padding, padding, padding);

        LinearLayout.LayoutParams btnParams = new LinearLayout.LayoutParams(
                0, dpToPx(42), 1.0f);
        btnParams.setMargins(dpToPx(2), 0, dpToPx(2), 0);

        burgerButton = new ImageButton(this);
        burgerButton.setImageResource(android.R.drawable.ic_menu_more);
        burgerButton.setColorFilter(Color.WHITE);
        burgerButton.setBackgroundColor(Color.parseColor("#16213e"));
        burgerButton.setLayoutParams(btnParams);
        burgerButton.setPadding(dpToPx(8), dpToPx(8), dpToPx(8), dpToPx(8));
        burgerButton.setFocusable(false);
        burgerButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showBurgerMenu(v);
            }
        });
        bar.addView(burgerButton);

        Button tabButton = createBarButton("TAB", btnParams);
        tabButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                TerminalSession session = getActiveSession();
                if (session != null) {
                    session.emulator.performTabCompletion();
                }
            }
        });
        bar.addView(tabButton);

        Button escButton = createBarButton("ESC", btnParams);
        escButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                TerminalSession session = getActiveSession();
                if (session != null) {
                    session.emulator.sendKeyCode(KeyEvent.KEYCODE_ESCAPE);
                }
            }
        });
        bar.addView(escButton);

        ctrlButton = createBarButton("CTRL", btnParams);
        ctrlButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                ctrlPressed = !ctrlPressed;
                updateModifierButtons();
                syncActiveModifierState();
            }
        });
        bar.addView(ctrlButton);

        shiftButton = createBarButton("SHIFT", btnParams);
        shiftButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                shiftPressed = !shiftPressed;
                updateModifierButtons();
                syncActiveModifierState();
            }
        });
        bar.addView(shiftButton);

        capsButton = createBarButton("CAPS", btnParams);
        capsButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                capsLockOn = !capsLockOn;
                updateModifierButtons();
                syncActiveModifierState();
            }
        });
        bar.addView(capsButton);

        return bar;
    }

    private Button createBarButton(String text, LinearLayout.LayoutParams params) {
        Button btn = new Button(this);
        btn.setText(text);
        btn.setTextColor(Color.WHITE);
        btn.setTextSize(12);
        btn.setBackgroundColor(Color.parseColor("#16213e"));
        btn.setLayoutParams(params);
        btn.setPadding(dpToPx(4), dpToPx(2), dpToPx(4), dpToPx(2));
        btn.setAllCaps(false);
        // Prevent button bar from stealing focus from the terminal view.
        btn.setFocusable(false);
        return btn;
    }

    private Button createSessionTabButton(String label) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, dpToPx(36));
        params.setMargins(0, 0, dpToPx(4), 0);

        Button button = new Button(this);
        button.setText(label);
        button.setTextSize(12);
        button.setAllCaps(false);
        button.setTextColor(Color.WHITE);
        button.setBackgroundColor(Color.parseColor("#16213e"));
        button.setLayoutParams(params);
        button.setPadding(dpToPx(10), dpToPx(2), dpToPx(10), dpToPx(2));
        // Prevent tab buttons from stealing focus from the terminal view.
        button.setFocusable(false);
        return button;
    }

    private void createNewSession() {
        final TerminalEmulator emulator = new TerminalEmulator(
                parseIntPreference("terminal_columns", 80),
                parseIntPreference("terminal_rows", 24));
        emulator.setPreferredShell(prefs.getString("shell_command", "auto"));
        if (extraBinPath != null) {
            emulator.setExtraPath(extraBinPath);
        }

        final TerminalView view = new TerminalView(this, emulator);
        view.setLayoutParams(new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));
        view.setOnPasteRequestedListener(new TerminalView.OnPasteRequestedListener() {
            @Override
            public void onPasteRequested() {
                pasteClipboard();
            }
        });
        applyPreferencesToView(view, prefs);

        final TerminalSession session = new TerminalSession(
                nextSessionId,
                emulator,
                view,
                createSessionTabButton("Tab " + nextSessionId));
        nextSessionId++;

        session.tabButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                switchToSession(sessions.indexOf(session));
            }
        });

        sessions.add(session);
        terminalContainer.addView(view);
        tabStrip.addView(session.tabButton);
        emulator.start();
        switchToSession(sessions.size() - 1);
    }

    private void closeCurrentSession() {
        if (sessions.size() <= 1) {
            Toast.makeText(this, "At least one tab must remain open", Toast.LENGTH_SHORT).show();
            return;
        }

        int index = activeSessionIndex;
        TerminalSession session = getActiveSession();
        if (session == null) {
            return;
        }

        session.emulator.stop();
        terminalContainer.removeView(session.view);
        tabStrip.removeView(session.tabButton);
        sessions.remove(index);
        switchToSession(Math.max(0, index - 1));
    }

    private void restartCurrentSession() {
        TerminalSession session = getActiveSession();
        if (session == null) {
            return;
        }

        session.emulator.reset();
        session.emulator.setPreferredShell(prefs.getString("shell_command", "auto"));
        session.emulator.start();
        session.view.requestFocus();
    }

    private void switchToSession(int index) {
        if (index < 0 || index >= sessions.size()) {
            return;
        }

        activeSessionIndex = index;
        for (int i = 0; i < sessions.size(); i++) {
            TerminalSession session = sessions.get(i);
            session.view.setVisibility(i == index ? View.VISIBLE : View.GONE);
        }

        refreshTabButtons();
        syncActiveModifierState();
        final TerminalSession active = getActiveSession();
        if (active != null) {
            // Use post() to request focus after the current event (e.g. button click) fully
            // completes, preventing the button from stealing focus back (fixes bug 12).
            active.view.post(new Runnable() {
                @Override
                public void run() {
                    active.view.requestFocus();
                }
            });
        }
    }

    private void refreshTabButtons() {
        for (int i = 0; i < sessions.size(); i++) {
            Button tabButton = sessions.get(i).tabButton;
            if (i == activeSessionIndex) {
                tabButton.setBackgroundColor(Color.parseColor("#0f3460"));
                tabButton.setTextColor(Color.parseColor("#e94560"));
            } else {
                tabButton.setBackgroundColor(Color.parseColor("#16213e"));
                tabButton.setTextColor(Color.WHITE);
            }
        }
    }

    private TerminalSession getActiveSession() {
        if (activeSessionIndex < 0 || activeSessionIndex >= sessions.size()) {
            return null;
        }
        return sessions.get(activeSessionIndex);
    }

    private void updateModifierButtons() {
        if (ctrlPressed) {
            ctrlButton.setBackgroundColor(Color.parseColor("#0f3460"));
            ctrlButton.setTextColor(Color.parseColor("#e94560"));
        } else {
            ctrlButton.setBackgroundColor(Color.parseColor("#16213e"));
            ctrlButton.setTextColor(Color.WHITE);
        }

        if (shiftPressed) {
            shiftButton.setBackgroundColor(Color.parseColor("#0f3460"));
            shiftButton.setTextColor(Color.parseColor("#e94560"));
        } else {
            shiftButton.setBackgroundColor(Color.parseColor("#16213e"));
            shiftButton.setTextColor(Color.WHITE);
        }

        if (capsLockOn) {
            capsButton.setBackgroundColor(Color.parseColor("#0f3460"));
            capsButton.setTextColor(Color.parseColor("#e94560"));
        } else {
            capsButton.setBackgroundColor(Color.parseColor("#16213e"));
            capsButton.setTextColor(Color.WHITE);
        }
    }

    private void syncActiveModifierState() {
        TerminalSession session = getActiveSession();
        if (session == null) {
            return;
        }
        session.view.setCtrlState(ctrlPressed);
        session.view.setShiftState(shiftPressed);
        session.view.setCapsLockState(capsLockOn);
    }

    private void showBurgerMenu(View anchor) {
        PopupMenu popup = new PopupMenu(this, anchor);
        popup.getMenu().add(0, MENU_NEW_TAB, 0, "New Tab");
        popup.getMenu().add(0, MENU_CLOSE_TAB, 1, "Close Tab");
        popup.getMenu().add(0, MENU_RESTART_TAB, 2, "Restart Tab");
        popup.getMenu().add(0, MENU_TOGGLE_KEYBOARD, 3, "Toggle Keyboard");
        popup.getMenu().add(0, MENU_PASTE, 4, "Paste");
        popup.getMenu().add(0, MENU_SETTINGS, 5, "Settings");
        popup.getMenu().add(0, MENU_ABOUT, 6, "About");

        popup.setOnMenuItemClickListener(new PopupMenu.OnMenuItemClickListener() {
            @Override
            public boolean onMenuItemClick(MenuItem item) {
                switch (item.getItemId()) {
                    case MENU_NEW_TAB:
                        createNewSession();
                        return true;
                    case MENU_CLOSE_TAB:
                        closeCurrentSession();
                        return true;
                    case MENU_RESTART_TAB:
                        restartCurrentSession();
                        return true;
                    case MENU_TOGGLE_KEYBOARD:
                        toggleKeyboard();
                        return true;
                    case MENU_PASTE:
                        pasteClipboard();
                        return true;
                    case MENU_SETTINGS:
                        startActivity(new Intent(TerminalActivity.this, SettingsActivity.class));
                        return true;
                    case MENU_ABOUT:
                        showAbout();
                        return true;
                }
                return false;
            }
        });

        popup.show();
    }

    private void toggleKeyboard() {
        InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        if (imm != null) {
            imm.toggleSoftInput(InputMethodManager.SHOW_FORCED, 0);
        }
    }

    private void pasteClipboard() {
        TerminalSession session = getActiveSession();
        if (session == null) {
            return;
        }

        ClipboardManager clipboard = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        if (clipboard != null && clipboard.hasPrimaryClip()) {
            ClipData clipData = clipboard.getPrimaryClip();
            if (clipData != null && clipData.getItemCount() > 0) {
                CharSequence text = clipData.getItemAt(0).coerceToText(this);
                if (text != null) {
                    session.emulator.sendText(text.toString());
                }
            }
        }
    }

    private void showAbout() {
        Toast.makeText(this,
                "BlackTerm v1.0.0\nTerminal for BlackBerry Passport\ngithub.com/QNXcraft/BlackTerm",
                Toast.LENGTH_LONG).show();
    }

    private void applyPreferences(SharedPreferences prefs) {
        boolean keepScreenOn = prefs.getBoolean("keep_screen_on", true);
        if (keepScreenOn) {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        } else {
            getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        }

        for (int i = 0; i < sessions.size(); i++) {
            TerminalSession session = sessions.get(i);
            session.emulator.setPreferredShell(prefs.getString("shell_command", "auto"));
            applyPreferencesToView(session.view, prefs);
        }
    }

    private void applyPreferencesToView(TerminalView view, SharedPreferences prefs) {
        String bgColor = prefs.getString("bg_color", "#000000");
        try {
            view.setTerminalBackgroundColor(Color.parseColor(bgColor));
        } catch (IllegalArgumentException e) {
            view.setTerminalBackgroundColor(Color.BLACK);
        }

        String fgColor = prefs.getString("fg_color", "#00FF00");
        try {
            view.setTerminalForegroundColor(Color.parseColor(fgColor));
        } catch (IllegalArgumentException e) {
            view.setTerminalForegroundColor(Color.GREEN);
        }

        view.setTerminalFontSize(parseIntPreference("font_size", 14));

        String fontFamily = prefs.getString("font_family", "monospace");
        view.setTerminalFont(fontFamily);
        view.setCtrlState(ctrlPressed);
        view.setShiftState(shiftPressed);
        view.setCapsLockState(capsLockOn);
    }

    @Override
    public void onSharedPreferenceChanged(SharedPreferences prefs, String key) {
        applyPreferences(prefs);
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        TerminalSession session = getActiveSession();
        if (session != null && session.view.handleKeyEvent(keyCode, event)) {
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override
    protected void onResume() {
        super.onResume();
        applyPreferences(PreferenceManager.getDefaultSharedPreferences(this));
        TerminalSession session = getActiveSession();
        if (session != null) {
            session.view.requestFocus();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        PreferenceManager.getDefaultSharedPreferences(this)
                .unregisterOnSharedPreferenceChangeListener(this);
        for (int i = 0; i < sessions.size(); i++) {
            sessions.get(i).emulator.stop();
        }
    }

    private int parseIntPreference(String key, int defaultValue) {
        try {
            return Integer.parseInt(prefs.getString(key, String.valueOf(defaultValue)));
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    private int dpToPx(int dp) {
        float density = getResources().getDisplayMetrics().density;
        return Math.round(dp * density);
    }
}
