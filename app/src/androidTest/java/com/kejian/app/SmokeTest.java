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
      networkChecks();
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
      ui(() -> new ImportController(activity).open());
      require(hasText("智能导入"), "import screen");
      shot("10-import.png");
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

  private void ui(Runnable r) {
    runOnMainSync(r);
    waitForIdleSync();
  }

  private void networkChecks() throws Exception {
    byte[] file;
    try (InputStream in = getContext().getAssets().open("sample.xls");
        ByteArrayOutputStream out = new ByteArrayOutputStream()) {
      byte[] buf = new byte[8192];
      int n;
      while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
      file = out.toByteArray();
    }
    JSONObject payload =
        new JSONObject()
            .put("filename", "sample.xls")
            .put(
                "contentBase64",
                android.util.Base64.encodeToString(file, android.util.Base64.NO_WRAP))
            .put("mode", "rules");
    JSONObject parsed = http("/parse", payload, 200);
    require(parsed.getString("mode").equals("rules"), "actual HTTP parser mode");
    require(parsed.getJSONArray("courses").length() == 16, "actual XLS HTTP 16 arrangements");
    require(parsed.getJSONArray("pending").length() == 2, "actual XLS HTTP 2 pending");
    require(
        parsed.getJSONObject("semester").getString("startDate").equals("2026-09-07"),
        "actual semester metadata");
    Context context = getTargetContext();
    String name = "kejian-network-test.db";
    context.deleteDatabase(name);
    ScheduleDb db = new ScheduleDb(context, name);
    try {
      ScheduleDb.Term term = db.terms().get(0);
      JSONObject meta = parsed.getJSONObject("semester");
      term.name = meta.getString("name");
      term.start = meta.getString("startDate");
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
      require(names.size() == 10, "actual sample 10 course titles");
      require(
          db.importResult(courses, term.id, parsed.getJSONArray("pending")) == 16,
          "HTTP results saved to SQLite");
      require(
          db.importResult(courses, term.id, parsed.getJSONArray("pending")) == 0,
          "second HTTP import idempotent");
      require(db.pending(term.id).length() == 2, "pending idempotent");
    } finally {
      db.close();
      context.deleteDatabase(name);
    }
    JSONObject health = http("/health", null, 200);
    if (!health.getBoolean("configured")) {
      payload.put("mode", "ai");
      JSONObject error = http("/parse", payload, 503);
      require(error.getString("error").contains("AI"), "unconfigured AI fails explicitly");
    }
  }

  private JSONObject http(String path, JSONObject payload, int expected) throws Exception {
    java.net.HttpURLConnection c =
        (java.net.HttpURLConnection)
            new java.net.URL("http://10.0.2.2:8765" + path).openConnection();
    c.setConnectTimeout(10000);
    c.setReadTimeout(15000);
    try {
      if (payload != null) {
        c.setRequestMethod("POST");
        c.setDoOutput(true);
        c.setRequestProperty("Content-Type", "application/json");
        try (OutputStream out = c.getOutputStream()) {
          out.write(payload.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }
      }
      int status = c.getResponseCode();
      if (status != expected) throw new AssertionError("HTTP " + status + " expected " + expected);
      try (InputStream in = status < 400 ? c.getInputStream() : c.getErrorStream();
          ByteArrayOutputStream out = new ByteArrayOutputStream()) {
        byte[] b = new byte[4096];
        int n;
        while ((n = in.read(b)) != -1) out.write(b, 0, n);
        return new JSONObject(out.toString("UTF-8"));
      }
    } finally {
      c.disconnect();
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
            Dialog d = CourseEditor.open(activity, imported, c -> {});
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
            Dialog fresh = CourseEditor.open(activity, null, c -> {});
            spinners.clear();
            collectSpinners(fresh.getWindow().getDecorView(), spinners);
            require(
                spinners.get(2).getSelectedItemPosition() == 0,
                "one-period new course default valid");
            fresh.dismiss();
            Course incomplete = new Course();
            incomplete.title = "";
            Dialog pending = CourseEditor.open(activity, incomplete, c -> {});
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
