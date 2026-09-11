package com.kejian.app;

import java.util.*;

/** Pure Java rules, shared by editing, import validation and conflict checks. */
public final class ScheduleRules {
  private ScheduleRules() {}

  public static List<Integer> parseWeeks(String input, int max) {
    String s =
        input
            .trim()
            .replace("周", "")
            .replace("第", "")
            .replace('，', ',')
            .replace('、', ',')
            .replace('–', '-')
            .replace('—', '-')
            .replace('至', '-')
            .replaceAll("\\s+", "");
    if (s.isEmpty()) throw new IllegalArgumentException("请填写上课周次，例如 1-3,5-17");
    TreeSet<Integer> out = new TreeSet<>();
    for (String part : s.split(",", -1)) {
      String[] p = part.split("-", -1);
      if (p.length > 2 || p.length == 0) throw new IllegalArgumentException("周次格式应为 1-3,5-17");
      int a, b;
      try {
        a = Integer.parseInt(p[0]);
        b = p.length == 2 ? Integer.parseInt(p[1]) : a;
      } catch (NumberFormatException e) {
        throw new IllegalArgumentException("周次格式应为 1-3,5-17");
      }
      if (a < 1 || b < a || b > max)
        throw new IllegalArgumentException("周次必须在1至" + max + "周内，且起始不大于结束");
      for (int i = a; i <= b; i++) out.add(i);
    }
    return new ArrayList<>(out);
  }

  public static String formatWeeks(List<Integer> weeks) {
    if (weeks.isEmpty()) return "";
    List<Integer> sorted = new ArrayList<>(new TreeSet<>(weeks));
    StringBuilder s = new StringBuilder();
    for (int i = 0; i < sorted.size(); i++) {
      int start = sorted.get(i), end = start;
      while (i + 1 < sorted.size() && sorted.get(i + 1) == end + 1) end = sorted.get(++i);
      if (s.length() > 0) s.append(',');
      s.append(start);
      if (end != start) s.append('-').append(end);
    }
    return s.toString();
  }

  public static boolean overlap(
      int d1, int s1, int e1, List<Integer> w1, int d2, int s2, int e2, List<Integer> w2) {
    return d1 == d2 && s1 <= e2 && s2 <= e1 && !Collections.disjoint(w1, w2);
  }
}
