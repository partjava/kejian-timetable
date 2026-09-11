package com.kejian.app;

import android.app.AlertDialog;
import android.content.Context;
import android.graphics.Color;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.Gravity;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;

/** RGB sliders plus a hex field, with a live preview so contrast is visible before saving. */
public final class ColorPicker {
  public interface Picked {
    void accept(String hex);
  }

  private ColorPicker() {}

  public static void open(Context c, String current, Picked callback) {
    final int[] rgb = parse(current);
    // Guards the three-way sync below: each widget writes to rgb, then one render writes back to
    // all three, and without this the write-back would re-enter as if the user had moved something.
    final boolean[] syncing = {false};

    LinearLayout root = Ui.col(c);
    Ui.pad(root, 22, 12);
    root.addView(Ui.text(c, "自定义颜色", 21, Ui.INK, true));
    Ui.gap(root, 16);

    final TextView preview = Ui.text(c, "示例文字 Aa 123", 16, Ui.INK, true);
    preview.setGravity(Gravity.CENTER);
    root.addView(preview, new LinearLayout.LayoutParams(-1, Ui.dp(c, 72)));
    Ui.gap(root, 6);
    root.addView(Ui.text(c, "上面的字会自动变成看得清的颜色", 12, Ui.MUTED, false));
    Ui.gap(root, 16);

    final EditText hex = new EditText(c);
    hex.setTextSize(15);
    hex.setTextColor(Ui.INK);
    hex.setSingleLine(true);
    hex.setInputType(InputType.TYPE_CLASS_TEXT);
    hex.setBackground(Ui.border(c, Ui.BG, 12));
    Ui.pad(hex, 12, 12);
    root.addView(Ui.text(c, "十六进制", 13, Ui.MUTED, false));
    Ui.gap(root, 7);
    root.addView(hex, new LinearLayout.LayoutParams(-1, Ui.dp(c, 48)));
    Ui.gap(root, 16);

    final SeekBar[] bars = new SeekBar[3];
    final TextView[] values = new TextView[3];
    String[] names = {"红 R", "绿 G", "蓝 B"};
    for (int i = 0; i < 3; i++) {
      final int channel = i;
      LinearLayout row = Ui.row(c);
      row.addView(
          Ui.text(c, names[i], 14, Ui.INK, true),
          new LinearLayout.LayoutParams(Ui.dp(c, 46), -2));
      SeekBar bar = new SeekBar(c);
      bar.setMax(255);
      bar.setContentDescription(names[i]);
      row.addView(bar, new LinearLayout.LayoutParams(0, -2, 1));
      TextView value = Ui.text(c, "", 14, Ui.MUTED, false);
      value.setGravity(Gravity.END);
      row.addView(value, new LinearLayout.LayoutParams(Ui.dp(c, 42), -2));
      root.addView(row);
      bars[i] = bar;
      values[i] = value;
      bar.setOnSeekBarChangeListener(
          new SeekBar.OnSeekBarChangeListener() {
            public void onProgressChanged(SeekBar b, int progress, boolean fromUser) {
              if (syncing[0] || !fromUser) return;
              rgb[channel] = progress;
              render(c, rgb, bars, values, hex, preview, syncing);
            }

            public void onStartTrackingTouch(SeekBar b) {}

            public void onStopTrackingTouch(SeekBar b) {}
          });
    }

    hex.addTextChangedListener(
        new TextWatcher() {
          public void beforeTextChanged(CharSequence s, int a, int b, int d) {}

          public void onTextChanged(CharSequence s, int a, int b, int d) {
            if (syncing[0]) return;
            String text = s.toString().trim();
            if (!text.startsWith("#")) text = "#" + text;
            if (!text.matches("#[0-9a-fA-F]{6}")) return; // half-typed: wait for the sixth digit
            int[] parsed = parse(text);
            System.arraycopy(parsed, 0, rgb, 0, 3);
            render(c, rgb, bars, values, hex, preview, syncing);
          }

          public void afterTextChanged(Editable e) {}
        });

    render(c, rgb, bars, values, hex, preview, syncing);
    new AlertDialog.Builder(c)
        .setView(root)
        .setNegativeButton("取消", null)
        .setPositiveButton("确定", (d, w) -> callback.accept(toHex(rgb[0], rgb[1], rgb[2])))
        .show();
  }

  private static void render(
      Context c,
      int[] rgb,
      SeekBar[] bars,
      TextView[] values,
      EditText hex,
      TextView preview,
      boolean[] syncing) {
    syncing[0] = true;
    String value = toHex(rgb[0], rgb[1], rgb[2]);
    for (int i = 0; i < 3; i++) {
      bars[i].setProgress(rgb[i]);
      values[i].setText("" + rgb[i]);
    }
    // Case-insensitive so typing an uppercase hex is not yanked back mid-edit.
    if (!hex.getText().toString().trim().equalsIgnoreCase(value)) hex.setText(value);
    preview.setTextColor(Ui.inkOn(value));
    preview.setBackground(Ui.bg(Color.parseColor(value), Ui.dp(c, 16)));
    syncing[0] = false;
  }

  private static int[] parse(String hex) {
    int color;
    try {
      color = Color.parseColor(hex);
    } catch (Exception e) {
      color = Color.parseColor(CourseColors.DEFAULT);
    }
    return new int[] {Color.red(color), Color.green(color), Color.blue(color)};
  }

  private static String toHex(int r, int g, int b) {
    return String.format(java.util.Locale.ROOT, "#%02x%02x%02x", r, g, b);
  }
}
