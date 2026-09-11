package com.kejian.app;

import android.app.*;
import android.content.*;
import android.graphics.*;
import android.graphics.drawable.GradientDrawable;
import android.view.*;
import android.widget.*;

/** Shared spacing, type, surfaces and line icons. All dimensions are dp/sp. */
public final class Ui {
  public static final int INK = Color.rgb(29, 33, 53),
      MUTED = Color.rgb(127, 133, 153),
      PRIMARY = Color.rgb(101, 88, 232),
      BG = Color.rgb(250, 250, 253),
      LINE = Color.rgb(235, 236, 244);

  public static int dp(Context c, float v) {
    return Math.round(v * c.getResources().getDisplayMetrics().density);
  }

  public static GradientDrawable bg(int color, int radius) {
    GradientDrawable d = new GradientDrawable();
    d.setColor(color);
    d.setCornerRadius(radius);
    return d;
  }

  public static GradientDrawable border(Context c, int color, int radius) {
    GradientDrawable d = bg(color, dp(c, radius));
    d.setStroke(dp(c, 1), LINE);
    return d;
  }

  public static TextView text(Context c, String value, int size, int color, boolean bold) {
    TextView t = new TextView(c);
    t.setText(value);
    t.setTextSize(size);
    t.setTextColor(color);
    t.setFontFeatureSettings("kern");
    if (bold) t.setTypeface(null, Typeface.BOLD);
    t.setIncludeFontPadding(false);
    return t;
  }

  public static LinearLayout col(Context c) {
    LinearLayout l = new LinearLayout(c);
    l.setOrientation(LinearLayout.VERTICAL);
    return l;
  }

  public static LinearLayout row(Context c) {
    LinearLayout l = new LinearLayout(c);
    l.setOrientation(LinearLayout.HORIZONTAL);
    l.setGravity(Gravity.CENTER_VERTICAL);
    return l;
  }

  public static void pad(View v, int x, int y) {
    v.setPadding(
        dp(v.getContext(), x), dp(v.getContext(), y), dp(v.getContext(), x), dp(v.getContext(), y));
  }

  public static void gap(LinearLayout l, int h) {
    View v = new View(l.getContext());
    l.addView(v, new LinearLayout.LayoutParams(1, dp(l.getContext(), h)));
  }

  public static TextView button(Context c, String label, boolean primary, Runnable action) {
    TextView t = text(c, label, 15, primary ? Color.WHITE : PRIMARY, true);
    t.setGravity(Gravity.CENTER);
    t.setMinHeight(dp(c, 50));
    pad(t, 14, 12);
    t.setBackground(bg(primary ? PRIMARY : Color.rgb(239, 237, 255), dp(c, 14)));
    t.setOnClickListener(v -> action.run());
    t.setContentDescription(label);
    return t;
  }

  public static TextView link(Context c, String label, Runnable action) {
    TextView t = text(c, label, 14, PRIMARY, true);
    pad(t, 12, 12);
    t.setMinHeight(dp(c, 44));
    t.setGravity(Gravity.CENTER);
    t.setOnClickListener(v -> action.run());
    return t;
  }

  public static LinearLayout card(Context c) {
    LinearLayout l = col(c);
    pad(l, 16, 16);
    l.setBackground(border(c, Color.WHITE, 18));
    return l;
  }

  public static EditText input(Context c, String label, String value, LinearLayout parent) {
    TextView title = text(c, label, 13, MUTED, false);
    parent.addView(title);
    gap(parent, 7);
    EditText e = new EditText(c);
    e.setTextSize(15);
    e.setTextColor(INK);
    e.setSingleLine(true);
    e.setText(value);
    e.setBackground(border(c, BG, 12));
    pad(e, 12, 12);
    parent.addView(e, new LinearLayout.LayoutParams(-1, dp(c, 48)));
    gap(parent, 16);
    return e;
  }

  public static Spinner select(
      Context c, String label, String[] items, int index, LinearLayout parent) {
    parent.addView(text(c, label, 13, MUTED, false));
    gap(parent, 7);
    Spinner s = new Spinner(c);
    ArrayAdapter<String> a =
        new ArrayAdapter<>(c, android.R.layout.simple_spinner_dropdown_item, items);
    s.setAdapter(a);
    s.setSelection(Math.max(0, Math.min(items.length - 1, index)));
    s.setBackground(border(c, BG, 12));
    parent.addView(s, new LinearLayout.LayoutParams(-1, dp(c, 48)));
    gap(parent, 16);
    return s;
  }

