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
    requireTerm(term);
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

  public boolean hasTerm(long id) {
    for (Term t : terms()) if (t.id == id) return true;
    return false;
  }

  private void requireTerm(long id) {
    if (!hasTerm(id)) throw new IllegalArgumentException("目标学期已不存在，请重新选择学期");
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

  /**
   * Deletes a term and everything filed under it. Children go first: the schema has no foreign key
   * and no cascade, so deleting only the term row would leave rows nothing can ever reach.
   */
  public void deleteTerm(long id) {
    SQLiteDatabase d = getWritableDatabase();
    d.beginTransaction();
    try {
      d.delete("courses", "term_id=?", new String[] {"" + id});
      d.delete("pending", "term_id=?", new String[] {"" + id});
      d.delete("terms", "id=?", new String[] {"" + id});
      d.setTransactionSuccessful();
    } finally {
      d.endTransaction();
    }
  }

  public int courseCount(long term) {
    try (Cursor c =
        getReadableDatabase()
            .rawQuery("SELECT COUNT(*) FROM courses WHERE term_id=?", new String[] {"" + term})) {
      return c.moveToFirst() ? c.getInt(0) : 0;
    }
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

  /**
   * The colour this title uses in this term, or null when the title is new here.
   *
   * "The" colour is the first arrangement's, in {@link #courses} order. Every method below shares
   * that one rule; a second rule would make titles flip colour on each save.
   */
  public String colorFor(long term, String title) {
    for (Course c : courses(term)) if (title.equals(c.title)) return c.color;
    return null;
  }

  /**
   * The colours the titles of this term wear, one entry per title.
   *
   * Used to keep a new course off a colour a different course is already using: {@link
   * CourseColors#pick} takes this set and returns something free. Not the same thing as the raw
   * colours of every row — a title whose arrangements disagree contributes once, its canonical
   * colour, which is what the timetable actually shows.
   */
  public Set<String> colorsInUse(long term) {
    Map<String, String> byTitle = new LinkedHashMap<>();
    for (Course c : courses(term)) byTitle.putIfAbsent(c.title, c.color);
    return new LinkedHashSet<>(byTitle.values());
  }

  public boolean isColorManual(long term, String title) {
    for (Course c : courses(term)) if (title.equals(c.title) && c.colorManual) return true;
    return false;
  }

  /** Refuse stale previews; write every selected title atomically, leaving all others untouched. */
  public void applyColorPlan(long term, List<Course> snapshot, Map<String,String> colors) {
    SQLiteDatabase d = getWritableDatabase();
    d.beginTransaction();
    try {
      List<Course> current = courses(term);
      Map<Long,String> expected = new HashMap<>();
      for(Course c : snapshot) expected.put(c.id,c.json().toString());
      if(current.size()!=expected.size()) throw new IllegalStateException("课表已变化，请重新打开配色预览");
      for(Course c : current) if(!c.json().toString().equals(expected.get(c.id)))
        throw new IllegalStateException("课表已变化，请重新打开配色预览");
      for(String color : colors.values()) if(!CourseColors.valid(color))
        throw new IllegalArgumentException("颜色无效");
      for(Course c : current) if(colors.containsKey(c.title)) {
        c.color = colors.get(c.title); c.colorManual = false; save(c);
      }
      d.setTransactionSuccessful();
    } catch(JSONException e) { throw new IllegalStateException(e); }
    finally { d.endTransaction(); }
  }

  public void markColorManual(long term, String title) {
    SQLiteDatabase d = getWritableDatabase();
    d.beginTransaction();
    try {
      for (Course c : courses(term)) if (title.equals(c.title) && !c.colorManual) {
        c.colorManual = true; save(c);
      }
      d.setTransactionSuccessful();
    } finally { d.endTransaction(); }
  }

  /** Repaints every arrangement of one title. Returns how many rows were written. */
  public int recolorTitle(long term, String title, String color) {
    // Rejects rather than coerces: Course.validate would quietly turn a malformed value into
    // COLORS[0], and writing that through would repaint a whole title group lavender.
    if (!CourseColors.valid(color)) throw new IllegalArgumentException("课程颜色值无效");
    SQLiteDatabase d = getWritableDatabase();
    d.beginTransaction();
    try {
      int changed = 0;
      for (Course c : courses(term))
        if (title.equals(c.title) && !color.equals(c.color)) {
          c.color = color;
          save(c);
          changed++;
        }
      d.setTransactionSuccessful();
      return changed;
    } finally {
      d.endTransaction();
    }
  }

  /** Converges every title in one term onto the colour its first arrangement already uses. */
  public int unifyColors(long term) {
    SQLiteDatabase d = getWritableDatabase();
    d.beginTransaction();
    try {
      int changed = 0;
      Map<String, String> seen = new LinkedHashMap<>();
      for (Course c : courses(term)) {
        String known = seen.get(c.title);
        if (known == null) seen.put(c.title, c.color);
        else if (!known.equals(c.color)) {
          c.color = known;
          save(c);
          changed++;
        }
      }
      d.setTransactionSuccessful();
      return changed;
    } finally {
      d.endTransaction();
    }
  }

  public int unifyColors() {
    int changed = 0;
    for (Term t : terms()) changed += unifyColors(t.id);
    return changed;
  }

  /**
   * Gives the titles of one term a colour each, as far as the palette allows. Repairs the data that
   * predates {@link CourseColors#pick}, where several different courses can wear the same colour.
   *
   * Titles are visited in {@link #courses} order and the first one keeps what it is wearing, so the
   * earliest-scheduled course is the one that does not move; the rest re-pick around it. Run
   * {@link #unifyColors(long)} first, or a title contributes whichever colour its first row has.
   */
  public int spreadColors(long term) {
    SQLiteDatabase d = getWritableDatabase();
    d.beginTransaction();
    try {
      int changed = 0;
      Set<String> decided = new HashSet<>(), used = new HashSet<>();
      for (Course c : courses(term)) {
        if (!decided.add(c.title)) continue; // one decision per title, not per arrangement
        if (used.add(c.color)) continue; // nobody else wears it, so it stays
        String fresh = CourseColors.pick(c.title, used);
        if (fresh.equals(c.color)) continue; // palette exhausted: the clash is unavoidable
        used.add(fresh);
        changed += recolorTitle(term, c.title, fresh);
      }
      d.setTransactionSuccessful();
      return changed;
    } finally {
      d.endTransaction();
    }
  }

  public long save(Course c) {
    requireTerm(c.semesterId);
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
      requireTerm(term);
      // Same title, same colour: seeded from what this term already has, so a re-import can never
      // repaint the schedule the user is looking at.
      Map<String, String> byTitle = new LinkedHashMap<>();
      Set<String> manualTitles = new HashSet<>();
      for (Course e : existing) if (e.colorManual) manualTitles.add(e.title);
      for (Course e : existing)
        if (!byTitle.containsKey(e.title)) byTitle.put(e.title, e.color);
      // Grown as titles are added, so two courses arriving in the same file cannot both take the
      // same free colour.
      Set<String> used = new LinkedHashSet<>(byTitle.values());
      for (Course c : courses) {
        c.id = 0;
        c.semesterId = term;
        // Validate before inheriting: it can rewrite a malformed colour, and that value must not
        // become the seed for the rest of the title group.
        c.validate(term(term).weeks, 16);
        if (!byTitle.containsKey(c.title)) {
          String fresh = CourseColors.pick(c.title, used);
          byTitle.put(c.title, fresh);
          used.add(fresh);
        }
        c.color = byTitle.get(c.title);
        // AI-provided metadata cannot override the existing title's ownership.
        c.colorManual = manualTitles.contains(c.title);
        // Course.same ignores colour, so recolouring above can neither create nor hide a duplicate.
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
      throw new IllegalArgumentException("不是支持的个人课表备份文件（兼容旧版课间）");
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
