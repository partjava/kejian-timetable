package com.kejian.app;

import android.content.*;
import android.database.Cursor;
import android.database.sqlite.*;
import java.time.*;
import java.util.*;
import org.json.*;

public class ScheduleDb extends SQLiteOpenHelper {
  public static class Term {
    public long id;
    public String name, start;
    public int weeks;

    public Term(long i, String n, String s, int w) {
      id = i;
      name = n;
      start = s;
      weeks = w;
    }
  }

  public ScheduleDb(Context c) {
    super(c, "kejian.db", null, 2);
  }

  public ScheduleDb(Context c, String databaseName) {
    super(c, databaseName, null, 2);
  }

  @Override
  public void onCreate(SQLiteDatabase d) {
    d.execSQL(
        "CREATE TABLE terms(id INTEGER PRIMARY KEY AUTOINCREMENT,name TEXT NOT NULL,start TEXT NOT"
            + " NULL,weeks INTEGER NOT NULL)");
    d.execSQL(
        "CREATE TABLE courses(id INTEGER PRIMARY KEY AUTOINCREMENT,term_id INTEGER NOT NULL,data"
            + " TEXT NOT NULL)");
    d.execSQL("CREATE INDEX courses_term ON courses(term_id)");
    createPending(d);
    LocalDate today = LocalDate.now();
    int month = today.getMonthValue();
    boolean autumn = month >= 8;
    int year = today.getYear();
    LocalDate start =
        LocalDate.of(year, autumn ? 9 : 2, 1)
            .with(java.time.temporal.TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
    d.execSQL(
        "INSERT INTO terms(name,start,weeks) VALUES(?,?,20)",
        new Object[] {year + (autumn ? " 秋季学期" : " 春季学期"), start.toString()});
  }

  private static void createPending(SQLiteDatabase d) {
    d.execSQL(
        "CREATE TABLE pending(id INTEGER PRIMARY KEY AUTOINCREMENT,term_id INTEGER NOT NULL,data"
            + " TEXT NOT NULL)");
  }

  @Override
  public void onUpgrade(SQLiteDatabase d, int a, int b) {
    if (a == 1 && b == 2) createPending(d);
    else throw new IllegalStateException("数据库版本不支持");
  }

  public JSONArray pending(long term) throws JSONException {
    JSONArray out = new JSONArray();
    try (Cursor c =
        getReadableDatabase()
            .rawQuery(
                "SELECT id,data FROM pending WHERE term_id=? ORDER BY id",
                new String[] {"" + term})) {
      while (c.moveToNext()) out.put(new JSONObject(c.getString(1)).put("id", c.getLong(0)));
    }
    return out;
  }

  public void savePending(long term, JSONArray items) throws JSONException {
    JSONArray existing = pending(term);
    for (int i = 0; i < items.length(); i++) {
      JSONObject item = items.getJSONObject(i);
      String title = item.getString("title"), notes = item.optString("notes", "");
      boolean duplicate = false;
      for (int k = 0; k < existing.length(); k++) {
        JSONObject old = existing.getJSONObject(k);
        if (title.equals(old.optString("title")) && notes.equals(old.optString("notes"))) {
          duplicate = true;
          break;
        }
      }
      if (!duplicate) {
        JSONObject clean = new JSONObject().put("title", title).put("notes", notes);
        ContentValues v = new ContentValues();
        v.put("term_id", term);
        v.put("data", clean.toString());
        getWritableDatabase().insertOrThrow("pending", null, v);
        existing.put(clean);
      }
    }
  }

  public void deletePending(long id) {
    getWritableDatabase().delete("pending", "id=?", new String[] {"" + id});
  }

  public int importResult(List<Course> courses, long term, JSONArray pending) throws JSONException {
    SQLiteDatabase d = getWritableDatabase();
    d.beginTransaction();
    try {
      int count = importCourses(courses, term);
      savePending(term, pending);
      d.setTransactionSuccessful();
      return count;
    } finally {
      d.endTransaction();
    }
  }

  public List<Term> terms() {
    List<Term> out = new ArrayList<>();
    try (Cursor c =
        getReadableDatabase()
            .rawQuery("SELECT id,name,start,weeks FROM terms ORDER BY id DESC", null)) {
      while (c.moveToNext())
        out.add(new Term(c.getLong(0), c.getString(1), c.getString(2), c.getInt(3)));
    }
    return out;
  }

  public Term term(long id) {
    for (Term t : terms()) if (t.id == id) return t;
    return terms().get(0);
  }

  public long saveTerm(Term t) {
    if (LocalDate.parse(t.start).getDayOfWeek() != DayOfWeek.MONDAY)
      throw new IllegalArgumentException("学期起始日期应为第一教学周的周一");
    if (t.name.trim().isEmpty() || t.weeks < 1 || t.weeks > 40)
      throw new IllegalArgumentException("学期名称不能为空，周数应为1–40");
    ContentValues v = new ContentValues();
    v.put("name", t.name.trim());
    v.put("start", t.start);
    v.put("weeks", t.weeks);
    if (t.id == 0) return getWritableDatabase().insertOrThrow("terms", null, v);
    getWritableDatabase().update("terms", v, "id=?", new String[] {"" + t.id});
    return t.id;
  }

  public List<Course> courses(long term) {
    List<Course> out = new ArrayList<>();
    try (Cursor c =
        getReadableDatabase()
            .rawQuery(
                "SELECT id,data FROM courses WHERE term_id=? ORDER BY id",
                new String[] {"" + term})) {
      while (c.moveToNext()) {
        try {
          Course v = Course.from(new JSONObject(c.getString(1)));
          v.id = c.getLong(0);
          v.semesterId = term;
          out.add(v);
        } catch (JSONException e) {
          throw new IllegalStateException("课程数据损坏", e);
        }
      }
    }
    out.sort(Comparator.comparingInt((Course c) -> c.day).thenComparingInt(c -> c.start));
    return out;
  }

  public long save(Course c) {
    try {
      c.validate(term(c.semesterId).weeks, 16);
      ContentValues v = new ContentValues();
      v.put("term_id", c.semesterId);
      v.put("data", c.json().toString());
      if (c.id == 0) c.id = getWritableDatabase().insertOrThrow("courses", null, v);
      else getWritableDatabase().update("courses", v, "id=?", new String[] {"" + c.id});
      return c.id;
    } catch (JSONException e) {
      throw new IllegalStateException(e);
    }
  }

  public void delete(long id) {
    getWritableDatabase().delete("courses", "id=?", new String[] {"" + id});
  }

  public int importCourses(List<Course> courses, long term) {
    SQLiteDatabase d = getWritableDatabase();
    int count = 0;
    d.beginTransaction();
    try {
      List<Course> existing = courses(term);
      for (Course c : courses) {
        c.id = 0;
        c.semesterId = term;
        c.validate(term(term).weeks, 16);
        boolean duplicate = false;
        for (Course e : existing)
          if (c.same(e)) {
            duplicate = true;
            break;
          }
        if (!duplicate) {
          save(c);
          existing.add(c);
          count++;
        }
      }
      d.setTransactionSuccessful();
      return count;
    } finally {
      d.endTransaction();
    }
  }

  public JSONObject backup() throws JSONException {
    JSONArray a = new JSONArray();
    for (Term t : terms()) {
      JSONArray cs = new JSONArray();
      for (Course c : courses(t.id)) cs.put(c.json());
      a.put(
          new JSONObject()
              .put("name", t.name)
              .put("start", t.start)
              .put("weeks", t.weeks)
              .put("courses", cs)
              .put("pending", pending(t.id)));
    }
    return new JSONObject().put("format", "kejian-backup").put("version", 1).put("terms", a);
  }

  /** Restore as new terms; never destroys existing user records. */
  public int restore(JSONObject j) throws JSONException {
    if (!"kejian-backup".equals(j.optString("format")) || j.optInt("version") != 1)
      throw new IllegalArgumentException("不是支持的课间备份文件");
    JSONArray ts = j.getJSONArray("terms");
    if (ts.length() == 0 || ts.length() > 100) throw new IllegalArgumentException("备份学期数量无效");
    SQLiteDatabase d = getWritableDatabase();
    d.beginTransaction();
    int n = 0;
    try {
      for (int i = 0; i < ts.length(); i++) {
        JSONObject t = ts.getJSONObject(i);
        Term term =
            new Term(0, t.getString("name") + "（恢复）", t.getString("start"), t.getInt("weeks"));
        long id = saveTerm(term);
        JSONArray cs = t.getJSONArray("courses");
        if (cs.length() > 5000) throw new IllegalArgumentException("备份课程过多");
        for (int k = 0; k < cs.length(); k++) {
          Course c = Course.from(cs.getJSONObject(k));
          c.id = 0;
          c.semesterId = id;
          save(c);
          n++;
        }
        JSONArray ps = t.optJSONArray("pending");
        if (ps != null) {
          if (ps.length() > 1000) throw new IllegalArgumentException("待补充事项过多");
          savePending(id, ps);
        }
      }
      d.setTransactionSuccessful();
      return n;
    } finally {
      d.endTransaction();
    }
  }
}
