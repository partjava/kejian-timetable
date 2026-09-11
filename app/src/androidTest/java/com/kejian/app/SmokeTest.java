package com.kejian.app;

import android.app.*;
import android.content.*;
import android.graphics.Bitmap;
import android.os.*;
import android.view.*;
import android.widget.*;
import java.io.*;
import java.util.*;
import org.json.*;

/** Device checks use a separate test database and restore display preferences. */
public class SmokeTest extends Instrumentation {
  private int checks;
  private MainActivity activity;
  private File captures;
  private boolean originalWeekends;

  @Override
  public void onCreate(Bundle args) {
    super.onCreate(args);
    start();
  }

  private void require(boolean ok, String message) {
    checks++;
    if (!ok) throw new AssertionError(message);
  }

  @Override
  public void onStart() {
    Bundle out = new Bundle();
    try {
      modelChecks();
      colorChecks();
      localImportChecks();
      Intent intent = new Intent(getTargetContext(), MainActivity.class);
      intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
      activity = (MainActivity) startActivitySync(intent);
      waitForIdleSync();
      editorBoundsChecks();
      captures = new File(getTargetContext().getExternalFilesDir(null), "qa-captures");
      if (!captures.exists() && !captures.mkdirs())
        throw new IOException("Cannot create capture directory");
      originalWeekends = activity.prefs.getBoolean("weekends", true);
      int before = activity.db.courses(activity.termId).size();
      ui(
          () -> {
            activity.prefs.edit().putBoolean("weekends", true).commit();
            activity.showTab(0);
          });
      require(hasText("周六") && hasText("周日"), "seven-day columns visible");
      shot("01-week-seven.png");
      ui(() -> activity.showTab(3));
      ui(
          () -> {
            Switch s = (Switch) findDesc(activity.getWindow().getDecorView(), "显示周末");
            if (s == null) throw new AssertionError("weekend switch missing");
            s.setChecked(false);
          });
      require(!activity.prefs.getBoolean("weekends", true), "switch persisted");
      ui(() -> activity.showTab(0));
      require(!hasText("周六") && !hasText("周日"), "weekend columns hidden");
      require(activity.db.courses(activity.termId).size() == before, "hide keeps all courses");
      shot("02-week-five.png");
      ui(() -> activity.showTab(1));
      require(hasText("今日"), "today screen");
      shot("03-today.png");
      ui(() -> activity.showTab(2));
      require(hasText("全部课程"), "course list screen");
      shot("04-courses.png");
      if (before > 0) {
        Course sample = activity.db.courses(activity.termId).get(0);
        ui(() -> activity.showDetail(sample));
        require(hasText("课程详情"), "detail screen");
        shot("05-detail.png");
        ui(() -> activity.editCourse(sample));
        shot("06-editor.png");
        sendKeyDownUpSync(KeyEvent.KEYCODE_BACK);
        waitForIdleSync();
      }
      ui(() -> activity.showTerms());
      require(hasText("学期管理"), "terms screen");
      shot("07-terms.png");
      ui(() -> activity.showTab(3));
      ui(() -> clickText("作息时间"));
      require(hasText("保存作息"), "time settings screen");
      shot("08-times.png");
      ui(() -> activity.showTab(3));
      shot("09-settings.png");
      ImportController importer = new ImportController(activity);
      ui(importer::open);
      require(hasText("智能导入"), "import screen");
      require(hasText("开始识别"), "import screen offers recognition");
      shot("10-import.png");
      ui(importer::settings);
      require(hasText("AI 配置"), "AI settings screen");
      require(hasText("API 地址") && hasText("模型名称") && hasText("API 密钥"), "AI settings fields");
      require(hasText("测试连接") && hasText("保存配置"), "AI settings actions");
      shot("10b-ai-config.png");
      ui(
          () -> {
            activity.prefs.edit().putBoolean("weekends", originalWeekends).commit();
            activity.showTab(0);
          });
      out.putString(
          "stream",
          "\nPASS: " + checks + " device checks; screenshots saved to " + captures + "\n");
      finish(Activity.RESULT_OK, out);
    } catch (Throwable e) {
      out.putString(
          "stream",
          "\nFAIL after "
              + checks
              + " checks: "
              + e
              + "\n"
              + android.util.Log.getStackTraceString(e));
      finish(Activity.RESULT_CANCELED, out);
    }
  }

