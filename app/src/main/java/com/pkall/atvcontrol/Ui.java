package com.pkall.atvcontrol;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/** Small helpers to build a D-pad friendly TV UI in code (no extra libraries needed). */
final class Ui {

    static final int ACCENT = 0xFFB98CFF;     // lilac, matches the wallpaper
    static final int OK_GREEN = 0xFF6EE7A0;
    static final int ERROR_RED = 0xFFFF7A85;
    static final int WAIT_AMBER = 0xFFFFD27A;

    private Ui() {
    }

    static int dp(Context c, float v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, c.getResources().getDisplayMetrics());
    }

    /** Wallpaper + dark overlay + two scrollable columns. Returns {left, right}. */
    static LinearLayout[] screen(Activity a) {
        FrameLayout root = new FrameLayout(a);
        ImageView bg = new ImageView(a);
        bg.setImageResource(R.drawable.bg);
        bg.setScaleType(ImageView.ScaleType.CENTER_CROP);
        root.addView(bg, match());
        View shade = new View(a);
        shade.setBackgroundColor(0x99000010);
        root.addView(shade, match());

        LinearLayout cols = new LinearLayout(a);
        cols.setOrientation(LinearLayout.HORIZONTAL);
        int pad = dp(a, 40);
        cols.setPadding(pad, dp(a, 32), pad, dp(a, 24));

        LinearLayout left = column(a);
        LinearLayout right = column(a);
        cols.addView(scroll(a, left), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f));
        View gap = new View(a);
        cols.addView(gap, new LinearLayout.LayoutParams(dp(a, 32), 1));
        cols.addView(scroll(a, right), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1.1f));
        root.addView(cols, match());
        a.setContentView(root);
        return new LinearLayout[]{left, right};
    }

    private static FrameLayout.LayoutParams match() {
        return new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
    }

    private static LinearLayout column(Context c) {
        LinearLayout l = new LinearLayout(c);
        l.setOrientation(LinearLayout.VERTICAL);
        return l;
    }

    private static ScrollView scroll(Context c, View child) {
        ScrollView s = new ScrollView(c);
        s.setFillViewport(true);
        s.setClipToPadding(false);
        int p = dp(c, 8);
        s.setPadding(p, p, p, p); // room for the focus zoom
        s.addView(child);
        return s;
    }

    static TextView text(Context c, String s, float sp, int color, boolean bold) {
        TextView t = new TextView(c);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        t.setLineSpacing(0, 1.15f);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        return t;
    }

    static void add(LinearLayout parent, View v, int topDp) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(parent.getContext(), topDp);
        parent.addView(v, lp);
    }

    /** Semi-transparent card for blocks of text. */
    static LinearLayout card(Context c) {
        LinearLayout l = column(c);
        GradientDrawable g = new GradientDrawable();
        g.setColor(0x66101030);
        g.setCornerRadius(dp(c, 14));
        g.setStroke(dp(c, 1), 0x33FFFFFF);
        l.setBackground(g);
        int p = dp(c, 18);
        l.setPadding(p, p, p, p);
        return l;
    }

    /** A big focusable tile with title + subtitle; grows and lights up when focused with the remote. */
    static LinearLayout tile(Context c, String title, String subtitle, View.OnClickListener click) {
        LinearLayout l = column(c);
        l.setFocusable(true);
        l.setClickable(true);
        l.setOnClickListener(click);
        int p = dp(c, 18);
        l.setPadding(p, dp(c, 14), p, dp(c, 14));

        StateListDrawable sl = new StateListDrawable();
        sl.addState(new int[]{android.R.attr.state_focused}, rounded(c, 0xEEFFFFFF, ACCENT, 3));
        sl.addState(new int[]{android.R.attr.state_pressed}, rounded(c, 0xEEFFFFFF, ACCENT, 3));
        sl.addState(new int[]{}, rounded(c, 0x55202040, 0x44FFFFFF, 1));
        l.setBackground(sl);

        final TextView t = text(c, title, 22, Color.WHITE, true);
        final TextView s = text(c, subtitle, 15, 0xCCFFFFFF, false);
        l.addView(t);
        if (subtitle != null && !subtitle.isEmpty()) l.addView(s);

        l.setOnFocusChangeListener((v, has) -> {
            t.setTextColor(has ? 0xFF1A1033 : Color.WHITE);
            s.setTextColor(has ? 0xFF3A2B5C : 0xCCFFFFFF);
            v.animate().scaleX(has ? 1.04f : 1f).scaleY(has ? 1.04f : 1f).setDuration(120).start();
        });
        return l;
    }

    private static GradientDrawable rounded(Context c, int fill, int stroke, int strokeDp) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(fill);
        g.setCornerRadius(dp(c, 14));
        g.setStroke(dp(c, strokeDp), stroke);
        return g;
    }

    static void message(Activity a, String title, String body) {
        AlertDialog d = new AlertDialog.Builder(a)
                .setTitle(title)
                .setMessage(body)
                .setPositiveButton("OK", null)
                .create();
        d.show();
    }
}
