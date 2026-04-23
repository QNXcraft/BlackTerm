package com.qnxcraft.blackterm.terminal;

import android.content.ClipData;
import android.content.ClipboardManager;
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

    public interface OnPasteRequestedListener {
        void onPasteRequested();
    }

    private TerminalEmulator emulator;
    private Paint textPaint;
    private Paint cursorPaint;
    private Paint bgPaint;
    private Paint selectionPaint;

    private float charWidth;
    private float charHeight;
    private float charDescent;

    private int termBgColor = Color.BLACK;
    private int termFgColor = Color.GREEN;

    private boolean shiftState = false;
    private boolean capsLockState = false;
    private boolean ctrlState = false;
    private OnPasteRequestedListener pasteRequestedListener;

    private GestureDetector gestureDetector;
    private Handler blinkHandler = new Handler();
    private boolean cursorBlinkVisible = true;
    private static final long BLINK_INTERVAL = 500;
    private int viewportTopRow = 0;
    private boolean selectionActive = false;
    private int selectionStartRow = -1;
    private int selectionStartCol = -1;
    private int selectionEndRow = -1;
    private int selectionEndCol = -1;

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

        selectionPaint = new Paint();
        selectionPaint.setColor(Color.argb(160, 80, 120, 255));

        updateCharMetrics();

        gestureDetector = new GestureDetector(context, new GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onSingleTapUp(MotionEvent e) {
                if (selectionActive) {
                    clearSelection();
                    return true;
                }
                requestFocus();
                showKeyboard();
                return true;
            }

            @Override
            public boolean onDoubleTap(MotionEvent e) {
                if (selectionActive) {
                    copySelectionToClipboard();
                } else if (pasteRequestedListener != null) {
                    pasteRequestedListener.onPasteRequested();
                }
                return true;
            }

            @Override
            public void onLongPress(MotionEvent e) {
                beginSelection(e.getX(), e.getY());
            }

            @Override
            public boolean onScroll(MotionEvent e1, MotionEvent e2, float distanceX, float distanceY) {
                if (selectionActive) {
                    updateSelection(e2.getX(), e2.getY());
                    return true;
                }

                int deltaRows = Math.round(distanceY / charHeight);
                if (deltaRows != 0) {
                    scrollViewportBy(deltaRows);
                    return true;
                }
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

        int[] fgColors = emulator.getFgColors();
        int[] bgColors = emulator.getBgColors();
        int rows = emulator.getRows();
        int cols = emulator.getColumns();
        int scrollbackCount = emulator.getScrollbackCount();

        for (int row = 0; row < rows; row++) {
            float y = row * charHeight;
            int absoluteRow = viewportTopRow + row;
            char[] line = emulator.getTranscriptLine(absoluteRow);
            boolean usingScreenRow = absoluteRow >= scrollbackCount;
            int screenRow = absoluteRow - scrollbackCount;

            for (int col = 0; col < cols; col++) {
                float x = col * charWidth;
                int idx = usingScreenRow && screenRow >= 0 && screenRow < rows
                        ? screenRow * cols + col
                        : -1;

                // Draw cell background if different from terminal bg
                int cellBg = idx >= 0 ? bgColors[idx] : termBgColor;
                if (cellBg != termBgColor) {
                    bgPaint.setColor(cellBg);
                    canvas.drawRect(x, y, x + charWidth, y + charHeight, bgPaint);
                }

                if (isCellSelected(absoluteRow, col)) {
                    canvas.drawRect(x, y, x + charWidth, y + charHeight, selectionPaint);
                }

                // Draw character
                char c = (line != null && col < line.length) ? line[col] : ' ';
                if (c != ' ' && c != 0) {
                    textPaint.setColor(idx >= 0 ? fgColors[idx] : termFgColor);
                    canvas.drawText(String.valueOf(c), x, y + charHeight - charDescent, textPaint);
                }
            }
        }

        // Draw cursor
        if (emulator.isCursorVisible() && cursorBlinkVisible && isFollowingBottom()) {
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

        if (selectionActive) {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_MOVE:
                    updateSelection(event.getX(), event.getY());
                    break;
                case MotionEvent.ACTION_UP:
                    copySelectionToClipboard();
                    break;
            }
        }

        return true;
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (handleKeyEvent(keyCode, event)) {
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override
    public boolean onKeyUp(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_ENTER ||
                keyCode == KeyEvent.KEYCODE_DEL ||
                keyCode == KeyEvent.KEYCODE_TAB ||
                keyCode == KeyEvent.KEYCODE_CTRL_LEFT ||
                keyCode == KeyEvent.KEYCODE_CTRL_RIGHT ||
                keyCode == KeyEvent.KEYCODE_DPAD_UP ||
                keyCode == KeyEvent.KEYCODE_DPAD_DOWN ||
                keyCode == KeyEvent.KEYCODE_DPAD_LEFT ||
                keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) {
            return true;
        }
        return super.onKeyUp(keyCode, event);
    }

    @Override
    public InputConnection onCreateInputConnection(EditorInfo outAttrs) {
        outAttrs.inputType = InputType.TYPE_NULL;
        outAttrs.imeOptions = EditorInfo.IME_FLAG_NO_FULLSCREEN;
        return new BaseInputConnection(this, false) {
            @Override
            public boolean commitText(CharSequence text, int newCursorPosition) {
                String t = text.toString();
                if (ctrlState && t.length() == 1) {
                    char ctrlChar = t.charAt(0);
                    if (ctrlChar == 'v' || ctrlChar == 'V') {
                        if (pasteRequestedListener != null) {
                            pasteRequestedListener.onPasteRequested();
                        }
                        return true;
                    }
                    if (Character.isLetter(ctrlChar)) {
                        emulator.sendControlKey(ctrlChar);
                        return true;
                    }
                }
                if (capsLockState || shiftState) {
                    t = t.toUpperCase();
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

        if (keyCode == KeyEvent.KEYCODE_CTRL_LEFT || keyCode == KeyEvent.KEYCODE_CTRL_RIGHT) {
            return true;
        }

        boolean ctrlActive = ctrlState || event.isCtrlPressed();
        if (ctrlActive && event.getAction() == KeyEvent.ACTION_DOWN) {
            if (keyCode == KeyEvent.KEYCODE_V) {
                if (pasteRequestedListener != null) {
                    pasteRequestedListener.onPasteRequested();
                }
                return true;
            }
            if (keyCode >= KeyEvent.KEYCODE_A && keyCode <= KeyEvent.KEYCODE_Z) {
                char ctrlChar = (char) ('A' + (keyCode - KeyEvent.KEYCODE_A));
                emulator.sendControlKey(ctrlChar);
                return true;
            }
            if (keyCode == KeyEvent.KEYCODE_SPACE) {
                emulator.sendText(String.valueOf((char) 0));
                return true;
            }
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

    public void setCtrlState(boolean ctrl) {
        this.ctrlState = ctrl;
    }

    public void setOnPasteRequestedListener(OnPasteRequestedListener listener) {
        this.pasteRequestedListener = listener;
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
        if (isFollowingBottom()) {
            scrollToBottom();
        }
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

    private void scrollViewportBy(int deltaRows) {
        int maxTop = Math.max(0, emulator.getTranscriptLineCount() - emulator.getRows());
        viewportTopRow = Math.max(0, Math.min(maxTop, viewportTopRow + deltaRows));
        invalidate();
    }

    private void scrollToBottom() {
        viewportTopRow = Math.max(0, emulator.getTranscriptLineCount() - emulator.getRows());
    }

    private boolean isFollowingBottom() {
        return viewportTopRow >= Math.max(0, emulator.getTranscriptLineCount() - emulator.getRows());
    }

    private void beginSelection(float touchX, float touchY) {
        int row = clampAbsoluteRow(viewportTopRow + (int) (touchY / charHeight));
        int col = clampColumn((int) (touchX / charWidth));
        selectionActive = true;
        selectionStartRow = row;
        selectionStartCol = col;
        selectionEndRow = row;
        selectionEndCol = col;
        invalidate();
    }

    private void updateSelection(float touchX, float touchY) {
        if (!selectionActive) {
            return;
        }
        selectionEndRow = clampAbsoluteRow(viewportTopRow + (int) (touchY / charHeight));
        selectionEndCol = clampColumn((int) (touchX / charWidth));
        invalidate();
    }

    private void clearSelection() {
        selectionActive = false;
        selectionStartRow = -1;
        selectionStartCol = -1;
        selectionEndRow = -1;
        selectionEndCol = -1;
        invalidate();
    }

    private boolean isCellSelected(int row, int col) {
        if (!selectionActive) {
            return false;
        }

        int startRow = selectionStartRow;
        int startCol = selectionStartCol;
        int endRow = selectionEndRow;
        int endCol = selectionEndCol;

        if (startRow > endRow || (startRow == endRow && startCol > endCol)) {
            startRow = selectionEndRow;
            startCol = selectionEndCol;
            endRow = selectionStartRow;
            endCol = selectionStartCol;
        }

        if (row < startRow || row > endRow) {
            return false;
        }
        if (startRow == endRow) {
            return col >= startCol && col <= endCol;
        }
        if (row == startRow) {
            return col >= startCol;
        }
        if (row == endRow) {
            return col <= endCol;
        }
        return true;
    }

    private void copySelectionToClipboard() {
        if (!selectionActive) {
            return;
        }

        String selectedText = buildSelectedText();
        if (selectedText.length() == 0) {
            return;
        }

        ClipboardManager clipboard = (ClipboardManager) getContext().getSystemService(Context.CLIPBOARD_SERVICE);
        if (clipboard != null) {
            clipboard.setPrimaryClip(ClipData.newPlainText("terminal-selection", selectedText));
        }
    }

    private String buildSelectedText() {
        if (!selectionActive) {
            return "";
        }

        int startRow = selectionStartRow;
        int startCol = selectionStartCol;
        int endRow = selectionEndRow;
        int endCol = selectionEndCol;

        if (startRow > endRow || (startRow == endRow && startCol > endCol)) {
            startRow = selectionEndRow;
            startCol = selectionEndCol;
            endRow = selectionStartRow;
            endCol = selectionStartCol;
        }

        StringBuilder builder = new StringBuilder();
        for (int row = startRow; row <= endRow; row++) {
            char[] line = emulator.getTranscriptLine(row);
            if (line == null) {
                continue;
            }

            int fromCol = (row == startRow) ? startCol : 0;
            int toCol = (row == endRow) ? endCol : emulator.getColumns() - 1;
            fromCol = Math.max(0, Math.min(fromCol, line.length));
            toCol = Math.max(0, Math.min(toCol, line.length - 1));
            if (toCol >= fromCol) {
                int actualEnd = toCol;
                while (actualEnd >= fromCol && line[actualEnd] == ' ') {
                    actualEnd--;
                }
                if (actualEnd >= fromCol) {
                    builder.append(line, fromCol, actualEnd - fromCol + 1);
                }
            }
            if (row < endRow) {
                builder.append('\n');
            }
        }
        return builder.toString();
    }

    private int clampAbsoluteRow(int row) {
        return Math.max(0, Math.min(row, emulator.getTranscriptLineCount() - 1));
    }

    private int clampColumn(int col) {
        return Math.max(0, Math.min(col, emulator.getColumns() - 1));
    }
}
