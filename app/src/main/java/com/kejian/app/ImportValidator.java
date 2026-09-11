package com.kejian.app;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * Checks the shape of a model's reply before anything reaches the database.
 *
 * The app used to rely on the PC service to reject malformed results; with a direct connection
 * that duty is the phone's. This is a port of validate_result in server/server.py, kept strict on
 * purpose: a colour outside the palette or a week outside the term is refused rather than quietly
 * rewritten, because silently repairing a model's output hides a bad answer instead of reporting it.
 */
public final class ImportValidator {
  private static final String REJECTED = "解析结果格式不正确或含有超出范围的值，请检查数据。";
  private static final int MAX_ITEMS = 1000, MAX_STRING = 2000, MAX_WEEKS = 40, MAX_PERIODS = 16;

  private ImportValidator() {}

  /** Returns a normalised result: courses, pending, warnings and semester, all range-checked. */
  public static JSONObject validate(JSONObject result) throws JSONException {
    if (result == null) fail();
    JSONArray courses = array(result, "courses"), pending = array(result, "pending");
    JSONArray warnings = array(result, "warnings");

    JSONArray cleanCourses = new JSONArray();
    for (int i = 0; i < courses.length(); i++) {
      JSONObject c = courses.optJSONObject(i);
      if (c == null) fail();
      JSONObject course = new JSONObject();
      for (String field : new String[] {"title", "teacher", "room", "color", "notes"})
        course.put(field, string(c, field, MAX_STRING));
      if (course.getString("title").trim().isEmpty()
          || !course.getString("color").matches("#[0-9a-fA-F]{6}")) fail();
      int start = integer(c, "start", 1, MAX_PERIODS), end = integer(c, "end", 1, MAX_PERIODS);
      if (end < start) fail();
      course
          .put("day", integer(c, "day", 1, 7))
          .put("start", start)
          .put("end", end)
          .put("weeks", weeks(c));
      cleanCourses.put(course);
    }

    JSONArray cleanPending = new JSONArray();
    for (int i = 0; i < pending.length(); i++) {
      JSONObject p = pending.optJSONObject(i);
      if (p == null) fail();
      cleanPending.put(
          new JSONObject()
              .put("title", string(p, "title", MAX_STRING))
              .put("notes", string(p, "notes", MAX_STRING)));
    }

    JSONObject semester = result.optJSONObject("semester");
    if (semester == null) fail();
    String startDate = string(semester, "startDate", MAX_STRING);
    if (!startDate.isEmpty()) {
      if (!startDate.matches("\\d{4}-\\d{2}-\\d{2}")) fail();
      try {
        LocalDate.parse(startDate);
      } catch (DateTimeParseException e) {
        fail();
      }
    }
    JSONObject cleanSemester =
        new JSONObject()
            .put("name", string(semester, "name", MAX_STRING))
            .put("startDate", startDate)
            .put("totalWeeks", integer(semester, "totalWeeks", 1, MAX_WEEKS));

    JSONArray cleanWarnings = new JSONArray();
    for (int i = 0; i < warnings.length(); i++) {
      Object w = warnings.opt(i);
      if (!(w instanceof String) || ((String) w).length() > MAX_STRING) fail();
      cleanWarnings.put(w);
    }

    return new JSONObject()
        .put("courses", cleanCourses)
        .put("pending", cleanPending)
        .put("warnings", cleanWarnings)
        .put("semester", cleanSemester)
        .put("mode", "ai");
  }

  private static JSONArray array(JSONObject result, String key) throws JSONException {
    JSONArray value = result.optJSONArray(key);
    if (value == null || value.length() > MAX_ITEMS) fail();
    return value;
  }

  private static String string(JSONObject o, String key, int max) throws JSONException {
    Object value = o.opt(key);
    if (!(value instanceof String) || ((String) value).length() > max) fail();
    return (String) value;
  }

  /**
   * Rejects anything that is not an integral JSON number. A string "5" or a float 5.0 would both
   * coerce silently, so the type is checked rather than the converted value.
   */
  private static int integer(JSONObject o, String key, int low, int high) throws JSONException {
    Object value = o.opt(key);
    if (!(value instanceof Integer) && !(value instanceof Long)) fail();
    long n = ((Number) value).longValue();
    if (n < low || n > high) fail();
    return (int) n;
  }

  private static JSONArray weeks(JSONObject c) throws JSONException {
    Object value = c.opt("weeks");
    if (!(value instanceof JSONArray)) fail();
    JSONArray raw = (JSONArray) value;
    if (raw.length() < 1 || raw.length() > MAX_WEEKS) fail();
    // A TreeSet drops duplicates and orders the result, matching the server's sorted(set(weeks)).
    TreeSet<Integer> unique = new TreeSet<>();
    for (int i = 0; i < raw.length(); i++) {
      Object w = raw.opt(i);
      if (!(w instanceof Integer) && !(w instanceof Long)) fail();
      long n = ((Number) w).longValue();
      if (n < 1 || n > MAX_WEEKS) fail();
      unique.add((int) n);
    }
    List<Integer> sorted = new ArrayList<>(unique);
    return new JSONArray(sorted);
  }

  private static void fail() {
    throw new IllegalArgumentException(REJECTED);
  }
}
