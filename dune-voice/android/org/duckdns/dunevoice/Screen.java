package org.duckdns.dunevoice;

import android.app.Activity;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.*;
import android.text.InputType;
import android.view.*;
import android.widget.*;

/**
 * Tiny code-only UI kit shared by the TV and phone apps (no AndroidX, no XML
 * layouts). Dark theme, rounded cards, and a clear focus ring on every
 * control so the TV screen is usable with a D-pad from across the room.
 */
public final class Screen {
    public static final int BG = Color.rgb(13, 18, 30), CARD = Color.rgb(24, 32, 50), RAISED = Color.rgb(36, 46, 70),
        TEXT = Color.rgb(236, 240, 247), MUTED = Color.rgb(150, 162, 186), ACCENT = Color.rgb(79, 179, 224),
        OK = Color.rgb(76, 195, 138), WARN = Color.rgb(242, 184, 75), BAD = Color.rgb(229, 100, 107);
    public final Activity activity;
    public final boolean tv;
    public final ScrollView scroll;
    public final Box root;

    public Screen(Activity value, boolean tv) {
        activity = value; this.tv = tv;
        Window window = value.getWindow();
        window.setStatusBarColor(BG); window.setNavigationBarColor(BG);
        scroll = new ScrollView(value); scroll.setFillViewport(true); scroll.setBackgroundColor(BG);
        LinearLayout column = new LinearLayout(value); column.setOrientation(LinearLayout.VERTICAL);
        int side = dp(tv ? 56 : 18), top = dp(tv ? 36 : 22);
        column.setPadding(side, top, side, top);
        scroll.addView(column); value.setContentView(scroll);
        root = new Box(this, column);
    }
    public int dp(float value) { return Math.round(value * activity.getResources().getDisplayMetrics().density); }
    public float sp(float value) { return tv ? value * 1.15f : value; }

    static GradientDrawable shape(int color, float radius, int stroke, int strokeColor) {
        GradientDrawable d = new GradientDrawable(); d.setColor(color); d.setCornerRadius(radius);
        if (stroke > 0) d.setStroke(stroke, strokeColor);
        return d;
    }
    /** Background with distinct focused / pressed / disabled looks. */
    Drawable states(int normal, int focused, float radius) {
        StateListDrawable list = new StateListDrawable();
        list.addState(new int[]{-android.R.attr.state_enabled}, shape(blend(normal, BG, 0.5f), radius, 0, 0));
        list.addState(new int[]{android.R.attr.state_pressed}, shape(blend(focused, Color.BLACK, 0.2f), radius, 0, 0));
        list.addState(new int[]{android.R.attr.state_focused}, shape(focused, radius, dp(3), Color.WHITE));
        list.addState(new int[]{}, shape(normal, radius, 0, 0));
        return list;
    }
    static int blend(int a, int b, float t) {
        return Color.rgb(Math.round(Color.red(a) * (1 - t) + Color.red(b) * t), Math.round(Color.green(a) * (1 - t) + Color.green(b) * t), Math.round(Color.blue(a) * (1 - t) + Color.blue(b) * t));
    }

