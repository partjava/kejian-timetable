package com.kejian.app;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * The course palette and the title-to-colour rule.
 *
 * Separate from {@link Course} on purpose: this class touches no Android and no JSON API, so the
 * pure-Java tests can compile and exercise it directly (see {@code tests/run-pure-java.cmd}).
 */
public final class CourseColors {
  /** What a course wears before anything assigns it a colour. */
  public static final String DEFAULT = "#DCD5FF";

  /**
   * Suggested to the AI and used as the seed pool, so it stays short. Deliberately separate from
   * {@link #PALETTE}: this array is interpolated into the model prompt, and growing it would shift
   * the colours the model picks for the same timetable.
   */
  public static final String[] SUGGESTED = {
    "#DCD5FF", "#CBE4FF", "#C5EFE1", "#FFE0C7", "#FFD7E8", "#DDE3FF"
  };

  /**
   * The swatches offered in the editor: ten hue families, each light/mid/deep. The pale column is
   * exactly {@link #SUGGESTED}, so a colour already stored on a course is always found in the
   * picker and shown as the current selection. Order is by hue family — a row per family.
   */
  public static final String[] PALETTE = {
    "#DCD5FF", "#C3B8FF", "#A79BFF", "#CBE4FF", "#A8D3FF", "#7FBDFF",
    "#C5EFE1", "#A5E4D0", "#7FD6BC", "#CFEFC7", "#AFE4A3", "#8BD67C",
    "#FFF0C2", "#FFE59A", "#FFD86B", "#FFE0C7", "#FFCB9E", "#FFB273",
    "#FFD7E8", "#FFB8D3", "#FF92BA", "#FFD9D6", "#FFB9B4", "#FF948C",
    "#DDE3FF", "#BFC9FF", "#9DA9FF", "#E8EAF2", "#D3D7E4", "#B9BFD0"
  };

  /** {@code #RRGGBB}, the only shape the rest of the app accepts. */
  public static boolean valid(String hex) {
    return hex != null && hex.matches("#[0-9a-fA-F]{6}");
  }

  /**
   * The colour a title always gets. Pure function of the name, ported from the retired Python
   * side, so the same course keeps the same colour across imports and devices.
   */
  public static String seed(String title) {
    try {
      byte[] h = MessageDigest.getInstance("SHA-256").digest(title.getBytes(StandardCharsets.UTF_8));
      long v = 0;
      for (int i = 0; i < 8; i++) v = (v << 8) | (h[i] & 0xFFL);
      // floorMod, not %, because the top byte can set bit 63 and Java's % keeps the sign.
      return SUGGESTED[Math.floorMod(v, SUGGESTED.length)];
    } catch (Exception e) {
      return DEFAULT;
    }
  }

  private CourseColors() {}
}
