package com.ilatv.tv;

import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

/** 纯 Java 动态构建界面，整套工程没有 layout XML。 */
public final class Ui {

    public static final int BG = 0xFF0F1115;
    public static final int CARD = 0xFF1B1F27;
    public static final int ACCENT = 0xFF6C8CFF;
    public static final int TEXT = 0xFFECEFF5;
    public static final int SUB = 0xFF9AA3B2;

    private Ui() {}

    public static int dp(Context c, int v) {
        return (int) (v * c.getResources().getDisplayMetrics().density + 0.5f);
    }

    public static LinearLayout column(Context c) {
        LinearLayout l = new LinearLayout(c);
        l.setOrientation(LinearLayout.VERTICAL);
        return l;
    }

    public static LinearLayout row(Context c) {
        LinearLayout l = new LinearLayout(c);
        l.setOrientation(LinearLayout.HORIZONTAL);
        return l;
    }

    public static TextView text(Context c, String s, int sizeSp, int color) {
        TextView t = new TextView(c);
        t.setText(s);
        t.setTextSize(sizeSp);
        t.setTextColor(color);
        return t;
    }

    public static Button button(Context c, String s) {
        Button b = new Button(c);
        b.setText(s);
        b.setTextColor(Color.WHITE);
        b.setAllCaps(false);
        b.setTextSize(14);
        GradientDrawable g = new GradientDrawable();
        g.setColor(ACCENT);
        g.setCornerRadius(dp(c, 8));
        b.setBackground(g);
        b.setPadding(dp(c, 14), dp(c, 8), dp(c, 14), dp(c, 8));
        return b;
    }

    public static Button ghost(Context c, String s) {
        Button b = button(c, s);
        GradientDrawable g = new GradientDrawable();
        g.setColor(CARD);
        g.setCornerRadius(dp(c, 8));
        g.setStroke(dp(c, 1), 0xFF353C4A);
        b.setBackground(g);
        b.setTextColor(TEXT);
        return b;
    }

    public static EditText input(Context c, String hint) {
        EditText e = new EditText(c);
        e.setHint(hint);
        e.setHintTextColor(SUB);
        e.setTextColor(TEXT);
        e.setSingleLine(true);
        e.setTextSize(15);
        GradientDrawable g = new GradientDrawable();
        g.setColor(CARD);
        g.setCornerRadius(dp(c, 8));
        g.setStroke(dp(c, 1), 0xFF353C4A);
        e.setBackground(g);
        e.setPadding(dp(c, 12), dp(c, 10), dp(c, 12), dp(c, 10));
        return e;
    }

    public static LinearLayout.LayoutParams lp(int w, int h, float weight) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(w, h);
        p.weight = weight;
        return p;
    }

    /**
     * 画面比例「自适应」的判定规则（点播与直播共用，避免两边逻辑漂移）：
     * 片源比例与屏幕比例相差 12% 以内 → 裁剪铺满（几乎看不到黑边）；
     * 差得多（如 4:3 老片、2.35:1 电影）→ 保持片源比例完整显示，宁可留黑边也不切画面。
     * 「全屏显示」恒铺满，「原始比例」恒完整显示，都不看片源。
     */
    public static boolean aspectCrop(String mode, float srcRatio, float screenRatio) {
        if ("full".equals(mode)) return true;
        if (!"auto".equals(mode)) return false;
        if (srcRatio <= 0.01f || screenRatio <= 0.01f) return false;
        return Math.abs(srcRatio - screenRatio) / screenRatio <= 0.12f;
    }
}