  private void modelChecks() throws Exception {
    Context c = getTargetContext();
    String name = "kejian-smoke-test.db";
    c.deleteDatabase(name);
    ScheduleDb db = new ScheduleDb(c, name);
    try {
      long term = db.terms().get(0).id;
      Course one = new Course();
      one.title = "测试课程";
      one.teacher = "测试教师";
      one.room = "A101";
      one.day = 6;
      one.start = 11;
      one.end = 12;
      one.weeks = ScheduleRules.parseWeeks("1-3,5", 20);
      one.semesterId = term;
      long id = db.save(one);
      require(id > 0, "insert id");
      require(db.courses(term).size() == 1, "sqlite insert");
      Course read = db.courses(term).get(0);
      require(read.weeks.equals(Arrays.asList(1, 2, 3, 5)), "sqlite week gap preserved");
      read.room = "B202";
      db.save(read);
      require(db.courses(term).get(0).room.equals("B202"), "sqlite edit");
      require(db.importCourses(Arrays.asList(read.copy()), term) == 0, "duplicate import skipped");
      Course later = read.copy();
      later.id = 0;
      later.room = "C303";
      later.weeks = ScheduleRules.parseWeeks("14-17", 20);
      db.save(later);
      require(db.courses(term).size() == 2, "same title multiple week rooms");
      require(!read.conflicts(later), "disjoint weeks no conflict");
      JSONArray pending =
          new JSONArray().put(new JSONObject().put("title", "待确认课程").put("notes", "缺少星期"));
      db.savePending(term, pending);
      db.savePending(term, pending);
      require(db.pending(term).length() == 1, "pending deduplicated");
      JSONObject backup = db.backup();
      int count = db.restore(backup);
      require(count == 2, "backup restores courses");
      require(db.terms().size() == 2, "restore non destructive");
      long restored = db.terms().get(0).id;
      require(db.pending(restored).length() == 1, "backup restores pending");
      int oldTerms = db.terms().size();
      JSONObject bad = new JSONObject(backup.toString());
      bad.getJSONArray("terms")
          .getJSONObject(0)
          .getJSONArray("courses")
          .getJSONObject(0)
          .put("day", 9);
      try {
        db.restore(bad);
        throw new AssertionError("invalid backup accepted");
      } catch (IllegalArgumentException expected) {
        checks++;
      }
      require(db.terms().size() == oldTerms, "invalid restore rolls back term");
      db.delete(id);
      require(db.courses(term).size() == 1, "delete single arrangement");
      require(db.courses(restored).size() == 2, "semester isolation");
    } finally {
      db.close();
      c.deleteDatabase(name);
    }
  }

