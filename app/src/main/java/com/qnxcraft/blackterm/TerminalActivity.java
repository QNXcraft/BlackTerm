package com.qnxcraft.blackterm;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.preference.PreferenceManager;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.qnxcraft.blackterm.terminal.TerminalEmulator;
import com.qnxcraft.blackterm.terminal.TerminalView;

public class TerminalActivity extends Activity implements SharedPreferences.OnSharedPreferenceChangeListener {

    private TerminalView terminalView;
    private TerminalEmulator terminalEmulator;
    private LinearLayout rootLayout;
    private LinearLayout buttonBar;
    private Button burgerButton;
    private boolean shiftPressed = false;
    private boolean capsLockOn = false;
    private Button shiftButton;
    private Button capsButton;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
        prefs.registerOnSharedPreferenceChangeListener(this);

        rootLayout = new LinearLayout(this);
        rootLayout.setOrientation(LinearLayout.VERTICAL);
        rootLayout.setBackgroundColor(Color.BLACK);

        terminalEmulator = new TerminalEmulator(80, 24);
        terminalView = new TerminalView(this, terminalEmulator);
        terminalView.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1.0f));

        rootLayout.addView(terminalView);

        buttonBar = createButtonBar();
        rootLayout.addView(buttonBar);

        setContentView(rootLayout);

        applyPreferences(prefs);

        terminalEmulator.start();
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

        // Burger menu button
        burgerButton = createBarButton("\u2630", btnParams);
        burgerButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showBurgerMenu(v);
            }
        });
        bar.addView(burgerButton);

        // Tab button
        Button tabButton = createBarButton("TAB", btnParams);
        tabButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                terminalEmulator.sendKeyCode(KeyEvent.KEYCODE_TAB);
            }
        });
        bar.addView(tabButton);

        // Escape button
        Button escButton = createBarButton("ESC", btnParams);
        escButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                terminalEmulator.sendKeyCode(KeyEvent.KEYCODE_ESCAPE);
            }
        });
        bar.addView(escButton);

        // Shift button
        shiftButton = createBarButton("SHIFT", btnParams);
        shiftButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                shiftPressed = !shiftPressed;
                updateModifierButtons();
                terminalView.setShiftState(shiftPressed);
            }
        });
        bar.addView(shiftButton);

        // Caps Lock button
        capsButton = createBarButton("CAPS", btnParams);
        capsButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                capsLockOn = !capsLockOn;
                updateModifierButtons();
                terminalView.setCapsLockState(capsLockOn);
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
        return btn;
    }

    private void updateModifierButtons() {
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

    private void showBurgerMenu(View anchor) {
        PopupMenu popup = new PopupMenu(this, anchor);
        popup.getMenu().add(0, 1, 0, "New Session");
        popup.getMenu().add(0, 2, 1, "Toggle Keyboard");
        popup.getMenu().add(0, 3, 2, "Paste");
        popup.getMenu().add(0, 4, 3, "Settings");
        popup.getMenu().add(0, 5, 4, "About");

        popup.setOnMenuItemClickListener(new PopupMenu.OnMenuItemClickListener() {
            @Override
            public boolean onMenuItemClick(MenuItem item) {
                switch (item.getItemId()) {
                    case 1:
                        terminalEmulator.reset();
                        terminalEmulator.start();
                        return true;
                    case 2:
                        toggleKeyboard();
                        return true;
                    case 3:
                        pasteClipboard();
                        return true;
                    case 4:
                        startActivity(new Intent(TerminalActivity.this, SettingsActivity.class));
                        return true;
                    case 5:
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

    @SuppressWarnings("deprecation")
    private void pasteClipboard() {
        android.text.ClipboardManager clipboard =
                (android.text.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        if (clipboard != null && clipboard.hasText()) {
            CharSequence text = clipboard.getText();
            if (text != null) {
                terminalEmulator.sendText(text.toString());
            }
        }
    }

    private void showAbout() {
        Toast.makeText(this,
                "BlackTerm v1.0.0\nTerminal for BlackBerry Passport\ngithub.com/QNXcraft/BlackTerm",
                Toast.LENGTH_LONG).show();
    }

    private void applyPreferences(SharedPreferences prefs) {
        // Background color
        String bgColor = prefs.getString("bg_color", "#000000");
        try {
            terminalView.setTerminalBackgroundColor(Color.parseColor(bgColor));
        } catch (IllegalArgumentException e) {
            terminalView.setTerminalBackgroundColor(Color.BLACK);
        }

        // Foreground color
        String fgColor = prefs.getString("fg_color", "#00FF00");
        try {
            terminalView.setTerminalForegroundColor(Color.parseColor(fgColor));
        } catch (IllegalArgumentException e) {
            terminalView.setTerminalForegroundColor(Color.GREEN);
        }

        // Font size
        int fontSize = Integer.parseInt(prefs.getString("font_size", "14"));
        terminalView.setTerminalFontSize(fontSize);

        // Font family
        String fontFamily = prefs.getString("font_family", "monospace");
        terminalView.setTerminalFont(fontFamily);
    }

    @Override
    public void onSharedPreferenceChanged(SharedPreferences prefs, String key) {
        applyPreferences(prefs);
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (terminalView.handleKeyEvent(keyCode, event)) {
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override
    protected void onResume() {
        super.onResume();
        applyPreferences(PreferenceManager.getDefaultSharedPreferences(this));
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        PreferenceManager.getDefaultSharedPreferences(this)
                .unregisterOnSharedPreferenceChangeListener(this);
        if (terminalEmulator != null) {
            terminalEmulator.stop();
        }
    }

    private int dpToPx(int dp) {
        float density = getResources().getDisplayMetrics().density;
        return Math.round(dp * density);
    }
}
