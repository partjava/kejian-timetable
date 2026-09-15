package com.kejian.app;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

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
   * Suggested to the AI. Deliberately separate from {@link #PALETTE}: this array is interpolated
   * into the model prompt, and growing it would shift the colours the model picks for the same
   * timetable. The model's own colours are advisory anyway — see {@link #seed} and {@link #pick}.
   */
  public static final String[] SUGGESTED = {
    "#DCD5FF", "#CBE4FF", "#C5EFE1", "#FFE0C7", "#FFD7E8", "#DDE3FF"
  };

  /**
   * The swatches offered in the editor: ten hue families, each light/mid/deep. The pale column is
   * exactly {@link #SUGGESTED}, so a colour already stored on a course is always found in the
   * picker and shown as the current selection. Rows group related hues for manual selection;
   * automatic allocation compares the actual gradient colors instead of relying on row order.
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
   * The colour a title would wear if nothing else were on the timetable: a pure function of the
   * name, ported from the retired Python side, so a course keeps its colour across imports and
   * devices. It is a preference, not a promise — {@link #pick} moves a title off it when another
   * course in the same term already wears it.
   */
  public static String seed(String title) {
    return PALETTE[seedIndex(title)];
  }

  /**
   * The colour a title new to a term should wear, given the colours its neighbours already wear.
   *
   * Maximize the minimum displayed-color distance from existing courses. The title seed breaks
   * ties deterministically. Compare gradient endpoints and accents, not only stored RGB values.
   */
  public static String pick(String title, Collection<String> taken) {
    int at = seedIndex(title);
    Set<String> used = new HashSet<>();
    if (taken != null) for (String color : taken)
      if (valid(color)) used.add(color.toUpperCase(Locale.ROOT));
    if (used.isEmpty()) return PALETTE[at];
    String best = PALETTE[at];
    double bestScore = -1;
    for (int i = 0; i < PALETTE.length; i++) {
      String candidate = PALETTE[(at + i) % PALETTE.length];
      if (used.contains(candidate)) continue;
      double nearest = Double.POSITIVE_INFINITY;
      for (String color : used) nearest = Math.min(nearest, displayDistance(candidate, color));
      if (nearest > bestScore + 0.000001) { best = candidate; bestScore = nearest; }
    }
    return best;
  }

  /** Approximate visual separation; not a promise of equal perception or color-blind distinction. */
  public static double displayDistance(String first, String second) {
    int[] a = gradient((int)Long.parseLong(first.substring(1), 16));
    int[] b = gradient((int)Long.parseLong(second.substring(1), 16));
    double sum = 0;
    for (int i = 0; i < 3; i++) {
      double r = ((a[i] >> 16) & 255) - ((b[i] >> 16) & 255);
      double g = ((a[i] >> 8) & 255) - ((b[i] >> 8) & 255);
      double blue = (a[i] & 255) - (b[i] & 255);
      sum += (i == 2 ? 0.35 : 1) * (r*r + g*g + blue*blue);
    }
    return sum;
  }

  /** Shared by allocation and Canvas: left endpoint, right endpoint, accent (opaque ARGB). */
  public static int[] gradient(int color) {
    float r = ((color >> 16) & 255) / 255f, g = ((color >> 8) & 255) / 255f, b = (color & 255) / 255f;
    float max = Math.max(r, Math.max(g,b)), min = Math.min(r, Math.min(g,b)), delta = max-min;
    float sat = max == 0 ? 0 : delta/max;
    if (sat < .05f) return new int[]{0xFFDEE2EB, 0xFFEEF0F6, 0xFF788094};
    float hue = max == r ? (g-b)/delta : max == g ? (b-r)/delta+2 : (r-g)/delta+4;
    hue = (hue * 60 + 360) % 360;
    float leftSat = Math.max(.44f, Math.min(.65f, sat*1.9f));
    return new int[]{hsv(hue,leftSat,.95f), hsv(hue,Math.max(.18f,leftSat*.38f),.98f),
        hsv(hue,Math.max(.72f,Math.min(.96f,sat*2.2f)),.82f)};
  }

  private static int hsv(float hue,float sat,float val) {
    float chroma = val*sat, x = chroma*(1-Math.abs((hue/60)%2-1)), m = val-chroma;
    float r=0,g=0,b=0;
    if(hue<60){r=chroma;g=x;} else if(hue<120){r=x;g=chroma;}
    else if(hue<180){g=chroma;b=x;} else if(hue<240){g=x;b=chroma;}
    else if(hue<300){r=x;b=chroma;} else {r=chroma;b=x;}
    return 0xFF000000 | Math.round((r+m)*255)<<16 | Math.round((g+m)*255)<<8 | Math.round((b+m)*255);
  }

  /**
   * Where in {@link #PALETTE} a title's own colour sits. Index 0 rather than an exception when the
   * digest is unavailable, because {@code PALETTE[0]} is {@link #DEFAULT}.
   */
  private static int seedIndex(String title) {
    try {
      byte[] h = MessageDigest.getInstance("SHA-256").digest(title.getBytes(StandardCharsets.UTF_8));
      long v = 0;
      for (int i = 0; i < 8; i++) v = (v << 8) | (h[i] & 0xFFL);
      // floorMod, not %, because the top byte can set bit 63 and Java's % keeps the sign.
      return Math.floorMod(v, PALETTE.length);
    } catch (Exception e) {
      return 0;
    }
  }

  private CourseColors() {}
}
