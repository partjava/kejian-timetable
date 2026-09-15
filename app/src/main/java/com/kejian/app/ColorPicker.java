package com.kejian.app;

import android.app.AlertDialog;
import android.content.Context;
import android.graphics.Color;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.EditText;
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
    final boolean[] syncing = {false};

    View root = LayoutInflater.from(c).inflate(R.layout.dialog_color_picker, null);
    final TextView preview = root.findViewById(R.id.color_preview);
    final EditText hex = root.findViewById(R.id.color_hex);

    final SeekBar[] bars = new SeekBar[] {
      root.findViewById(R.id.color_red_bar),
      root.findViewById(R.id.color_green_bar),
      root.findViewById(R.id.color_blue_bar)
    };
    final TextView[] values = new TextView[] {
      root.findViewById(R.id.color_red_val),
      root.findViewById(R.id.color_green_val),
      root.findViewById(R.id.color_blue_val)
    };

    for (int i = 0; i < 3; i++) {
      final int channel = i;
      bars[i].setOnSeekBarChangeListener(
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
            if (!text.matches("#[0-9a-fA-F]{6}")) return;
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
      values[i].setText(String.valueOf(rgb[i]));
    }
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