  /**
   * One title, one colour, plus term deletion. Runs on its own database so the user's timetable is
   * never touched.
   */
  private void colorChecks() throws Exception {
    Context c = getTargetContext();
    String name = "kejian-color-test.db";
    c.deleteDatabase(name);
    ScheduleDb db = new ScheduleDb(c, name);
    try {
      long term = db.terms().get(0).id;

      // Import seeds a fresh title from its name, and every arrangement of that title agrees.
      List<Course> batch = new ArrayList<>();
      for (int i = 0; i < 3; i++) batch.add(sample("高等数学", 1 + i, term, "1-3"));
      batch.add(sample("大学英语", 5, term, "1-3"));
      require(db.importCourses(batch, term) == 3, "colour fixture imported");
      String maths = db.colorFor(term, "高等数学");
      require(CourseColors.valid(maths), "seeded colour is well formed");
      require(maths.equals(CourseColors.seed("高等数学")), "fresh title takes the seed colour");
      for (Course x : db.courses(term))
        if (x.title.equals("高等数学"))
          require(x.color.equals(maths), "title colour is uniform after import");

      // A re-import must not repaint the timetable the user is looking at.
      require(db.importCourses(batch, term) == 0, "re-import is a no-op");
      require(db.colorFor(term, "高等数学").equals(maths), "re-import keeps the existing colour");

      // An explicit pick writes through to every arrangement of the title.
      require(db.recolorTitle(term, "高等数学", "#123456") == 3, "recolour writes the whole title");
      for (Course x : db.courses(term))
        if (x.title.equals("高等数学"))
          require("#123456".equals(x.color), "every arrangement repainted");
      require("#123456".equals(db.colorFor(term, "高等数学")), "colorFor reads the new colour");
      require(db.recolorTitle(term, "高等数学", "#123456") == 0, "recolour is idempotent");
      try {
        db.recolorTitle(term, "高等数学", "nope");
        throw new AssertionError("malformed colour accepted");
      } catch (IllegalArgumentException expected) {
        checks++;
      }
      require("#123456".equals(db.colorFor(term, "高等数学")), "a rejected colour changes nothing");

      // Disagreement is repaired by unify, which keeps the first arrangement's colour. The stray
      // has to be a later row: recolouring the canonical first one just moves the canonical colour.
      List<Course> listed = db.courses(term);
      Course stray = listed.get(listed.size() - 2); // the third 高等数学 row; 大学英语 sorts after it
      require(stray.title.equals("高等数学"), "fixture sorts by day then start");
      stray.color = "#ABCDEF";
      db.save(stray);
      require("#123456".equals(db.colorFor(term, "高等数学")), "colorFor still reads the first row");
      require(db.unifyColors(term) == 1, "unify rewrites the stray row");
      require("#123456".equals(db.colorFor(term, "高等数学")), "unify keeps the first colour");
      require(db.unifyColors(term) == 0, "unify is idempotent");

      // Deleting a term takes its children with it and leaves every other term alone.
      db.savePending(
          term, new JSONArray().put(new JSONObject().put("title", "待补充").put("notes", "")));
      long keep = db.saveTerm(new ScheduleDb.Term(0, "保留学期", "2026-02-02", 20));
      db.save(sample("线性代数", 4, keep, "1-3"));
      db.savePending(
          keep, new JSONArray().put(new JSONObject().put("title", "留下的").put("notes", "")));
      db.deleteTerm(term);
      require(db.terms().size() == 1, "term deleted");
      require(db.terms().get(0).id == keep, "the surviving term is the other one");
      require(db.courseCount(term) == 0 && db.courseCount(keep) == 1, "other term untouched");
      // The schema has no foreign key, so only a raw count proves the child rows went too.
      require(rows(db, "courses", term) == 0, "no orphan course rows");
      require(rows(db, "pending", term) == 0, "no orphan pending rows");
    } finally {
      db.close();
      c.deleteDatabase(name);
    }
  }

  private long rows(ScheduleDb db, String table, long term) {
    try (android.database.Cursor cur =
        db.getReadableDatabase()
            .rawQuery("SELECT COUNT(*) FROM " + table + " WHERE term_id=?", new String[] {"" + term})) {
      return cur.moveToFirst() ? cur.getLong(0) : -1;
    }
  }

  private Course sample(String title, int day, long term, String weeks) {
    Course c = new Course();
    c.title = title;
    c.day = day;
    c.start = 1;
    c.end = 2;
    c.weeks = ScheduleRules.parseWeeks(weeks, 20);
    c.semesterId = term;
    return c;
  }

  private void ui(Runnable r) {
    runOnMainSync(r);
    waitForIdleSync();
  }

