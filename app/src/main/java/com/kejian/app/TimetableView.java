package com.kejian.app;

import android.content.Context;
import android.graphics.*;
import android.text.*;
import android.view.*;
import java.util.*;

/** Scrollable twelve-period board. Hit areas use the same rectangles as drawing. */
public class TimetableView extends View {
  public interface Listener {
    void open(Course course);
  }

  private final java.util.List<Course> courses;
  private final int days, periods, week;
  private final String[] times;
  private final Listener listener;
  private final Paint paint = new Paint(3);
  private final TextPaint text = new TextPaint(3);
  private final java.util.List<RectF> hits = new ArrayList<>();
  private final java.util.List<Course> hitCourses = new ArrayList<>();

  public TimetableView(
      Context c, java.util.List<Course> cs, int d, int p, int w, String[] t, Listener l) {
    super(c);
    courses = cs;
    days = d;
    periods = p;
    week = w;
    times = t;
    listener = l;
    setContentDescription("每周课表，点击彩色课程查看详情");
  }

  private float dp(float x) {
    return Ui.dp(getContext(), x);
  }

  @Override
  protected void onMeasure(int w, int h) {
    setMeasuredDimension(MeasureSpec.getSize(w), (int) dp(periods * 74));
  }

  @Override
  protected void onDraw(Canvas c) {
    super.onDraw(c);
    float rail = dp(36), row = dp(74), cw = (getWidth() - rail) / days;
    paint.setColor(Color.WHITE);
    c.drawRect(0, 0, getWidth(), getHeight(), paint);
    paint.setColor(Ui.LINE);
    paint.setStrokeWidth(dp(.7f));
    for (int i = 0; i <= days; i++) c.drawLine(rail + i * cw, 0, rail + i * cw, getHeight(), paint);
    for (int i = 0; i <= periods; i++) c.drawLine(0, i * row, getWidth(), i * row, paint);
    for (int i = 0; i < periods; i++) {
      text.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
      text.setColor(Ui.INK);
      text.setTextSize(dp(12));
      text.setTextAlign(Paint.Align.CENTER);
      c.drawText("" + (i + 1), rail / 2, i * row + dp(22), text);
      text.setTypeface(Typeface.DEFAULT);
      text.setColor(Ui.MUTED);
      text.setTextSize(dp(8));
      String time = i < times.length ? times[i] : "";
      c.drawText(time, rail / 2, i * row + dp(37), text);
    }
    hits.clear();
    hitCourses.clear();
    for (Course v : courses) {
      if (v.day > days || !v.weeks.contains(week)) continue;
      RectF r =
          new RectF(
              rail + (v.day - 1) * cw + dp(2),
              (v.start - 1) * row + dp(3),
              rail + v.day * cw - dp(2),
              Math.min(v.end, periods) * row - dp(3));
      if (r.bottom <= r.top) continue;
      paint.setColor(Color.parseColor(v.color));
      c.drawRoundRect(r, dp(9), dp(9), paint);
      hits.add(r);
      hitCourses.add(v);
      int width = Math.max(1, (int) (r.width() - dp(8)));
      text.setTextAlign(Paint.Align.LEFT);
      text.setColor(Ui.INK);
      text.setTextSize(dp(days == 7 ? 10 : 12));
      text.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
      StaticLayout title =
          StaticLayout.Builder.obtain(v.title, 0, v.title.length(), text, width)
              .setAlignment(Layout.Alignment.ALIGN_CENTER)
              .setMaxLines(3)
              .setEllipsize(TextUtils.TruncateAt.END)
              .setIncludePad(false)
              .build();
      c.save();
      c.clipRect(r);
      c.translate(r.left + dp(4), r.top + dp(11));
      title.draw(c);
      float y = title.getHeight() + dp(8);
      text.setTextSize(dp(days == 7 ? 8 : 10));
      text.setColor(Color.rgb(80, 87, 106));
      text.setTypeface(Typeface.DEFAULT);
      StaticLayout room =
          StaticLayout.Builder.obtain(v.room, 0, v.room.length(), text, width)
              .setAlignment(Layout.Alignment.ALIGN_CENTER)
              .setMaxLines(2)
              .setEllipsize(TextUtils.TruncateAt.END)
              .setIncludePad(false)
              .build();
      c.translate(0, y);
      room.draw(c);
      c.restore();
    }
  }

  @Override
  public boolean onTouchEvent(android.view.MotionEvent e) {
    if (e.getAction() == android.view.MotionEvent.ACTION_UP) {
      for (int i = hits.size() - 1; i >= 0; i--)
        if (hits.get(i).contains(e.getX(), e.getY())) {
          performClick();
          listener.open(hitCourses.get(i));
          return true;
        }
    }
    return true;
  }

  @Override
  public boolean performClick() {
    super.performClick();
    return true;
  }
}
