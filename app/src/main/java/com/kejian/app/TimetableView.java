package com.kejian.app;

import android.content.Context;
import android.graphics.*;
import android.text.*;
import android.view.*;
import java.util.*;

/**
 * Scrollable timetable board with modern pastel cards, left accent strips,
 * intelligent subject icons, room pin indicators, and category tag pills.
 */
public class TimetableView extends View {
  public interface Listener {
    void open(Course course);
  }

  private static final int ROOM_INK = Color.rgb(80, 87, 106);
  private static final int FROST_ALPHA = 0x59;

  private final java.util.List<Course> courses;
  private final int days, periods, week, todayIndex;
  private final String[] startTimes, endTimes;
  private final Listener listener;
  private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
  // Keep translucent decorations isolated from the opaque course surface.
  private final Paint backgroundPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
  private final Paint borderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
  private final Paint tagPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
  private final Map<Course, CachedGradient> gradientCache = new IdentityHashMap<>();
  private final TextPaint text = new TextPaint(Paint.ANTI_ALIAS_FLAG);
  private final java.util.List<RectF> hits = new ArrayList<>();
  private final java.util.List<Course> hitCourses = new ArrayList<>();

  public TimetableView(
      Context c,
      java.util.List<Course> cs,
      int d,
      int p,
      int w,
      String[] starts,
      String[] ends,
      int today,
      Listener l) {
    super(c);
    courses = cs;
    days = d;
    periods = p;
    week = w;
    startTimes = starts;
    endTimes = ends;
    todayIndex = today;
    listener = l;
    setContentDescription("每周课表，点击彩色课程查看详情");
  }

  private float dp(float x) {
    return Ui.dp(getContext(), x);
  }

  private static class CardStyle {
    final int leftColor;
    final int rightColor;
    final int accentColor;

    CardStyle(int leftColor, int rightColor, int accentColor) {
      this.leftColor = leftColor;
      this.rightColor = rightColor;
      this.accentColor = accentColor;
    }
  }

  private static class CachedGradient {
    final int color;
    final RectF bounds;
    final CardStyle style;
    final Shader shader;

    CachedGradient(int color, RectF bounds) {
      this.color = color;
      this.bounds = new RectF(bounds);
      style = calculateCardStyle(color);
      shader = new LinearGradient(bounds.left, bounds.centerY(), bounds.right, bounds.centerY(),
          style.leftColor, style.rightColor, Shader.TileMode.CLAMP);
    }
  }

  /**
   * Computes a prominent, distinct gradient and accent color for ANY course palette color.
   * Eliminates the washed-out white effect by enforcing guaranteed saturation across all hues.
   */
  private static CardStyle calculateCardStyle(int solidColor) {
    int[] colors = CourseColors.gradient(solidColor);
    return new CardStyle(colors[0], colors[1], colors[2]);
  }

  private static String getTagForCourse(Course v) {
    if (v.notes != null && !v.notes.isEmpty()) {
      if (v.notes.contains("必修")) return "必修课";
      if (v.notes.contains("选修")) return "选修课";
      if (v.notes.contains("重修")) return "重修";
    }
    String t = v.title;
    if (t.contains("实验") || t.contains("实践") || t.contains("实习") || t.contains("实训")
        || t.contains("课设") || t.contains("上机") || t.contains("实")) {
      return "实践课";
    }
    if (t.contains("高数") || t.contains("数学") || t.contains("英语") || t.contains("体育")
        || t.contains("思修") || t.contains("马原") || t.contains("毛概") || t.contains("形势")
        || t.contains("近代史") || t.contains("军事") || t.contains("政治") || t.contains("语文")
        || t.contains("习近平") || t.contains("特色社") || t.contains("理论")) {
      return "公共课";
    }
    return "专业课";
  }

  @Override
  protected void onMeasure(int w, int h) {
    setMeasuredDimension(MeasureSpec.getSize(w), (int) dp(periods * 74));
  }