  /**
   * Exercises the phone-side import path with no network involved: reading a worksheet, rendering
   * the prompt payload, validating a reply shaped like the model's, and storing the result. The
   * live AI call itself is deliberately never made from a test.
   */
  private void localImportChecks() throws Exception {
    byte[] file = ("节次,,星期一\n,一,测试课程/(1-1节)1-3周，5周/示例教室/测试教师\n"
        + ",二,测试课程/(2-2节)1-3周，5周/示例教室/测试教师").getBytes(java.nio.charset.StandardCharsets.UTF_8);
    List<Sheets.Sheet> sheets = Sheets.read("synthetic.csv", file);
    require(sheets.size() == 1, "synthetic CSV one sheet");
    String content = Sheets.toPromptJson(sheets);
    require(content.startsWith("{\"untrustedWorksheetData\":"), "prompt payload shape");
    require(content.contains("测试课程"), "prompt payload carries cell text");
    require(!content.contains("\"row\":0"), "prompt rows are 1-based");

    JSONObject reply =
        new JSONObject()
            .put(
                "courses",
                new JSONArray()
                    .put(
                        new JSONObject()
                            .put("title", "测试课程")
                            .put("teacher", "测试教师")
                            .put("room", "示例教室")
                            .put("color", "#DCD5FF")
                            .put("notes", "")
                            .put("day", 1)
                            .put("start", 1)
                            .put("end", 1)
                            .put("weeks", new JSONArray(new int[] {5, 1, 3, 1}))))
            .put("pending", new JSONArray())
            .put("warnings", new JSONArray())
            .put(
                "semester",
                new JSONObject().put("name", "").put("startDate", "").put("totalWeeks", 20));
    JSONObject parsed = ImportValidator.validate(reply);
    require(parsed.getString("mode").equals("ai"), "validated mode is ai");
    require(
        parsed.getJSONObject("semester").getString("startDate").isEmpty(),
        "unknown semester date not fabricated");
    JSONObject course = parsed.getJSONArray("courses").getJSONObject(0);
    require(course.getJSONArray("weeks").length() == 3, "duplicate weeks collapsed");
    require(course.getJSONArray("weeks").getInt(0) == 1, "weeks sorted ascending");

    JSONObject bad = new JSONObject(reply.toString());
    bad.getJSONArray("courses").getJSONObject(0).put("color", "red");
    try {
      ImportValidator.validate(bad);
      throw new AssertionError("invalid colour accepted");
    } catch (IllegalArgumentException expected) {
      checks++;
    }
    JSONObject badWeeks = new JSONObject(reply.toString());
    badWeeks.getJSONArray("courses").getJSONObject(0).put("weeks", new JSONArray().put("1"));
    try {
      ImportValidator.validate(badWeeks);
      throw new AssertionError("string week accepted");
    } catch (IllegalArgumentException expected) {
      checks++;
    }

    Context context = getTargetContext();
    String name = "kejian-network-test.db";
    context.deleteDatabase(name);
    ScheduleDb db = new ScheduleDb(context, name);
    try {
      ScheduleDb.Term term = db.terms().get(0);
      JSONObject meta = parsed.getJSONObject("semester");
      term.name = "虚构测试学期";
      term.weeks = meta.getInt("totalWeeks");
      db.saveTerm(term);
      List<Course> courses = new ArrayList<>();
      JSONArray cs = parsed.getJSONArray("courses");
      Set<String> names = new HashSet<>();
      for (int i = 0; i < cs.length(); i++) {
        Course c = Course.from(cs.getJSONObject(i));
        courses.add(c);
        names.add(c.title);
      }
      require(names.size() == 1, "synthetic sample one course title");
      require(
          db.importResult(courses, term.id, parsed.getJSONArray("pending")) == 1,
          "HTTP results saved to SQLite");
      require(
          db.importResult(courses, term.id, parsed.getJSONArray("pending")) == 0,
          "second HTTP import idempotent");
      require(db.pending(term.id).length() == 0, "pending idempotent");
    } finally {
      db.close();
      context.deleteDatabase(name);
    }
  }

