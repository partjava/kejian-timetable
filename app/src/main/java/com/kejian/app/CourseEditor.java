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
    View root = a.getLayoutInflater().inflate(R.layout.dialog_course_editor, null);
    ((TextView) root.findViewById(R.id.course_editor_title))
        .setText(fresh ? R.string.course_add_title : R.string.course_edit_title);
    root.findViewById(R.id.course_close).setOnClickListener(v -> dialog.dismiss());
    EditText name = root.findViewById(R.id.course_name);
    EditText teacher = root.findViewById(R.id.course_teacher);
    EditText room = root.findViewById(R.id.course_room);
    EditText notes = root.findViewById(R.id.course_notes);
    name.setText(c.title);
    teacher.setText(c.teacher);
    room.setText(c.room);
    notes.setText(c.notes);
    Spinner day = root.findViewById(R.id.course_day);
    Spinner start = root.findViewById(R.id.course_start);
    Spinner end = root.findViewById(R.id.course_end);
    bindSpinner(a, day, Course.DAYS, c.day - 1);
    String[] ps = new String[16];
    for (int i = 0; i < ps.length; i++) ps[i] = "第 " + (i + 1) + " 节";
    bindSpinner(a, start, ps, c.start - 1);
    bindSpinner(a, end, ps, c.end - 1);
    EditText weeks = weekPicker(a, c, root);
    final String[] chosen = {c.color};
    final boolean[] colorPicked = {false};
    final List<TextView> swatches = new ArrayList<>();
    LinearLayout grid = root.findViewById(R.id.course_color_grid);
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
    TextView current = root.findViewById(R.id.course_current_color);
    root.findViewById(R.id.course_custom_color).setOnClickListener(
        v -> ColorPicker.open(
            a,
            chosen[0],
            hex -> {
              chosen[0] = hex;
              colorPicked[0] = true;
              paintSwatches(a, swatches, chosen[0], null);
            }));
    paintSwatches(a, swatches, chosen[0], current);
    root.findViewById(R.id.course_save).setOnClickListener(
        v -> {
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
        });
    return root;
  }

  private static void bindSpinner(MainActivity a, Spinner spinner, String[] items, int index) {
    spinner.setAdapter(new ArrayAdapter<>(a, android.R.layout.simple_spinner_dropdown_item, items));
    spinner.setSelection(Math.max(0, Math.min(items.length - 1, index)));
  }

  /**
   * Week chips over the term's weeks, plus the text field they mirror.
   *
   * The chips are only a view onto the field's text: saving still goes through
   * {@link ScheduleRules#parseWeeks}, so the parser's messages and every existing test are
   * untouched. Typing wins while the field has focus and the chips catch up when it loses focus —
   * syncing on each keystroke would fight half-typed input like "1-".
   */
  private static EditText weekPicker(MainActivity a, Course c, View root) {
    final int total = a.term().weeks;
    final Set<Integer> picked = new TreeSet<>(c.weeks);
    final List<TextView> chips = new ArrayList<>();
    final int[] anchor = {0};

    final EditText field = root.findViewById(R.id.course_weeks);
    field.setText(ScheduleRules.formatWeeks(new ArrayList<>(picked)));

    final Runnable showPicked = () -> field.setText(ScheduleRules.formatWeeks(new ArrayList<>(picked)));
    final Runnable repaint =
        () -> {
          for (int i = 0; i < chips.size(); i++) paintChip(a, chips.get(i), picked.contains(i + 1));
        };

    LinearLayout grid = root.findViewById(R.id.course_week_grid);
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
    int[] quickIds = {R.id.course_week_all, R.id.course_week_odd, R.id.course_week_even};
    for (int index = 0; index < quickIds.length; index++) {
      final int mode = index;
      root.findViewById(quickIds[index]).setOnClickListener(
          v -> {
            picked.clear();
            for (int i = 1; i <= total; i++)
              if (mode == 0 || (mode == 1 ? i % 2 == 1 : i % 2 == 0)) picked.add(i);
            repaint.run();
            showPicked.run();
          });
    }
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