  @Override
  protected void onDraw(Canvas c) {
    super.onDraw(c);
    float rail = dp(38), row = dp(74), cw = (getWidth() - rail) / days;

    // Icons change these properties; start every frame with the same grid state.
    paint.setStyle(Paint.Style.FILL);
    paint.setStrokeCap(Paint.Cap.BUTT);
    paint.setStrokeJoin(Paint.Join.MITER);
    paint.setColor(Color.WHITE);
    c.drawRect(0, 0, getWidth(), getHeight(), paint);

    paint.setColor(Ui.LINE);
    paint.setStrokeWidth(dp(.7f));
    for (int i = 0; i <= days; i++) c.drawLine(rail + i * cw, 0, rail + i * cw, getHeight(), paint);
    for (int i = 0; i <= periods; i++) c.drawLine(0, i * row, getWidth(), i * row, paint);

    // Left time axis: equal font size (8.5sp), generous vertical spacing
    for (int i = 0; i < periods; i++) {
      text.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
      text.setColor(Ui.INK);
      text.setTextSize(dp(13));
      text.setTextAlign(Paint.Align.CENTER);
      c.drawText("" + (i + 1), rail / 2, i * row + dp(20), text);

      text.setTypeface(Typeface.DEFAULT);
      text.setColor(Ui.MUTED);
      text.setTextSize(dp(8.5f));
      String start = startTimes != null && i < startTimes.length ? startTimes[i] : "";
      c.drawText(start, rail / 2, i * row + dp(34), text);

      String end = endTimes != null && i < endTimes.length ? endTimes[i] : "";
      if (!end.isEmpty()) {
        text.setTextSize(dp(8.5f));
        c.drawText(end, rail / 2, i * row + dp(62), text);
      }
    }

    hits.clear();
    hitCourses.clear();
    gradientCache.keySet().retainAll(courses);

    for (Course v : courses) {
      if (v.day > days || !v.weeks.contains(week)) continue;
      RectF r =
          new RectF(
              rail + (v.day - 1) * cw + dp(2.5f),
              (v.start - 1) * row + dp(3),
              rail + v.day * cw - dp(2.5f),
              Math.min(v.end, periods) * row - dp(3));
      if (r.bottom <= r.top) continue;

      hits.add(r);
      hitCourses.add(v);

      int solidColor = Ui.color(v.color);
      CachedGradient cached = gradientCache.get(v);
      if (cached == null || cached.color != solidColor || !cached.bounds.equals(r)) {
        cached = new CachedGradient(solidColor, r);
        gradientCache.put(v, cached);
      }
      CardStyle style = cached.style;
      // MainActivity passes -1 when highlighting is disabled or this week does not contain today.
      boolean isToday = v.day - 1 == todayIndex;
      int ink = isToday ? Ui.inkOn(solidColor) : Ui.INK;
      int roomInk = isToday ? ink : ROOM_INK;
      int accent = isToday ? ink : style.accentColor;

      // Today uses the original stored color, without extra saturation or a white overlay.
      backgroundPaint.setShader(isToday ? null : cached.shader);
      backgroundPaint.setColor(isToday ? solidColor : Color.WHITE);
      backgroundPaint.setAlpha(255);
      c.drawRoundRect(r, dp(11), dp(11), backgroundPaint);

      // 1.1 Delicate Stroke matching card tint
      borderPaint.setColor(Color.argb(45, Color.red(accent), Color.green(accent), Color.blue(accent)));
      borderPaint.setStyle(Paint.Style.STROKE);
      borderPaint.setStrokeWidth(dp(1.2f));
      c.drawRoundRect(r, dp(11), dp(11), borderPaint);

      // 3. Clip card body for content drawing
      c.save();
      c.clipRect(r.left + dp(5), r.top + dp(3), r.right - dp(3), r.bottom - dp(3));

      // 4. Subject Icon (top left)
      float iconX = r.left + dp(9);
      float iconY = r.top + dp(9);
      float iconSize = dp(12.5f);
      drawCourseIcon(c, v.title, iconX, iconY, iconSize, accent);

      // 5. Course Title
      float contentWidth = Math.max(1, r.width() - dp(14));
      text.setTextAlign(Paint.Align.LEFT);
      text.setColor(ink);
      text.setTextSize(dp(days == 7 ? 10.5f : 12f));
      text.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));

      StaticLayout titleLayout =
          StaticLayout.Builder.obtain(v.title, 0, v.title.length(), text, (int) contentWidth)
              .setAlignment(Layout.Alignment.ALIGN_NORMAL)
              .setMaxLines(3)
              .setEllipsize(TextUtils.TruncateAt.END)
              .setIncludePad(false)
              .build();

