package com.kejian.app;

import java.util.*;

/** Read-only, deterministic allocation for explicitly selected title groups. */
final class ColorPlan {
  static Map<String,String> create(List<Course> courses, Set<String> selected) {
    Map<String,String> original = new TreeMap<>();
    for (Course c : courses) original.putIfAbsent(c.title,c.color);
    Set<String> used = new LinkedHashSet<>();
    for (Map.Entry<String,String> e : original.entrySet())
      if (!selected.contains(e.getKey())) used.add(e.getValue());
    Map<String,String> changes = new LinkedHashMap<>();
    for (String title : original.keySet()) if (selected.contains(title)) {
      String color = CourseColors.pick(title,used);
      changes.put(title,color); used.add(color);
    }
    return changes;
  }
  private ColorPlan() {}
}
