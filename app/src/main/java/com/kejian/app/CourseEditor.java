package com.kejian.app;

import android.app.*;
import android.graphics.Color;
import android.view.*;
import android.widget.*;
import java.util.*;

public final class CourseEditor {
  public interface Saved {
    void accept(Course course);
  }

  public static Dialog open(MainActivity a, Course original, Saved callback) {
    Course c = original == null ? new Course() : original.copy();
    if (original == null) c.end = Math.min(c.end, a.periods());
    c.semesterId = a.termId;
    if (c.weeks.isEmpty())
      c.weeks = ScheduleRules.parseWeeks("1-" + a.term().weeks, a.term().weeks);
    final Dialog dialog = new Dialog(a);
    dialog.setContentView(build(a, c, original == null, dialog, callback));
    android.view.Window w = dialog.getWindow();
    if (w != null) {
      w.setBackgroundDrawable(Ui.bg(Color.WHITE, Ui.dp(a, 24)));
      w.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
      w.setGravity(Gravity.BOTTOM);
    }
    dialog.show();
    if (w != null) w.setLayout(-1, (int) (a.getResources().getDisplayMetrics().heightPixels * .9));
    return dialog;
  }

  private static View build(
      MainActivity a, Course c, boolean fresh, Dialog dialog, Saved callback) {
    LinearLayout root = Ui.col(a);
    Ui.pad(root, 22, 18);
    LinearLayout top = Ui.row(a);
    top.addView(
        Ui.text(a, fresh ? "添加课程" : "编辑课程", 22, Ui.INK, true),
        new LinearLayout.LayoutParams(0, -2, 1));
    top.addView(Ui.link(a, "关闭", dialog::dismiss));
    root.addView(top);
    Ui.gap(root, 12);
    ScrollView scroll = new ScrollView(a);
    scroll.setFillViewport(false);
    root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
    LinearLayout body = Ui.col(a);
    scroll.addView(body);
    Ui.gap(body, 8);
    EditText name = Ui.input(a, "课程名称 *", c.title, body);
    name.setHint("例如：移动应用开发");
    EditText teacher = Ui.input(a, "任课教师", c.teacher, body);
    EditText room = Ui.input(a, "上课地点", c.room, body);
    Spinner day = Ui.select(a, "星期", Course.DAYS, c.day - 1, body);
    LinearLayout period = Ui.row(a);
    LinearLayout left = Ui.col(a), right = Ui.col(a);
    period.addView(left, new LinearLayout.LayoutParams(0, -2, 1));
    View spacer = new View(a);
    period.addView(spacer, new LinearLayout.LayoutParams(Ui.dp(a, 12), 1));
    period.addView(right, new LinearLayout.LayoutParams(0, -2, 1));
    body.addView(period);
    String[] ps = new String[16];
    for (int i = 0; i < ps.length; i++) ps[i] = "第 " + (i + 1) + " 节";
    Spinner start = Ui.select(a, "开始节次", ps, c.start - 1, left),
        end = Ui.select(a, "结束节次", ps, c.end - 1, right);
    body.addView(Ui.text(a, "可选择1–16节；超出现有节数时，保存后自动扩展课表。", 12, Ui.MUTED, false));
    Ui.gap(body, 12);
    EditText weeks = Ui.input(a, "上课周次 *", ScheduleRules.formatWeeks(c.weeks), body);
    weeks.setHint("例如：1-3,5-17");
    LinearLayout quick = Ui.row(a);
    for (String mode : new String[] {"全部周", "单周", "双周"})
      quick.addView(
          Ui.link(
              a,
              mode,
              () -> {
                List<Integer> ws = new ArrayList<>();
                for (int i = 1; i <= a.term().weeks; i++)
                  if (mode.equals("全部周") || (mode.equals("单周") ? i % 2 == 1 : i % 2 == 0))
                    ws.add(i);
                weeks.setText(ScheduleRules.formatWeeks(ws));
              }),
          new LinearLayout.LayoutParams(0, -2, 1));
    body.addView(quick);
    Ui.gap(body, 12);
    EditText notes = Ui.input(a, "备注（选填）", c.notes, body);
    notes.setSingleLine(false);
    notes.setMinLines(2);
    body.addView(Ui.text(a, "课程颜色", 13, Ui.MUTED, false));
    Ui.gap(body, 12);
    LinearLayout colors = Ui.row(a);
    final String[] chosen = {c.color};
    final List<TextView> swatches = new ArrayList<>();
    for (String color : Course.COLORS) {
      TextView sw = Ui.text(a, color.equals(chosen[0]) ? "✓" : "", 20, Ui.PRIMARY, true);
      sw.setGravity(Gravity.CENTER);
      sw.setBackground(Ui.bg(Color.parseColor(color), Ui.dp(a, 22)));
      LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(Ui.dp(a, 38), Ui.dp(a, 38));
      lp.rightMargin = Ui.dp(a, 10);
      colors.addView(sw, lp);
      swatches.add(sw);
      sw.setContentDescription("选择课程颜色" + swatches.size());
      sw.setOnClickListener(
          v -> {
            chosen[0] = color;
            for (TextView b : swatches) b.setText("");
            sw.setText("✓");
          });
    }
    body.addView(colors);
    Ui.gap(body, 24);
    Ui.gap(root, 12);
    root.addView(
        Ui.button(
            a,
            "保存课程",
            true,
            () -> {
              try {
                c.title = name.getText().toString().trim();
                c.teacher = teacher.getText().toString().trim();
                c.room = room.getText().toString().trim();
                c.day = day.getSelectedItemPosition() + 1;
                c.start = start.getSelectedItemPosition() + 1;
                c.end = end.getSelectedItemPosition() + 1;
                c.weeks = ScheduleRules.parseWeeks(weeks.getText().toString(), a.term().weeks);
                c.notes = notes.getText().toString().trim();
                c.color = chosen[0];
                c.validate(a.term().weeks, 16);
                callback.accept(c);
                dialog.dismiss();
              } catch (Exception e) {
                Ui.error(a, e);
              }
            }));
    return root;
  }
}
