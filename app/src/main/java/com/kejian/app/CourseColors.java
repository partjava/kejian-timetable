package com.kejian.app;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Collection;

/**
 * The course palette and the title-to-colour rule.
 *
 * Separate from {@link Course} on purpose: this class touches no Android and no JSON API, so the
 * pure-Java tests can compile and exercise it directly (see {@code tests/run-pure-java.cmd}).
 */
public final class CourseColors {
  /** What a course wears before anything assigns it a colour. */
  public static final String DEFAULT = "#DCD5FF";

  /** How many palette entries share a hue family; {@link #PALETTE} is laid out one family per row. */
  private static final int FAMILY = 3;

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
   * picker and shown as the current selection. Order is by hue family — a row per family, which
   * {@link #pick} relies on when it has to move a title off a colour someone else is wearing.
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
   * Its own {@link #seed} when that is free; otherwise the nearest free colour in the same hue
   * family, so a course that has to move stays recognisably itself; otherwise the first free one
   * anywhere. Only when the whole palette is spoken for does it give up and return the seed, which
   * is the one case where two titles can share a colour.
   */
  public static String pick(String title, Collection<String> taken) {
    int at = seedIndex(title);
    String preferred = PALETTE[at];
    if (taken == null || !taken.contains(preferred)) return preferred;
    int base = at - at % FAMILY;
    for (int i = 0; i < FAMILY; i++) if (!taken.contains(PALETTE[base + i])) return PALETTE[base + i];
    for (String candidate : PALETTE) if (!taken.contains(candidate)) return candidate;
    return preferred;
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