  private void editorBoundsChecks() {
    int original = activity.periods();
    try {
      ui(
          () -> {
            activity.prefs.edit().putInt("periods", 12).commit();
            Course imported = new Course();
            imported.title = "晚间测试";
            imported.start = 13;
            imported.end = 14;
            imported.weeks = ScheduleRules.parseWeeks("1-3", 20);
            Dialog d = CourseEditor.open(activity, imported, (c, picked) -> {});
            ArrayList<Spinner> spinners = new ArrayList<>();
            collectSpinners(d.getWindow().getDecorView(), spinners);
            require(
                spinners.get(1).getSelectedItemPosition() == 12,
                "imported start13 preserved in editor");
            require(
                spinners.get(2).getSelectedItemPosition() == 13,
                "imported end14 preserved in editor");
            d.dismiss();
            activity.prefs.edit().putInt("periods", 1).commit();
            Dialog fresh = CourseEditor.open(activity, null, (c, picked) -> {});
            spinners.clear();
            collectSpinners(fresh.getWindow().getDecorView(), spinners);
            require(
                spinners.get(2).getSelectedItemPosition() == 0,
                "one-period new course default valid");
            View decor = fresh.getWindow().getDecorView();
            require(
                countDesc(decor, "选择课程颜色", "") == CourseColors.PALETTE.length,
                "editor offers every palette swatch");
            require(
                countDesc(decor, "第", "周") == activity.term().weeks,
                "week chips cover the whole term");
            fresh.dismiss();
            Course incomplete = new Course();
            incomplete.title = "";
            Dialog pending = CourseEditor.open(activity, incomplete, (c, picked) -> {});
            require(pending.isShowing(), "incomplete pending opens for repair");
            pending.dismiss();
          });
    } finally {
      ui(() -> activity.prefs.edit().putInt("periods", original).commit());
    }
  }

  private void collectSpinners(View root, ArrayList<Spinner> out) {
    if (root instanceof Spinner) out.add((Spinner) root);
    if (root instanceof ViewGroup) {
      ViewGroup g = (ViewGroup) root;
      for (int i = 0; i < g.getChildCount(); i++) collectSpinners(g.getChildAt(i), out);
    }
  }

  private boolean hasText(String value) {
    return findText(activity.getWindow().getDecorView(), value) != null;
  }

  private View findText(View root, String value) {
    if (root instanceof TextView && ((TextView) root).getText().toString().equals(value))
      return root;
    if (root instanceof ViewGroup) {
      ViewGroup g = (ViewGroup) root;
      for (int i = 0; i < g.getChildCount(); i++) {
        View v = findText(g.getChildAt(i), value);
        if (v != null) return v;
      }
    }
    return null;
  }

  private View findDesc(View root, String value) {
    if (value.contentEquals(
        root.getContentDescription() == null ? "" : root.getContentDescription())) return root;
    if (root instanceof ViewGroup) {
      ViewGroup g = (ViewGroup) root;
      for (int i = 0; i < g.getChildCount(); i++) {
        View v = findDesc(g.getChildAt(i), value);
        if (v != null) return v;
      }
    }
    return null;
  }

  /** Counts views whose content description starts with {@code prefix} and ends with {@code suffix}. */
  private int countDesc(View root, String prefix, String suffix) {
    int n = 0;
    CharSequence d = root.getContentDescription();
    if (d != null) {
      String s = d.toString();
      if (s.startsWith(prefix) && s.endsWith(suffix)) n++;
    }
    if (root instanceof ViewGroup) {
      ViewGroup g = (ViewGroup) root;
      for (int i = 0; i < g.getChildCount(); i++) n += countDesc(g.getChildAt(i), prefix, suffix);
    }
    return n;
  }

  private void clickText(String value) {
    View v = findText(activity.getWindow().getDecorView(), value);
    if (v == null) throw new AssertionError("missing " + value);
    while (!v.isClickable() && v.getParent() instanceof View) v = (View) v.getParent();
    v.performClick();
  }

  private void shot(String name) throws IOException {
    waitForIdleSync();
    SystemClock.sleep(400);
    Bitmap b = getUiAutomation().takeScreenshot();
    if (b == null) throw new IOException("No screenshot");
    try (FileOutputStream out = new FileOutputStream(new File(captures, name))) {
      b.compress(Bitmap.CompressFormat.PNG, 100, out);
    }
    b.recycle();
  }
}