      float titleY = iconY + iconSize + dp(6);
      c.save();
      c.translate(r.left + dp(9), titleY);
      titleLayout.draw(c);
      c.restore();

      // 6. Location with Location Pin Icon
      float nextY = titleY + titleLayout.getHeight() + dp(5);
      if (!v.room.isEmpty() && nextY + dp(12) < r.bottom - dp(20)) {
        float pinX = r.left + dp(9);
        float pinY = nextY + dp(2);

        // Location Pin (circle + tip)
        paint.setColor(roomInk);
        paint.setStyle(Paint.Style.FILL);
        c.drawCircle(pinX + dp(2), pinY + dp(2), dp(1.8f), paint);

        Path pin = new Path();
        pin.moveTo(pinX + dp(0.6f), pinY + dp(2));
        pin.lineTo(pinX + dp(2), pinY + dp(5.5f));
        pin.lineTo(pinX + dp(3.4f), pinY + dp(2));
        pin.close();
        c.drawPath(pin, paint);

        // Location text
        text.setTypeface(Typeface.DEFAULT);
        text.setColor(roomInk);
        text.setTextSize(dp(days == 7 ? 8.5f : 9.5f));
        float maxRoomWidth = Math.max(1, contentWidth - dp(7));
        CharSequence roomEllipsized = TextUtils.ellipsize(v.room, text, maxRoomWidth, TextUtils.TruncateAt.END);
        c.drawText(roomEllipsized.toString(), pinX + dp(6.5f), nextY + dp(6f), text);

        nextY += dp(14);
      }

      // 7. Bottom Tag Pill (e.g. 公共课 / 专业课 / 实践课)
      if (r.height() >= dp(95) && nextY < r.bottom - dp(18)) {
        String tag = getTagForCourse(v);
        text.setTextSize(dp(8f));
        text.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        float tagTextW = text.measureText(tag);
        float pillW = tagTextW + dp(10);
        float pillH = dp(17);
        float pillX = r.left + dp(9);
        float pillY = r.bottom - dp(9) - pillH;

        RectF pillRect = new RectF(pillX, pillY, pillX + pillW, pillY + pillH);
        tagPaint.setColor(Color.argb(38, Color.red(accent), Color.green(accent), Color.blue(accent)));
        c.drawRoundRect(pillRect, dp(5), dp(5), tagPaint);

        text.setColor(accent);
        text.setTextAlign(Paint.Align.CENTER);
        c.drawText(tag, pillRect.centerX(), pillRect.centerY() + dp(2.8f), text);
      }

