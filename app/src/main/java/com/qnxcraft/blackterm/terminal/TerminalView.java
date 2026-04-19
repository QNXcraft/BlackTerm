package com.qnxcraft.blackterm.terminal;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.os.Handler;
import android.text.InputType;
import android.view.GestureDetector;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.inputmethod.BaseInputConnection;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;
import android.view.inputmethod.InputMethodManager;

/**
 * Custom View that renders the terminal emulator screen.
 * Optimized for BlackBerry Passport's 1440x1440 square display.
 */
public class TerminalView extends View implements TerminalEmulator.TerminalListener {

    private TerminalEmulator emulator;
    private Paint textPaint;
    private Paint cursorPaint;
    private Paint bgPaint;

    private float charWidth;
    private float charHeight;
    private float charDescent;

    private int termBgColor = Color.BLACK;
    private int termFgColor = Color.GREEN;

    private boolean shiftState = false;
    private boolean capsLockState = false;

    private GestureDetector gestureDetector;
    private Handler blinkHandler = new Handler();
    private boolean cursorBlinkVisible = true;
    private static final long BLINK_INTERVAL = 500;

    private Runnable blinkRunnable = new Runnable() {
        @Override
        public void run() {
            cursorBlinkVisible = !cursorBlinkVisible;
            invalidate();
            blinkHandler.postDelayed(this, BLINK_INTERVAL);
        }
    };

    public TerminalView(Context context, TerminalEmulator emulator) {
        super(context);
        this.emulator = emulator;
        emulator.setListener(this);

        setFocusable(true);
        setFocusableInTouchMode(true);

        textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        textPaint.setTypeface(Typeface.MONOSPACE);
        textPaint.setTextSize(14 * getResources().getDisplayMetrics().scaledDensity);
        textPaint.setColor(termFgColor);

        cursorPaint = new Paint();
        cursorPaint.setColor(termFgColor);
        cursorPaint.setAlpha(180);

        bgPaint = new Paint();
        bgPaint.setColor(termBgColor);

        updateCharMetrics();

        gestureDetector = new GestureDetector(context, new GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onSingleTapUp(MotionEvent e) {
                requestFocus();
                showKeyboard();
                return true;
            }

            @Override
            public boolean onDoubleTap(MotionEvent e) {
                // Double tap to paste
                return true;
            }
        });