  public static View icon(Context c, String name, int color) {
    return new Icon(c, name, color);
  }

  public static TextView empty(Context c, String title, String body) {
    TextView t = text(c, title + "\n\n" + body, 15, MUTED, false);
    t.setGravity(Gravity.CENTER);
    t.setLineSpacing(dp(c, 5), 1);
    pad(t, 24, 42);
    return t;
  }

  public static void error(Context c, Exception e) {
    new AlertDialog.Builder(c)
        .setTitle("暂时无法完成")
        .setMessage(e.getMessage() == null ? "请检查输入后重试" : e.getMessage())
        .setPositiveButton("知道了", null)
        .show();
  }

  private static class Icon extends View {
    final String name;
    final Paint p = new Paint(3);
    final int color;

    Icon(Context c, String n, int col) {
      super(c);
      name = n;
      color = col;
      setLayerType(View.LAYER_TYPE_SOFTWARE, null);
    }

    @Override
    protected void onDraw(Canvas cv) {
      super.onDraw(cv);
      cv.save();
      float s = Math.min(getWidth(), getHeight()) / 24f;
      cv.translate((getWidth() - 24 * s) / 2, (getHeight() - 24 * s) / 2);
      cv.scale(s, s);
      p.setColor(color);
      p.setStyle(Paint.Style.STROKE);
      p.setStrokeWidth(1.7f);
      p.setStrokeCap(Paint.Cap.ROUND);
      p.setStrokeJoin(Paint.Join.ROUND);
      switch (name) {
        case "calendar":
          cv.drawRoundRect(3, 5, 21, 21, 3, 3, p);
          cv.drawLine(3, 10, 21, 10, p);
          cv.drawLine(8, 3, 8, 7, p);
          cv.drawLine(16, 3, 16, 7, p);
          cv.drawLine(8, 14, 10, 14, p);
          cv.drawLine(14, 14, 16, 14, p);
          cv.drawLine(8, 18, 10, 18, p);
          break;
        case "today":
          cv.drawCircle(12, 12, 9, p);
          cv.drawLine(12, 6, 12, 12, p);
          cv.drawLine(12, 12, 16, 14, p);
          break;
        case "book":
          cv.drawRoundRect(3, 4, 11, 20, 2, 2, p);
          cv.drawRoundRect(13, 4, 21, 20, 2, 2, p);
          cv.drawLine(12, 6, 12, 21, p);
          break;
        case "settings":
          cv.drawCircle(12, 12, 4, p);
          cv.drawCircle(12, 12, 8, p);
          for (int i = 0; i < 8; i++) {
            double a = i * Math.PI / 4;
            cv.drawLine(
                12 + 8 * (float) Math.cos(a),
                12 + 8 * (float) Math.sin(a),
                12 + 10 * (float) Math.cos(a),
                12 + 10 * (float) Math.sin(a),
                p);
          }
          break;
        case "spark":
          Path q = new Path();
          q.moveTo(12, 2);
          q.lineTo(15, 9);
          q.lineTo(22, 12);
          q.lineTo(15, 15);
          q.lineTo(12, 22);
          q.lineTo(9, 15);
          q.lineTo(2, 12);
          q.lineTo(9, 9);
          q.close();
          cv.drawPath(q, p);
          break;
        case "back":
          cv.drawLine(17, 12, 5, 12, p);
          cv.drawLine(5, 12, 11, 6, p);
          cv.drawLine(5, 12, 11, 18, p);
          break;
        case "pin":
          cv.drawCircle(12, 9, 3, p);
          Path pin = new Path();
          pin.moveTo(12, 22);
          pin.cubicTo(0, 11, 4, 3, 12, 3);
          pin.cubicTo(20, 3, 24, 11, 12, 22);
          cv.drawPath(pin, p);
          break;
        default:
          cv.drawLine(12, 5, 12, 19, p);
          cv.drawLine(5, 12, 19, 12, p);
      }
      cv.restore();
    }
  }
}