      c.restore();
    }
  }

  private void drawCourseIcon(Canvas c, String title, float x, float y, float size, int color) {
    paint.setColor(color);
    paint.setStyle(Paint.Style.STROKE);
    paint.setStrokeWidth(dp(1.25f));
    paint.setStrokeCap(Paint.Cap.ROUND);
    paint.setStrokeJoin(Paint.Join.ROUND);

    if (title.contains("高数") || title.contains("数学") || title.contains("英语") || title.contains("史")
        || title.contains("毛") || title.contains("思") || title.contains("政") || title.contains("语")
        || title.contains("文") || title.contains("法") || title.contains("社") || title.contains("论")) {
      // 📖 Open Book Icon
      float mid = x + size / 2;
      Path p = new Path();
      p.moveTo(x + dp(1), y + dp(2.5f));
      p.quadTo(mid - dp(1), y + dp(1), mid, y + dp(2.5f));
      p.quadTo(x + size - dp(1), y + dp(1), x + size - dp(1), y + dp(2.5f));
      p.lineTo(x + size - dp(1), y + size - dp(1.5f));
      p.quadTo(mid + dp(1), y + size - dp(3.5f), mid, y + size - dp(1.5f));
      p.quadTo(x + dp(1), y + size - dp(3.5f), x + dp(1), y + size - dp(1.5f));
      p.close();
      c.drawPath(p, paint);
      c.drawLine(mid, y + dp(2.5f), mid, y + size - dp(1.5f), paint);
    } else if (title.contains("实验") || title.contains("化") || title.contains("物")
        || title.contains("生") || title.contains("医")) {
      // 🧪 Flask / Beaker Icon
      Path p = new Path();
      p.moveTo(x + dp(3.5f), y + dp(1));
      p.lineTo(x + size - dp(3.5f), y + dp(1));
      p.moveTo(x + dp(4.5f), y + dp(1));
      p.lineTo(x + dp(4.5f), y + dp(4));
      p.lineTo(x + dp(1.5f), y + size - dp(1.5f));
      p.lineTo(x + size - dp(1.5f), y + size - dp(1.5f));
      p.lineTo(x + size - dp(4.5f), y + dp(4));
      p.lineTo(x + size - dp(4.5f), y + dp(1));
      c.drawPath(p, paint);
      c.drawLine(x + dp(3.5f), y + size - dp(4.5f), x + size - dp(3.5f), y + size - dp(4.5f), paint);
    } else if (title.contains("安全") || title.contains("防") || title.contains("保密") || title.contains("密")) {
      // 🛡️ Shield Icon
      Path p = new Path();
      p.moveTo(x + dp(2), y + dp(2));
      p.lineTo(x + size - dp(2), y + dp(2));
      p.lineTo(x + size - dp(2), y + size * 0.55f);
      p.quadTo(x + size / 2, y + size - dp(1), x + size / 2, y + size - dp(1));
      p.quadTo(x + dp(2), y + size * 0.55f, x + dp(2), y + dp(2));
      c.drawPath(p, paint);
    } else if (title.contains("软") || title.contains("开发") || title.contains("程序")
        || title.contains("算法") || title.contains("工程") || title.contains("码")) {
      // </> Code Tag Icon
      Path p = new Path();
      p.moveTo(x + dp(3.5f), y + dp(3));
      p.lineTo(x + dp(1.2f), y + size / 2);
      p.lineTo(x + dp(3.5f), y + size - dp(3));
      p.moveTo(x + size - dp(3.5f), y + dp(3));
      p.lineTo(x + size - dp(1.2f), y + size / 2);
      p.lineTo(x + size - dp(3.5f), y + size - dp(3));
      c.drawPath(p, paint);
      c.drawLine(x + size * 0.62f, y + dp(2f), x + size * 0.38f, y + size - dp(2f), paint);
    } else if (title.contains("linux") || title.contains("系统") || title.contains("操") || title.contains("端")) {
      // >_ Terminal Icon
      RectF box = new RectF(x + dp(1), y + dp(1.5f), x + size - dp(1), y + size - dp(1.5f));
      c.drawRoundRect(box, dp(2), dp(2), paint);
      Path p = new Path();
      p.moveTo(x + dp(3), y + dp(4));
      p.lineTo(x + dp(5.5f), y + size / 2);
      p.lineTo(x + dp(3), y + size - dp(4));
      c.drawPath(p, paint);
      c.drawLine(x + dp(7), y + size - dp(4), x + size - dp(3), y + size - dp(4), paint);
    } else if (title.contains("计") || title.contains("算") || title.contains("组") || title.contains("硬件") || title.contains("网")) {
      // 🔲 Chip Icon
      RectF chip = new RectF(x + dp(3), y + dp(3), x + size - dp(3), y + size - dp(3));
      c.drawRoundRect(chip, dp(2), dp(2), paint);
      c.drawLine(x + dp(1), y + size / 2, x + dp(3), y + size / 2, paint);
      c.drawLine(x + size - dp(3), y + size / 2, x + size - dp(1), y + size / 2, paint);
      c.drawLine(x + size / 2, y + dp(1), x + size / 2, y + dp(3), paint);
      c.drawLine(x + size / 2, y + size - dp(3), x + size / 2, y + size - dp(1), paint);
    } else {
      // General Bookmark / Tag Icon
      RectF box = new RectF(x + dp(2), y + dp(2), x + size - dp(2), y + size - dp(2));
      c.drawRoundRect(box, dp(3), dp(3), paint);
      paint.setStyle(Paint.Style.FILL);
      c.drawCircle(x + size / 2, y + size / 2, dp(1.6f), paint);
    }
  }

  @Override
  public boolean onTouchEvent(android.view.MotionEvent e) {
    if (e.getAction() == android.view.MotionEvent.ACTION_UP) {
      for (int i = hits.size() - 1; i >= 0; i--) {
        if (hits.get(i).contains(e.getX(), e.getY())) {
          performClick();
          listener.open(hitCourses.get(i));
          return true;
        }
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
