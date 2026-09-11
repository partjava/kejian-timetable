package com.kejian.app;

import android.app.*;
import android.graphics.Color;
import android.graphics.Typeface;
import android.view.*;
import android.widget.*;
import java.util.*;

public final class CourseEditor {
  public interface Saved {
    /**
     * @param colorPicked true when the user chose a colour in this session. Needed to tell a
     *     deliberate pick apart from "never opened the picker", which decides whether changing a
     *     course's colour repaints every arrangement sharing its name.
     */
    void accept(Course course, boolean colorPicked);
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
    EditText weeks = weekPicker(a, c, body);
    EditText notes = Ui.input(a, "备注（选填）", c.notes, body);
    notes.setSingleLine(false);
    notes.setMinLines(2);
    final String[] chosen = {c.color};
    final boolean[] colorPicked = {false};
    body.addView(Ui.text(a, "课程颜色", 13, Ui.MUTED, false));
    Ui.gap(body, 12);
    final List<TextView> swatches = new ArrayList<>();
    LinearLayout grid = Ui.col(a);
    for (int row = 0; row * 6 < CourseColors.PALETTE.length; row++) {
      LinearLayout line = Ui.row(a);
      for (int k = 0; k < 6; k++) {
        int index = row * 6 + k;
        if (index >= CourseColors.PALETTE.length) {
          line.addView(new View(a), new LinearLayout.LayoutParams(0, Ui.dp(a, 34), 1));
          continue;
        }
        String color = CourseColors.PALETTE[index];
        TextView sw = Ui.text(a, "", 18, Ui.INK, true);
        sw.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, Ui.dp(a, 34), 1);
        lp.setMargins(Ui.dp(a, 3), Ui.dp(a, 3), Ui.dp(a, 3), Ui.dp(a, 3));
        line.addView(sw, lp);
        swatches.add(sw);
        sw.setContentDescription("选择课程颜色" + (index + 1));
        sw.setOnClickListener(
            v -> {
              chosen[0] = color;
              colorPicked[0] = true;
              paintSwatches(a, swatches, chosen[0], null);
            });
      }
      grid.addView(line);
    }
    body.addView(grid);
    Ui.gap(body, 10);
    TextView current = Ui.text(a, "", 18, Ui.INK, true);
    current.setGravity(Gravity.CENTER);
    current.setContentDescription("当前颜色");
    LinearLayout custom = Ui.row(a);
    custom.addView(current, new LinearLayout.LayoutParams(Ui.dp(a, 48), Ui.dp(a, 48)));
    custom.addView(new View(a), new LinearLayout.LayoutParams(Ui.dp(a, 10), 1));
    custom.addView(
        Ui.button(
            a,
            "自定义颜色…",
            false,
            () ->
                ColorPicker.open(
                    a,
                    chosen[0],
                    hex -> {
                      chosen[0] = hex;
                      colorPicked[0] = true;
                      paintSwatches(a, swatches, chosen[0], null);
                    })),
        new LinearLayout.LayoutParams(0, Ui.dp(a, 48), 1));
    body.addView(custom);
    Ui.gap(body, 8);
    body.addView(Ui.text(a, "同名课程共用一种颜色，改色会一起应用。", 12, Ui.MUTED, false));
    paintSwatches(a, swatches, chosen[0], current);
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
                callback.accept(c, colorPicked[0]);
                dialog.dismiss();
              } catch (Exception e) {
                Ui.error(a, e);
              }
            }));
    return root;
  }

  /**
   * Week chips over the term's weeks, plus the text field they mirror.
   *
   * The chips are only a view onto the field's text: saving still goes through
   * {@link ScheduleRules#parseWeeks}, so the parser's messages and every existing test are
   * untouched. Typing wins while the field has focus and the chips catch up when it loses focus —
   * syncing on each keystroke would fight half-typed input like "1-".
   */
  private static EditText weekPicker(MainActivity a, Course c, LinearLayout body) {
    final int total = a.term().weeks;
    final Set<Integer> picked = new TreeSet<>(c.weeks);
    final List<TextView> chips = new ArrayList<>();
    final int[] anchor = {0};

    final EditText field = new EditText(a);
    field.setTextSize(15);
    field.setTextColor(Ui.INK);
    field.setSingleLine(true);
    field.setText(ScheduleRules.formatWeeks(new ArrayList<>(picked)));
    field.setHint("例如：1-3,5-17");
    field.setBackground(Ui.border(a, Ui.BG, 12));
    Ui.pad(field, 12, 12);

    final Runnable showPicked = () -> field.setText(ScheduleRules.formatWeeks(new ArrayList<>(picked)));
    final Runnable repaint =
        () -> {
          for (int i = 0; i < chips.size(); i++) paintChip(a, chips.get(i), picked.contains(i + 1));
        };

    body.addView(Ui.text(a, "上课周次 *", 13, Ui.MUTED, false));
    Ui.gap(body, 7);
    LinearLayout grid = Ui.col(a);
    body.addView(grid);
    for (int row = 0; row * 7 < total; row++) {
      LinearLayout line = Ui.row(a);
      for (int k = 0; k < 7; k++) {
        final int week = row * 7 + k + 1;
        if (week > total) {
          // Keeps the last row's chips the same width as the rows above.
          line.addView(new View(a), new LinearLayout.LayoutParams(0, Ui.dp(a, 40), 1));
          continue;
        }
        TextView chip = Ui.text(a, "" + week, 14, Ui.INK, false);
        chip.setGravity(Gravity.CENTER);
        chip.setContentDescription("第" + week + "周");
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, Ui.dp(a, 40), 1);
        int m = Ui.dp(a, 3);
        lp.setMargins(m, m, m, m);
        line.addView(chip, lp);
        chips.add(chip);
        chip.setOnClickListener(
            v -> {
              if (!picked.remove(week)) picked.add(week);
              anchor[0] = week;
              paintChip(a, chip, picked.contains(week));
              showPicked.run();
            });
        chip.setOnLongClickListener(
            v -> {
              if (anchor[0] > 0 && anchor[0] != week)
                for (int w = Math.min(anchor[0], week); w <= Math.max(anchor[0], week); w++)
                  picked.add(w);
              anchor[0] = week;
              repaint.run();
              showPicked.run();
              return true;
            });
      }
      grid.addView(line);
    }
    repaint.run();
    Ui.gap(body, 6);
    LinearLayout quick = Ui.row(a);
    for (String mode : new String[] {"全部周", "单周", "双周"}) {
      quick.addView(
          Ui.link(
              a,
              mode,
              () -> {
                picked.clear();
                for (int i = 1; i <= total; i++)
                  if (mode.equals("全部周") || (mode.equals("单周") ? i % 2 == 1 : i % 2 == 0))
                    picked.add(i);
                repaint.run();
                showPicked.run();
              }),
          new LinearLayout.LayoutParams(0, -2, 1));
    }
    body.addView(quick);
    Ui.gap(body, 10);
    body.addView(Ui.text(a, "或直接填写", 13, Ui.MUTED, false));
    Ui.gap(body, 7);
    body.addView(field, new LinearLayout.LayoutParams(-1, Ui.dp(a, 48)));
    field.setOnFocusChangeListener(
        (v, hasFocus) -> {
          if (hasFocus) return;
          try {
            picked.clear();
            picked.addAll(ScheduleRules.parseWeeks(field.getText().toString(), total));
          } catch (Exception e) {
            return; // half-typed or invalid: leave the chips as they were until it parses
          }
          repaint.run();
        });
    Ui.gap(body, 16);
    return field;
  }

  private static void paintChip(MainActivity a, TextView chip, boolean on) {
    if (chip == null) return;
    chip.setTextColor(on ? Color.WHITE : Ui.INK);
    chip.setTypeface(null, on ? Typeface.BOLD : Typeface.NORMAL);
    chip.setBackground(Ui.bg(on ? Ui.PRIMARY : Color.rgb(240, 240, 246), Ui.dp(a, 12)));
  }

  /** Rings and ticks the chosen swatch. {@code current} is null when only the grid is on screen. */
  private static void paintSwatches(
      MainActivity a, List<TextView> swatches, String color, TextView current) {
    for (int i = 0; i < swatches.size(); i++) {
      String hex = CourseColors.PALETTE[i];
      boolean on = hex.equalsIgnoreCase(color);
      TextView sw = swatches.get(i);
      sw.setText(on ? "✓" : "");
      sw.setTextColor(Ui.inkOn(hex));
      sw.setBackground(Ui.swatch(Ui.color(hex), Ui.dp(a, 12), on));
    }
    if (current != null) current.setBackground(Ui.swatch(Ui.color(color), Ui.dp(a, 12), false));
  }
}
