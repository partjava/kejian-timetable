package com.kejian.app;

import java.util.*;
import org.json.*;

/** One teaching arrangement. A course title may have several rooms/week sets. */
public class Course {
  public long id, semesterId;
  public boolean colorManual = false;
  public String title = "", teacher = "", room = "", color = CourseColors.DEFAULT, notes = "";
  public int day = 1, start = 1, end = 2;
  public List<Integer> weeks = new ArrayList<>();
  public static final String[] DAYS = {"周一", "周二", "周三", "周四", "周五", "周六", "周日"};

  public JSONObject json() throws JSONException {
    return new JSONObject()
        .put("id", id)
        .put("semesterId", semesterId)
        .put("title", title)
        .put("teacher", teacher)
        .put("room", room)
        .put("color", color)
        .put("colorManual", colorManual)
        .put("notes", notes)
        .put("day", day)
        .put("start", start)
        .put("end", end)
        .put("weeks", new JSONArray(weeks));
  }

  public static Course from(JSONObject j) throws JSONException {
    Course c = new Course();
    c.id = j.optLong("id");
    c.semesterId = j.optLong("semesterId");
    c.title = j.getString("title").trim();
    c.teacher = j.optString("teacher", "");
    c.room = j.optString("room", "");
    c.notes = j.optString("notes", "");
    c.color = j.optString("color", CourseColors.DEFAULT);
    // Older backups cannot distinguish hand-picked colors: preserve them by default.
    c.colorManual = j.optBoolean("colorManual", true);
    c.day = j.getInt("day");
    c.start = j.getInt("start");
    c.end = j.getInt("end");
    JSONArray w = j.getJSONArray("weeks");
    for (int i = 0; i < w.length(); i++) c.weeks.add(w.getInt(i));
    c.validate(40, 16);
    return c;
  }

  /** Copy also supports incomplete editor drafts; validation happens on save. */
  public Course copy() {
    Course c = new Course();
    c.id = id;
    c.semesterId = semesterId;
    c.title = title;
    c.teacher = teacher;
    c.room = room;
    c.color = color;
    c.colorManual = colorManual;
    c.notes = notes;
    c.day = day;
    c.start = start;
    c.end = end;
    c.weeks = new ArrayList<>(weeks);
    return c;
  }

  public void validate(int maxWeeks, int maxPeriods) {
    if (title.isEmpty() || title.length() > 120)
      throw new IllegalArgumentException("课程名不能为空，且不能超过120字");
    if (day < 1 || day > 7 || start < 1 || end < start || end > maxPeriods)
      throw new IllegalArgumentException("请检查星期和节次，当前每天" + maxPeriods + "节");
    if (weeks.isEmpty()) throw new IllegalArgumentException("至少选择一个上课周次");
    for (int w : weeks)
      if (w < 1 || w > maxWeeks) throw new IllegalArgumentException("上课周次超出当前学期范围");
    if (!CourseColors.valid(color)) color = CourseColors.DEFAULT;
    weeks = new ArrayList<>(new TreeSet<>(weeks));
    if (teacher.length() > 120 || room.length() > 200 || notes.length() > 2000)
      throw new IllegalArgumentException("教师、教室或备注过长");
  }

  public String when() {
    return DAYS[day - 1] + " · 第" + start + (end == start ? "" : "–" + end) + "节";
  }

  public boolean conflicts(Course c) {
    return semesterId == c.semesterId
        && ScheduleRules.overlap(day, start, end, weeks, c.day, c.start, c.end, c.weeks);
  }

  public boolean same(Course c) {
    return title.equals(c.title)
        && teacher.equals(c.teacher)
        && room.equals(c.room)
        && day == c.day
        && start == c.start
        && end == c.end
        && weeks.equals(c.weeks);
  }
}