        blinkHandler.postDelayed(blinkRunnable, BLINK_INTERVAL);
    }

    private void updateCharMetrics() {
        Rect bounds = new Rect();
        textPaint.getTextBounds("M", 0, 1, bounds);
        charWidth = textPaint.measureText("M");
        charHeight = textPaint.getTextSize() * 1.2f;
        charDescent = textPaint.descent();
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        if (charWidth > 0 && charHeight > 0) {
            int newCols = (int) (w / charWidth);
            int newRows = (int) (h / charHeight);
            if (newCols > 0 && newRows > 0) {
                emulator.resize(newCols, newRows);
            }
        }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);

        // Draw background
        canvas.drawColor(termBgColor);

        char[][] screen = emulator.getScreen();
        int[] fgColors = emulator.getFgColors();
        int[] bgColors = emulator.getBgColors();
        int rows = emulator.getRows();
        int cols = emulator.getColumns();

        for (int row = 0; row < rows; row++) {
            float y = row * charHeight;

            for (int col = 0; col < cols; col++) {
                float x = col * charWidth;
                int idx = row * cols + col;

                // Draw cell background if different from terminal bg
                int cellBg = bgColors[idx];
                if (cellBg != termBgColor) {
                    bgPaint.setColor(cellBg);
                    canvas.drawRect(x, y, x + charWidth, y + charHeight, bgPaint);
                }

                // Draw character
                char c = screen[row][col];
                if (c != ' ' && c != 0) {
                    textPaint.setColor(fgColors[idx]);
                    canvas.drawText(String.valueOf(c), x, y + charHeight - charDescent, textPaint);
                }
            }
        }

        // Draw cursor
        if (emulator.isCursorVisible() && cursorBlinkVisible) {
            int curRow = emulator.getCursorRow();
            int curCol = emulator.getCursorCol();
            float cx = curCol * charWidth;
            float cy = curRow * charHeight;
            cursorPaint.setColor(termFgColor);
            cursorPaint.setAlpha(180);
            canvas.drawRect(cx, cy, cx + charWidth, cy + charHeight, cursorPaint);
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        gestureDetector.onTouchEvent(event);
        return true;
    }

    @Override
    public InputConnection onCreateInputConnection(EditorInfo outAttrs) {
        outAttrs.inputType = InputType.TYPE_NULL;
        outAttrs.imeOptions = EditorInfo.IME_FLAG_NO_FULLSCREEN;
        return new BaseInputConnection(this, false) {
            @Override
            public boolean commitText(CharSequence text, int newCursorPosition) {
                String t = text.toString();
                if (capsLockState || shiftState) {
                    t = t.toUpperCase();
                    if (shiftState) {
                        shiftState = false;
                    }
                }
                emulator.sendText(t);
                return true;
            }

            @Override
            public boolean sendKeyEvent(KeyEvent event) {
                if (event.getAction() == KeyEvent.ACTION_DOWN) {
                    handleKeyEvent(event.getKeyCode(), event);
                }
                return true;
            }

            @Override
            public boolean deleteSurroundingText(int beforeLength, int afterLength) {
                if (beforeLength > 0) {
                    emulator.sendText("\177");
                }
                return true;
            }
        };
    }

    @Override
    public boolean onCheckIsTextEditor() {
        return true;
    }

    public boolean handleKeyEvent(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            return false;
        }

        switch (keyCode) {
            case KeyEvent.KEYCODE_ENTER:
                emulator.sendKeyCode(KeyEvent.KEYCODE_ENTER);
                return true;
            case KeyEvent.KEYCODE_DEL:
                emulator.sendKeyCode(KeyEvent.KEYCODE_DEL);
                return true;
            case KeyEvent.KEYCODE_TAB:
                emulator.sendKeyCode(KeyEvent.KEYCODE_TAB);
                return true;
            case KeyEvent.KEYCODE_DPAD_UP:
                emulator.sendKeyCode(KeyEvent.KEYCODE_DPAD_UP);
                return true;
            case KeyEvent.KEYCODE_DPAD_DOWN:
                emulator.sendKeyCode(KeyEvent.KEYCODE_DPAD_DOWN);
                return true;
            case KeyEvent.KEYCODE_DPAD_LEFT:
                emulator.sendKeyCode(KeyEvent.KEYCODE_DPAD_LEFT);
                return true;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                emulator.sendKeyCode(KeyEvent.KEYCODE_DPAD_RIGHT);
                return true;
            default:
                int unicodeChar = event.getUnicodeChar(
                        (shiftState || capsLockState) ? KeyEvent.META_SHIFT_ON : 0);
                if (unicodeChar != 0) {
                    String ch = String.valueOf((char) unicodeChar);
                    emulator.sendText(ch);
                    if (shiftState) {
                        shiftState = false;
                    }
                    return true;
                }
        }
        return false;
    }

    public void setShiftState(boolean shift) {
        this.shiftState = shift;
    }

    public void setCapsLockState(boolean capsLock) {
        this.capsLockState = capsLock;
    }

    public void setTerminalBackgroundColor(int color) {
        this.termBgColor = color;
        invalidate();
    }

    public void setTerminalForegroundColor(int color) {
        this.termFgColor = color;
        textPaint.setColor(color);
        cursorPaint.setColor(color);
        invalidate();
    }

    public void setTerminalFontSize(int sp) {
        textPaint.setTextSize(sp * getResources().getDisplayMetrics().scaledDensity);
        updateCharMetrics();
        // Recalculate terminal size
        if (getWidth() > 0 && getHeight() > 0) {
            int newCols = (int) (getWidth() / charWidth);
            int newRows = (int) (getHeight() / charHeight);
            if (newCols > 0 && newRows > 0) {
                emulator.resize(newCols, newRows);
            }
        }
        invalidate();
    }

    public void setTerminalFont(String fontFamily) {
        Typeface tf;
        switch (fontFamily) {
            case "serif":
                tf = Typeface.create(Typeface.SERIF, Typeface.NORMAL);
                break;
            case "sans-serif":
                tf = Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL);
                break;
            default:
                tf = Typeface.MONOSPACE;
                break;
        }
        textPaint.setTypeface(tf);
        updateCharMetrics();
        invalidate();
    }

    @Override
    public void onScreenUpdate() {
        invalidate();
    }

    @Override
    public void onTitleChanged(String title) {
        // Can be used to update activity title
    }

    private void showKeyboard() {
        InputMethodManager imm = (InputMethodManager) getContext().getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) {
            imm.showSoftInput(this, InputMethodManager.SHOW_IMPLICIT);
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        blinkHandler.removeCallbacks(blinkRunnable);
    }
}