    /** A vertical (or horizontal) group of views. Cards and rows are boxes too. */
    public static final class Box {
        public final Screen screen;
        public final LinearLayout view;
        Box(Screen screen, LinearLayout view) { this.screen = screen; this.view = view; }
        private LinearLayout.LayoutParams params(int height, int topMargin) {
            boolean row = view.getOrientation() == LinearLayout.HORIZONTAL;
            LinearLayout.LayoutParams p = row ? new LinearLayout.LayoutParams(0, height, 1) : new LinearLayout.LayoutParams(-1, height);
            if (row) { p.leftMargin = screen.dp(5); p.rightMargin = screen.dp(5); } else p.topMargin = topMargin;
            return p;
        }
        public <T extends View> T add(T child, int height, int topMargin) { view.addView(child, params(height, topMargin)); return child; }
        public TextView label(String value, float size, int color, boolean bold) {
            TextView t = new TextView(screen.activity); t.setText(value); t.setTextSize(screen.sp(size)); t.setTextColor(color);
            t.setLineSpacing(0, 1.15f); if (bold) t.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
            return add(t, -2, screen.dp(6));
        }
        public TextView title(String value) { TextView t = label(value, 28, TEXT, true); t.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD)); return t; }
        public TextView heading(String value) { return label(value, 19, TEXT, true); }
        public TextView text(String value) { return label(value, 16, TEXT, false); }
        public TextView muted(String value) { return label(value, 14, MUTED, false); }
        /** Small uppercase caption above a group. */
        public TextView caption(String value) {
            TextView t = label(value.toUpperCase(java.util.Locale.ROOT), 12, MUTED, true); t.setLetterSpacing(0.08f);
            ((LinearLayout.LayoutParams) t.getLayoutParams()).topMargin = view.getChildCount() == 1 ? screen.dp(2) : screen.dp(22); return t;
        }
        public Box card() {
            LinearLayout c = new LinearLayout(screen.activity); c.setOrientation(LinearLayout.VERTICAL);
            int p = screen.dp(screen.tv ? 22 : 18); c.setPadding(p, screen.dp(screen.tv ? 16 : 12), p, p);
            c.setBackground(shape(CARD, screen.dp(20), 0, 0));
            add(c, -2, screen.dp(12)); return new Box(screen, c);
        }
        public Box row() {
            LinearLayout r = new LinearLayout(screen.activity); r.setOrientation(LinearLayout.HORIZONTAL);
            r.setBaselineAligned(false);
            LinearLayout.LayoutParams p = params(-2, screen.dp(10)); p.leftMargin = -screen.dp(5); p.rightMargin = -screen.dp(5);
            view.addView(r, p); return new Box(screen, r);
        }
        public Box column() {
            LinearLayout c = new LinearLayout(screen.activity); c.setOrientation(LinearLayout.VERTICAL);
            add(c, -2, 0); return new Box(screen, c);
        }
        private Button button(String label, View.OnClickListener action, int normal, int focused, int textColor) {
            Button b = new Button(screen.activity); b.setText(label); b.setAllCaps(false); b.setTextSize(screen.sp(16));
            b.setTextColor(textColor); b.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
            b.setBackground(screen.states(normal, focused, screen.dp(14))); b.setStateListAnimator(null);
            b.setPadding(screen.dp(14), screen.dp(10), screen.dp(14), screen.dp(10));
            b.setOnClickListener(action); b.setMinHeight(screen.dp(screen.tv ? 60 : 52)); b.setFocusable(true);
            if (view.getOrientation() == LinearLayout.HORIZONTAL) { b.setSingleLine(true); b.setPadding(screen.dp(6), 0, screen.dp(6), 0); }
            return add(b, -2, screen.dp(10));
        }
        public Button primary(String label, View.OnClickListener action) { return button(label, action, ACCENT, blend(ACCENT, Color.WHITE, 0.25f), Color.rgb(8, 20, 30)); }
        public Button secondary(String label, View.OnClickListener action) { return button(label, action, RAISED, blend(RAISED, ACCENT, 0.45f), TEXT); }
        public Button quiet(String label, View.OnClickListener action) {
            Button b = button(label, action, CARD, blend(CARD, ACCENT, 0.35f), ACCENT); b.setMinHeight(screen.dp(44)); return b;
        }
        public Status status(String text) { return new Status(this, text); }
        public EditText field(String hint, String initial, int inputType) {
            EditText f = new EditText(screen.activity); f.setHint(hint); f.setText(initial);
            f.setSingleLine(true); f.setTextSize(screen.sp(18)); f.setInputType(inputType);
            f.setTextColor(TEXT); f.setHintTextColor(MUTED);
            f.setBackground(screen.states(RAISED, blend(RAISED, ACCENT, 0.2f), screen.dp(14)));
            f.setPadding(screen.dp(16), 0, screen.dp(16), 0);
            return add(f, screen.dp(56), screen.dp(10));
        }
        public Box visible(boolean value) { view.setVisibility(value ? View.VISIBLE : View.GONE); return this; }
    }

    /** Coloured dot + line of text, e.g. "● Телефон подключён". */
    public static final class Status {
        public final TextView view;
        private final Screen screen;
        Status(Box box, String text) {
            screen = box.screen; view = box.label(text, 16, TEXT, false);
            view.setCompoundDrawablePadding(screen.dp(12)); view.setGravity(Gravity.CENTER_VERTICAL);
            set(MUTED, text);
        }
        public void set(int color, String text) {
            GradientDrawable dot = new GradientDrawable(); dot.setShape(GradientDrawable.OVAL); dot.setColor(color);
            int size = screen.dp(12); dot.setSize(size, size);
            view.setCompoundDrawablesRelativeWithIntrinsicBounds(dot, null, null, null); view.setText(text);
        }
    }

    /** Large round toggle used for the phone's microphone switch. */
    public static Button roundToggle(Box box, Drawable icon, View.OnClickListener action) {
        Screen s = box.screen;
        Button b = new Button(s.activity); b.setOnClickListener(action); b.setStateListAnimator(null);
        int size = s.dp(132);
        icon.setBounds(0, 0, s.dp(56), s.dp(56)); b.setCompoundDrawables(null, icon, null, null);
        b.setPadding(0, (size - s.dp(56)) / 2, 0, 0);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(size, size); p.gravity = Gravity.CENTER_HORIZONTAL; p.topMargin = s.dp(8);
        box.view.addView(b, p); return b;
    }
    public static void paintToggle(Screen s, Button b, boolean on) {
        StateListDrawable list = new StateListDrawable();
        int base = on ? ACCENT : RAISED;
        GradientDrawable pressed = new GradientDrawable(); pressed.setShape(GradientDrawable.OVAL); pressed.setColor(blend(base, Color.BLACK, 0.2f));
        GradientDrawable normal = new GradientDrawable(); normal.setShape(GradientDrawable.OVAL); normal.setColor(base);
        if (on) normal.setStroke(s.dp(8), blend(ACCENT, BG, 0.55f));
        list.addState(new int[]{android.R.attr.state_pressed}, pressed); list.addState(new int[]{}, normal);
        b.setBackground(list);
        b.getCompoundDrawables()[1].setTint(on ? Color.rgb(8, 20, 30) : TEXT);
    }
    public static final int TEXT_INPUT = InputType.TYPE_CLASS_TEXT;
    public static ColorStateList tint(int color) { return ColorStateList.valueOf(color); }
}
