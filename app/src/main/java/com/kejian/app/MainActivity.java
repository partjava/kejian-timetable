package com.kejian.app;

import android.app.*;
import android.content.*;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.view.*;
import android.widget.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import org.json.*;

/** App navigation and screen composition. Data and import logic live in separate classes. */
public class MainActivity extends Activity {
  public ScheduleDb db;
  public android.content.SharedPreferences prefs;
  public long termId;
  private FrameLayout page;
  private LinearLayout navigation;
  private int tab = 0, displayWeek = 1;
  private boolean subpage = false;
  private ImportController importer;
  private static final int BACKUP = 201, RESTORE = 202;

  @Override
  public void onCreate(Bundle state) {
    super.onCreate(state);
    getWindow()
        .getDecorView()
        .setSystemUiVisibility(
            View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
    setContentView(R.layout.activity_main);
    View rootView = findViewById(R.id.root);
    rootView.setOnApplyWindowInsetsListener(
        (v, insets) -> {
          v.setPadding(
              insets.getSystemWindowInsetLeft(),
              insets.getSystemWindowInsetTop(),
              insets.getSystemWindowInsetRight(),
              insets.getSystemWindowInsetBottom());
          return insets.consumeSystemWindowInsets();
        });
    rootView.requestApplyInsets();
    page = findViewById(R.id.page);
    navigation = findViewById(R.id.navigation);
    prefs = getSharedPreferences("preferences", MODE_PRIVATE);
    db = new ScheduleDb(this);
    termId = db.term(prefs.getLong("term", 0)).id;
    seedProvidedTimetable();
    migratePending();
    if (state != null) {
      tab = state.getInt("tab", 0);
      termId = db.term(state.getLong("term", termId)).id;
    }
    displayWeek = state == null ? currentWeek() : state.getInt("week", currentWeek());
    importer = new ImportController(this);
    showTab(tab);
  }

  @Override
  public void onSaveInstanceState(Bundle state) {
    state.putInt("tab", tab);
    state.putLong("term", termId);
    state.putInt("week", displayWeek);
    super.onSaveInstanceState(state);
  }

  public ScheduleDb.Term term() {
    return db.term(termId);
  }

  public int periods() {
    return prefs.getInt("periods", 12);
  }

  public void activateTerm(long id) {
    termId = db.term(id).id;
    prefs.edit().putLong("term", termId).apply();
    displayWeek = currentWeek();
  }

  public void ensureVisiblePeriods() {
    int max = periods();
    for (ScheduleDb.Term t : db.terms())
      for (Course c : db.courses(t.id)) max = Math.max(max, c.end);
    if (max > periods()) prefs.edit().putInt("periods", max).apply();
  }

  public int currentWeek() {
    long days = ChronoUnit.DAYS.between(LocalDate.parse(term().start), LocalDate.now());
    return Math.max(1, Math.min(term().weeks, (int) Math.floorDiv(days, 7) + 1));
  }

  private boolean inSemester() {
    long days = ChronoUnit.DAYS.between(LocalDate.parse(term().start), LocalDate.now());
    return days >= 0 && days < (long) term().weeks * 7;
  }

  public String[] startTimes() {
    return times(
        "starts",
        new String[] {
          "08:00", "08:55", "10:00", "10:55", "14:00", "14:55", "16:00", "16:55", "19:00", "19:55",
          "20:50", "21:45", "22:00", "22:30", "23:00", "23:30"
        });
  }

  public String[] endTimes() {
    return times(
        "ends",
        new String[] {
          "08:45", "09:40", "10:45", "11:40", "14:45", "15:40", "16:45", "17:40", "19:45", "20:40",
          "21:35", "22:00", "22:25", "22:55", "23:25", "23:55"
        });
  }

  private String[] times(String key, String[] fallback) {
    String raw = prefs.getString(key, "");
    String[] v = raw.split(",");
    return v.length == 16 ? v : fallback;
  }

  private void seedProvidedTimetable() {
    if (prefs.getBoolean("seeded", false)) return;
    try (InputStream in = getAssets().open("sample-result.json")) {
      ByteArrayOutputStream bytes = new ByteArrayOutputStream();
      byte[] block = new byte[8192];
      int n;
      while ((n = in.read(block)) != -1) bytes.write(block, 0, n);
      JSONObject j = new JSONObject(new String(bytes.toByteArray(), StandardCharsets.UTF_8));
      JSONObject t = j.getJSONObject("semester");
      ScheduleDb.Term target = term();
      target.name = t.getString("name");
      target.start = t.getString("startDate");
      target.weeks = t.getInt("totalWeeks");
      db.saveTerm(target);
      JSONArray a = j.getJSONArray("courses");
      List<Course> cs = new ArrayList<>();
      for (int i = 0; i < a.length(); i++) cs.add(Course.from(a.getJSONObject(i)));
      db.importCourses(cs, termId);
      prefs
          .edit()
          .putBoolean("seeded", true)
          .putLong("term", termId)
          .putString("samplePending", j.optJSONArray("pending").toString())
          .apply();
    } catch (Exception e) {
      prefs.edit().putBoolean("seeded", true).apply();
      toast("示例未载入，可从智能导入添加课表");
    }
  }

  public void toast(String s) {
    Toast.makeText(this, s, Toast.LENGTH_LONG).show();
  }

  private void migratePending() {
    if (!prefs.contains("samplePending")) return;
    try {
      db.savePending(termId, new JSONArray(prefs.getString("samplePending", "[]")));
      prefs.edit().remove("samplePending").apply();
    } catch (Exception e) {
      toast("待补充事项尚未迁移，请重启应用重试");
    }
  }

  public void showTab(int target) {
    displayWeek = Math.max(1, Math.min(term().weeks, displayWeek));
    tab = target;
    subpage = false;
    navigation.setVisibility(View.VISIBLE);
    page.removeAllViews();
    renderNav();
    switch (tab) {
      case 0:
        showTimetable();
        break;
      case 1:
        showToday();
        break;
      case 2:
        showCourses();
        break;
      default:
        showSettings();
    }
  }

  private void renderNav() {
    navigation.removeAllViews();
    String[] names = {"课表", "今日", "课程", "设置"}, icons = {"calendar", "today", "book", "settings"};
    for (int i = 0; i < 4; i++) {
      final int index = i;
      LinearLayout n = Ui.col(this);
      n.setGravity(Gravity.CENTER);
      View icon = Ui.icon(this, icons[i], i == tab ? Ui.PRIMARY : Ui.MUTED);
      n.addView(icon, new LinearLayout.LayoutParams(Ui.dp(this, 24), Ui.dp(this, 24)));
      Ui.gap(n, 5);
      TextView label = Ui.text(this, names[i], 11, i == tab ? Ui.PRIMARY : Ui.MUTED, i == tab);
      label.setGravity(Gravity.CENTER);
      n.addView(label);
      navigation.addView(n, new LinearLayout.LayoutParams(0, -1, 1));
      n.setContentDescription(names[i]);
      n.setOnClickListener(v -> showTab(index));
    }
  }

  public LinearLayout subScreen(String title, String subtitle) {
    subpage = true;
    navigation.setVisibility(View.GONE);
    page.removeAllViews();
    LinearLayout root = Ui.col(this);
    page.addView(root, new FrameLayout.LayoutParams(-1, -1));
    LinearLayout h = Ui.row(this);
    Ui.pad(h, 16, 12);
    TextView back = Ui.link(this, "‹", () -> showTab(tab));
    back.setTextSize(30);
    h.addView(back);
    LinearLayout label = Ui.col(this);
    label.addView(Ui.text(this, title, 21, Ui.INK, true));
    if (!subtitle.isEmpty()) {
      Ui.gap(label, 5);
      label.addView(Ui.text(this, subtitle, 12, Ui.MUTED, false));
    }
    h.addView(label, new LinearLayout.LayoutParams(0, -2, 1));
    root.addView(h);
    return root;
  }

  private LinearLayout content(LinearLayout root) {
    ScrollView scroll = new ScrollView(this);
    scroll.setFillViewport(true);
    root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
    LinearLayout body = Ui.col(this);
    Ui.pad(body, 20, 12);
    scroll.addView(body);
    return body;
  }

  private LinearLayout screen(String title, String subtitle) {
    LinearLayout root = Ui.col(this);
    page.addView(root, new FrameLayout.LayoutParams(-1, -1));
    LinearLayout h = Ui.row(this);
    Ui.pad(h, 22, 20);
    LinearLayout labels = Ui.col(this);
    labels.addView(Ui.text(this, title, 27, Ui.INK, true));
    Ui.gap(labels, 7);
    labels.addView(Ui.text(this, subtitle, 12, Ui.MUTED, false));
    h.addView(labels, new LinearLayout.LayoutParams(0, -2, 1));
    View star = Ui.icon(this, "spark", Ui.PRIMARY);
    h.addView(star, new LinearLayout.LayoutParams(Ui.dp(this, 27), Ui.dp(this, 27)));
    star.setContentDescription("智能导入");
    star.setOnClickListener(v -> importer.open());
    root.addView(h);
    return root;
  }

  private void showTimetable() {
    LinearLayout root = screen("课间", term().name);
    LinearLayout actions = Ui.row(this);
    Ui.pad(actions, 16, 0);
    actions.addView(
        Ui.link(
            this,
            "‹",
            () -> {
              displayWeek = Math.max(1, displayWeek - 1);
              showTab(0);
            }));
    TextView week = Ui.button(this, "第 " + displayWeek + " 周  ⌄", false, this::chooseWeek);
    actions.addView(week, new LinearLayout.LayoutParams(0, Ui.dp(this, 43), 1));
    actions.addView(
        Ui.link(
            this,
            "›",
            () -> {
              displayWeek = Math.min(term().weeks, displayWeek + 1);
              showTab(0);
            }));
    actions.addView(
        Ui.link(
            this,
            "本周",
            () -> {
              displayWeek = currentWeek();
              showTab(0);
            }));
    root.addView(actions);
    Ui.gap(root, 12);
    int days = prefs.getBoolean("weekends", true) ? 7 : 5;
    LinearLayout headers = Ui.row(this);
    headers.setBackgroundColor(Color.WHITE);
    View rail = new View(this);
    headers.addView(rail, new LinearLayout.LayoutParams(Ui.dp(this, 36), Ui.dp(this, 58)));
    LocalDate monday = LocalDate.parse(term().start).plusWeeks(displayWeek - 1);
    for (int i = 0; i < days; i++) {
      LocalDate date = monday.plusDays(i);
      boolean today = date.equals(LocalDate.now()) && prefs.getBoolean("highlight", true);
      LinearLayout col = Ui.col(this);
      col.setGravity(Gravity.CENTER);
      col.addView(Ui.text(this, Course.DAYS[i], 11, today ? Ui.PRIMARY : Ui.MUTED, today));
      Ui.gap(col, 5);
      TextView num =
          Ui.text(this, "" + date.getDayOfMonth(), 14, today ? Color.WHITE : Ui.INK, true);
      num.setGravity(Gravity.CENTER);
      if (today) num.setBackground(Ui.bg(Ui.PRIMARY, Ui.dp(this, 18)));
      col.addView(num, new LinearLayout.LayoutParams(Ui.dp(this, 28), Ui.dp(this, 28)));
      headers.addView(col, new LinearLayout.LayoutParams(0, Ui.dp(this, 62), 1));
    }
    root.addView(headers);
    FrameLayout board = new FrameLayout(this);
    root.addView(board, new LinearLayout.LayoutParams(-1, 0, 1));
    ScrollView scroll = new ScrollView(this);
    scroll.setFillViewport(false);
    List<Course> cs = db.courses(termId);
    TimetableView grid =
        new TimetableView(this, cs, days, periods(), displayWeek, startTimes(), this::showDetail);
    scroll.addView(grid, new ScrollView.LayoutParams(-1, -2));
    board.addView(scroll, new FrameLayout.LayoutParams(-1, -1));
    TextView add = Ui.button(this, "+", true, () -> editCourse(null));
    add.setTextSize(29);
    add.setElevation(Ui.dp(this, 5));
    add.setBackground(Ui.bg(Ui.PRIMARY, Ui.dp(this, 28)));
    FrameLayout.LayoutParams ap =
        new FrameLayout.LayoutParams(
            Ui.dp(this, 56), Ui.dp(this, 56), Gravity.BOTTOM | Gravity.END);
    ap.setMargins(0, 0, Ui.dp(this, 18), Ui.dp(this, 18));
    board.addView(add, ap);
    add.setContentDescription("添加课程");
    if (cs.isEmpty()) {
      TextView empty = Ui.empty(this, "还没有课程", "点击右下角添加，或点击右上角智能导入");
      FrameLayout.LayoutParams ep = new FrameLayout.LayoutParams(-1, -2, Gravity.CENTER);
      board.addView(empty, ep);
    }
  }

  private void chooseWeek() {
    String[] weeks = new String[term().weeks];
    for (int i = 0; i < weeks.length; i++) weeks[i] = "第 " + (i + 1) + " 周";
    new AlertDialog.Builder(this)
        .setTitle("切换教学周")
        .setSingleChoiceItems(
            weeks,
            displayWeek - 1,
            (d, n) -> {
              displayWeek = n + 1;
              d.dismiss();
              showTab(0);
            })
        .setNegativeButton("取消", null)
        .show();
  }

  private void showToday() {
    LocalDate today = LocalDate.now();
    LinearLayout root =
        screen(
            "今日",
            today.getMonthValue()
                + "月"
                + today.getDayOfMonth()
                + "日  "
                + Course.DAYS[today.getDayOfWeek().getValue() - 1]);
    LinearLayout body = content(root);
    List<Course> list = new ArrayList<>();
    for (Course c : db.courses(termId))
      if (inSemester()
          && c.day == today.getDayOfWeek().getValue()
          && c.weeks.contains(currentWeek())) list.add(c);
    LinearLayout summary = Ui.card(this);
    summary.setBackground(Ui.bg(Color.rgb(235, 239, 255), Ui.dp(this, 18)));
    summary.addView(
        Ui.text(
            this,
            list.isEmpty() ? "今天，留一点时间给自己" : "今天有 " + list.size() + " 项课程安排",
            20,
            Ui.PRIMARY,
            true));
    Ui.gap(summary, 8);
    summary.addView(
        Ui.text(
            this,
            inSemester() ? "第 " + currentWeek() + " 周 · 按上课时间排列" : "今天不在当前学期日期范围内",
            13,
            Ui.MUTED,
            false));
    body.addView(summary);
    Ui.gap(body, 22);
    if (list.isEmpty()) body.addView(Ui.empty(this, "今天没有课程", "去课表看看接下来一周的安排吧"));
    boolean next = false;
    for (Course c : list) {
      String status;
      LocalTime now = LocalTime.now(),
          s = LocalTime.parse(startTimes()[c.start - 1]),
          e = LocalTime.parse(endTimes()[c.end - 1]);
      if (now.isAfter(e)) status = "已结束";
      else if (!now.isBefore(s)) status = "进行中";
      else if (!next) {
        status = "下一节";
        next = true;
      } else status = "待上课";
      LinearLayout card =
          courseCard(c, status + "  ·  " + startTimes()[c.start - 1] + "–" + endTimes()[c.end - 1]);
      body.addView(card);
      Ui.gap(body, 12);
    }
  }

  private LinearLayout courseCard(Course c, String subtitle) {
    LinearLayout card = Ui.card(this);
    LinearLayout row = Ui.row(this);
    View bar = new View(this);
    bar.setBackground(Ui.bg(Color.parseColor(c.color), Ui.dp(this, 4)));
    LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(Ui.dp(this, 5), Ui.dp(this, 62));
    bp.rightMargin = Ui.dp(this, 14);
    row.addView(bar, bp);
    LinearLayout copy = Ui.col(this);
    TextView title = Ui.text(this, c.title, 16, Ui.INK, true);
    title.setMaxLines(2);
    copy.addView(title);
    Ui.gap(copy, 8);
    copy.addView(Ui.text(this, subtitle, 12, Ui.MUTED, false));
    Ui.gap(copy, 6);
    copy.addView(
        Ui.text(
            this,
            (c.room.isEmpty() ? "地点待填写" : c.room)
                + "  ·  "
                + (c.teacher.isEmpty() ? "教师待填写" : c.teacher),
            12,
            Ui.MUTED,
            false));
    row.addView(copy, new LinearLayout.LayoutParams(0, -2, 1));
    card.addView(row);
    card.setOnClickListener(v -> showDetail(c));
    return card;
  }

  private void showCourses() {
    List<Course> all = db.courses(termId);
    Set<String> names = new HashSet<>();
    for (Course c : all) names.add(c.title);
    LinearLayout root = screen("全部课程", names.size() + " 门课程 · " + all.size() + " 项上课安排");
    LinearLayout body = content(root);
    EditText search = Ui.input(this, "搜索课程、教师或教室", "", body);
    search.setHint("输入关键词查找");
    LinearLayout buttons = Ui.row(this);
    buttons.addView(
        Ui.button(this, "＋ 添加课程", false, () -> editCourse(null)),
        new LinearLayout.LayoutParams(0, -2, 1));
    View gap = new View(this);
    buttons.addView(gap, new LinearLayout.LayoutParams(Ui.dp(this, 10), 1));
    buttons.addView(
        Ui.button(this, "智能导入", true, () -> importer.open()),
        new LinearLayout.LayoutParams(0, -2, 1));
    body.addView(buttons);
    Ui.gap(body, 18);
    LinearLayout results = Ui.col(this);
    body.addView(results);
    Runnable filter =
        () -> {
          results.removeAllViews();
          String q = search.getText().toString().trim().toLowerCase(Locale.ROOT);
          for (Course c : all)
            if ((c.title + c.teacher + c.room).toLowerCase(Locale.ROOT).contains(q)) {
              results.addView(
                  courseCard(c, c.when() + "  ·  " + ScheduleRules.formatWeeks(c.weeks) + "周"));
              Ui.gap(results, 10);
            }
          if (results.getChildCount() == 0)
            results.addView(Ui.empty(this, "没有找到课程", "试试其他关键词，或添加一门新课程"));
        };
    search.addTextChangedListener(
        new android.text.TextWatcher() {
          public void beforeTextChanged(CharSequence s, int a, int c, int f) {}

          public void onTextChanged(CharSequence s, int a, int b, int c) {
            filter.run();
          }

          public void afterTextChanged(android.text.Editable e) {}
        });
    filter.run();
  }

  public void editCourse(Course c) {
    CourseEditor.open(
        this,
        c,
        value -> {
          for (Course other : db.courses(termId))
            if (value.id != other.id && value.conflicts(other))
              throw new IllegalArgumentException("与“" + other.title + "”的周次和节次重叠，请调整后保存");
          db.save(value);
          ensureVisiblePeriods();
          showTab(tab);
          toast("课程已保存");
        });
  }

  public void showDetail(Course c) {
    LinearLayout root = subScreen("课程详情", c.when());
    LinearLayout body = content(root);
    LinearLayout hero = Ui.card(this);
    hero.setBackground(Ui.bg(Color.parseColor(c.color), Ui.dp(this, 20)));
    View icon = Ui.icon(this, "book", Ui.INK);
    hero.addView(icon, new LinearLayout.LayoutParams(Ui.dp(this, 36), Ui.dp(this, 36)));
    Ui.gap(hero, 22);
    hero.addView(Ui.text(this, c.title, 25, Ui.INK, true));
    Ui.gap(hero, 10);
    hero.addView(Ui.text(this, "上课安排", 13, Ui.MUTED, false));
    body.addView(hero);
    Ui.gap(body, 24);
    detailRow(body, "任课教师", c.teacher.isEmpty() ? "未填写" : c.teacher);
    detailRow(body, "上课地点", c.room.isEmpty() ? "未填写" : c.room);
    detailRow(body, "星期 / 节次", c.when());
    detailRow(body, "上课时间", startTimes()[c.start - 1] + "–" + endTimes()[c.end - 1]);
    detailRow(body, "上课周次", ScheduleRules.formatWeeks(c.weeks) + "周");
    if (!c.notes.isEmpty()) detailRow(body, "备注", c.notes);
    Ui.gap(body, 28);
    body.addView(Ui.button(this, "编辑课程", true, () -> editCourse(c)));
    Ui.gap(body, 12);
    TextView delete =
        Ui.link(
            this,
            "删除这项安排",
            () ->
                new AlertDialog.Builder(this)
                    .setTitle("删除课程安排？")
                    .setMessage(c.title + "\n" + c.when() + "\n删除后无法撤销，其他安排不会受影响。")
                    .setNegativeButton("取消", null)
                    .setPositiveButton(
                        "删除",
                        (d, w) -> {
                          db.delete(c.id);
                          showTab(tab);
                          toast("已删除这项课程安排");
                        })
                    .show());
    delete.setTextColor(Color.rgb(211, 76, 87));
    body.addView(delete);
  }

  private void detailRow(LinearLayout body, String key, String value) {
    LinearLayout r = Ui.row(this);
    Ui.pad(r, 0, 17);
    TextView label = Ui.text(this, key, 13, Ui.MUTED, false);
    r.addView(label, new LinearLayout.LayoutParams(Ui.dp(this, 92), -2));
    TextView v = Ui.text(this, value, 14, Ui.INK, false);
    v.setLineSpacing(Ui.dp(this, 4), 1);
    r.addView(v, new LinearLayout.LayoutParams(0, -2, 1));
    body.addView(r);
    View line = new View(this);
    line.setBackgroundColor(Ui.LINE);
    body.addView(line, new LinearLayout.LayoutParams(-1, Ui.dp(this, 1)));
  }

  private void section(LinearLayout body, String title) {
    Ui.gap(body, 20);
    body.addView(Ui.text(this, title, 12, Ui.MUTED, true));
    Ui.gap(body, 10);
  }

  private void setting(LinearLayout body, String title, String detail, Runnable click) {
    LinearLayout row = Ui.card(this);
    LinearLayout h = Ui.row(this);
    LinearLayout labels = Ui.col(this);
    labels.addView(Ui.text(this, title, 15, Ui.INK, true));
    if (!detail.isEmpty()) {
      Ui.gap(labels, 6);
      labels.addView(Ui.text(this, detail, 12, Ui.MUTED, false));
    }
    h.addView(labels, new LinearLayout.LayoutParams(0, -2, 1));
    h.addView(Ui.text(this, "›", 22, Ui.MUTED, false));
    row.addView(h);
    row.setOnClickListener(v -> click.run());
    body.addView(row);
    Ui.gap(body, 8);
  }

  private void toggle(LinearLayout body, String title, String detail, String key, boolean initial) {
    LinearLayout box = Ui.card(this);
    LinearLayout row = Ui.row(this);
    LinearLayout label = Ui.col(this);
    label.addView(Ui.text(this, title, 15, Ui.INK, true));
    Ui.gap(label, 6);
    TextView d = Ui.text(this, detail, 12, Ui.MUTED, false);
    label.addView(d);
    row.addView(label, new LinearLayout.LayoutParams(0, -2, 1));
    Switch s = new Switch(this);
    s.setContentDescription(title);
    s.setChecked(prefs.getBoolean(key, initial));
    s.setOnCheckedChangeListener((b, v) -> prefs.edit().putBoolean(key, v).apply());
    row.addView(s);
    box.addView(row);
    body.addView(box);
    Ui.gap(body, 8);
  }

  private void showSettings() {
    LinearLayout root = screen("设置", "让课表，适合你的节奏");
    LinearLayout body = content(root);
    section(body, "学期与时间");
    setting(body, "学期管理", term().name, this::showTerms);
    setting(body, "作息时间", "每天 " + periods() + " 节 · 支持晚间课程", this::showTimes);
    section(body, "显示偏好");
    toggle(body, "显示周末", "关闭仅隐藏周六、周日，课程仍会保留", "weekends", true);
    toggle(body, "突出显示今天", "在课表日期栏标记今天", "highlight", true);
    section(body, "智能导入");
    setting(body, "导入课表文件", "Excel / CSV / 图片 · 识别后确认", () -> importer.open());
    setting(body, "识别服务设置", "连接地址与 AI 配置状态", () -> importer.settings());
    try {
      int count = db.pending(termId).length();
      setting(
          body,
          "待补充事项",
          count == 0 ? "当前没有待补充课程" : count + " 项课程需要确认上课时间",
          this::showSamplePending);
    } catch (JSONException e) {
      Ui.error(this, e);
    }
    section(body, "数据管理");
    setting(body, "备份课表", "导出所有学期为本地 JSON 文件", this::backup);
    setting(body, "恢复课表", "作为新学期恢复，保留已有数据", this::restore);
    Ui.gap(body, 20);
    TextView footer = Ui.text(this, "课间 1.0\nJava 原生安卓 · 课表随身记", 12, Ui.MUTED, false);
    footer.setGravity(Gravity.CENTER);
    footer.setLineSpacing(Ui.dp(this, 6), 1);
    body.addView(footer);
    Ui.gap(body, 20);
  }

  private void showSamplePending() {
    try {
      JSONArray arr = db.pending(termId);
      LinearLayout root = subScreen("待补充事项", "源文件没有完整上课时间，未自动排入课表");
      LinearLayout body = content(root);
      if (arr.length() == 0) body.addView(Ui.empty(this, "所有课程都已安排好", "识别时缺少信息的课程会保存在这里"));
      for (int i = 0; i < arr.length(); i++) {
        JSONObject j = arr.getJSONObject(i);
        String title = j.optString("title", "未命名课程"), notes = j.optString("notes");
        long id = j.getLong("id");
        setting(
            body,
            title,
            notes,
            () -> {
              Course c = new Course();
              c.title = title;
              c.notes = notes;
              c.weeks = ScheduleRules.parseWeeks("1-" + term().weeks, term().weeks);
              CourseEditor.open(
                  this,
                  c,
                  value -> {
                    for (Course other : db.courses(termId))
                      if (value.conflicts(other))
                        throw new IllegalArgumentException("与“" + other.title + "”时间冲突，请调整");
                    db.save(value);
                    ensureVisiblePeriods();
                    db.deletePending(id);
                    showSamplePending();
                    toast("已补充并保存课程");
                  });
            });
      }
    } catch (Exception e) {
      Ui.error(this, e);
    }
  }

  public void showTerms() {
    LinearLayout root = subScreen("学期管理", "切换学期，课程数据分别保存");
    LinearLayout body = content(root);
    for (ScheduleDb.Term t : db.terms()) {
      LinearLayout card = Ui.card(this);
      card.addView(Ui.text(this, t.name, 18, t.id == termId ? Ui.PRIMARY : Ui.INK, true));
      Ui.gap(card, 10);
      card.addView(
          Ui.text(
              this,
              (t.id == termId ? "当前学期  ·  " : "") + "共 " + t.weeks + " 周",
              13,
              Ui.MUTED,
              false));
      Ui.gap(card, 8);
      card.addView(Ui.text(this, "开学日期  " + t.start, 13, Ui.MUTED, false));
      LinearLayout actions = Ui.row(this);
      actions.addView(
          Ui.link(this, "编辑", () -> editTerm(t)), new LinearLayout.LayoutParams(0, -2, 1));
      actions.addView(
          Ui.link(
              this,
              t.id == termId ? "查看课表" : "切换到此学期",
              () -> {
                termId = t.id;
                prefs.edit().putLong("term", termId).apply();
                displayWeek = currentWeek();
                showTab(0);
              }),
          new LinearLayout.LayoutParams(0, -2, 1));
      card.addView(actions);
      body.addView(card);
      Ui.gap(body, 14);
    }
    body.addView(
        Ui.button(
            this,
            "＋ 新建学期",
            true,
            () ->
                editTerm(
                    new ScheduleDb.Term(
                        0,
                        "",
                        LocalDate.now()
                            .with(
                                java.time.temporal.TemporalAdjusters.previousOrSame(
                                    DayOfWeek.MONDAY))
                            .toString(),
                        20))));
  }

  private void editTerm(ScheduleDb.Term t) {
    LinearLayout form = Ui.col(this);
    Ui.pad(form, 22, 10);
    EditText name = Ui.input(this, "学期名称", t.name, form);
    EditText start = Ui.input(this, "第一周周一日期（YYYY-MM-DD）", t.start, form);
    start.setFocusable(false);
    start.setOnClickListener(
        v -> {
          LocalDate d = LocalDate.parse(start.getText());
          new DatePickerDialog(
                  this,
                  (view, y, m, day) -> start.setText(LocalDate.of(y, m + 1, day).toString()),
                  d.getYear(),
                  d.getMonthValue() - 1,
                  d.getDayOfMonth())
              .show();
        });
    EditText weeks = Ui.input(this, "总周数（1–40）", "" + t.weeks, form);
    weeks.setInputType(2);
    AlertDialog dialog =
        new AlertDialog.Builder(this)
            .setTitle(t.id == 0 ? "新建学期" : "编辑学期")
            .setView(form)
            .setNegativeButton("取消", null)
            .setPositiveButton("保存", null)
            .create();
    dialog.setOnShowListener(
        x ->
            dialog
                .getButton(-1)
                .setOnClickListener(
                    v -> {
                      try {
                        int w = Integer.parseInt(weeks.getText().toString());
                        LocalDate day = LocalDate.parse(start.getText());
                        if (day.getDayOfWeek() != DayOfWeek.MONDAY)
                          throw new IllegalArgumentException("请选择第一教学周的周一，课表列按周一开始");
                        for (Course c : db.courses(t.id))
                          for (int cw : c.weeks)
                            if (cw > w)
                              throw new IllegalArgumentException("已有课程安排在第" + cw + "周，不能缩短学期到此周之前");
                        ScheduleDb.Term value =
                            new ScheduleDb.Term(t.id, name.getText().toString(), day.toString(), w);
                        long id = db.saveTerm(value);
                        if (t.id == 0) {
                          termId = id;
                          prefs.edit().putLong("term", id).apply();
                        }
                        displayWeek = Math.min(displayWeek, w);
                        dialog.dismiss();
                        showTerms();
                      } catch (Exception e) {
                        Ui.error(this, e);
                      }
                    }));
    dialog.show();
  }

  private void showTimes() {
    LinearLayout root = subScreen("作息时间", "设置每天节数和每节课的起止时间");
    LinearLayout body = content(root);
    String[] options = new String[16];
    for (int i = 0; i < 16; i++) options[i] = (i + 1) + " 节";
    Spinner count = Ui.select(this, "每天节数", options, periods() - 1, body);
    String[] starts = startTimes(), ends = endTimes();
    LinearLayout rows = Ui.col(this);
    body.addView(rows);
    Runnable render =
        () -> {
          rows.removeAllViews();
          for (int i = 0; i < count.getSelectedItemPosition() + 1; i++) {
            final int k = i;
            LinearLayout r = Ui.row(this);
            Ui.pad(r, 0, 6);
            r.addView(
                Ui.text(this, "第 " + (i + 1) + " 节", 14, Ui.INK, true),
                new LinearLayout.LayoutParams(Ui.dp(this, 70), -2));
            TextView s = Ui.button(this, starts[i], false, () -> {}),
                e = Ui.button(this, ends[i], false, () -> {});
            s.setOnClickListener(v -> pickTime(starts, k, s));
            e.setOnClickListener(v -> pickTime(ends, k, e));
            r.addView(s, new LinearLayout.LayoutParams(0, Ui.dp(this, 45), 1));
            TextView dash = Ui.text(this, " — ", 14, Ui.MUTED, false);
            r.addView(dash);
            r.addView(e, new LinearLayout.LayoutParams(0, Ui.dp(this, 45), 1));
            rows.addView(r);
          }
        };
    count.setOnItemSelectedListener(
        new android.widget.AdapterView.OnItemSelectedListener() {
          public void onItemSelected(AdapterView<?> p, View v, int n, long id) {
            render.run();
          }

          public void onNothingSelected(AdapterView<?> p) {}
        });
    render.run();
    Ui.gap(body, 24);
    body.addView(
        Ui.button(
            this,
            "保存作息",
            true,
            () -> {
              try {
                int n = count.getSelectedItemPosition() + 1;
                for (ScheduleDb.Term t : db.terms())
                  for (Course c : db.courses(t.id))
                    if (c.end > n)
                      throw new IllegalArgumentException("有课程使用第" + c.end + "节，不能隐藏这些节次");
                for (int i = 0; i < n; i++) {
                  if (!LocalTime.parse(starts[i]).isBefore(LocalTime.parse(ends[i])))
                    throw new IllegalArgumentException("第" + (i + 1) + "节结束时间必须晚于开始时间");
                  if (i > 0 && LocalTime.parse(starts[i]).isBefore(LocalTime.parse(ends[i - 1])))
                    throw new IllegalArgumentException("第" + (i + 1) + "节与上一节时间重叠");
                }
                prefs
                    .edit()
                    .putInt("periods", n)
                    .putString("starts", String.join(",", starts))
                    .putString("ends", String.join(",", ends))
                    .apply();
                toast("作息已保存");
                showTab(3);
              } catch (Exception e) {
                Ui.error(this, e);
              }
            }));
  }

  private void pickTime(String[] values, int index, TextView target) {
    LocalTime t = LocalTime.parse(values[index]);
    new TimePickerDialog(
            this,
            (v, h, m) -> {
              values[index] = String.format(Locale.ROOT, "%02d:%02d", h, m);
              target.setText(values[index]);
            },
            t.getHour(),
            t.getMinute(),
            true)
        .show();
  }

  private void backup() {
    Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT);
    i.setType("application/json");
    i.addCategory(Intent.CATEGORY_OPENABLE);
    i.putExtra(Intent.EXTRA_TITLE, "课间备份-" + LocalDate.now() + ".json");
    startActivityForResult(i, BACKUP);
  }

  private void restore() {
    Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
    i.setType("*/*");
    i.addCategory(Intent.CATEGORY_OPENABLE);
    startActivityForResult(i, RESTORE);
  }

  @Override
  protected void onActivityResult(int req, int result, Intent data) {
    super.onActivityResult(req, result, data);
    if (result != RESULT_OK || data == null || data.getData() == null) return;
    if (req == ImportController.PICK_FILE) {
      importer.selected(data.getData());
      return;
    }
    try {
      if (req == BACKUP) {
        JSONObject j = db.backup();
        try (OutputStream out = getContentResolver().openOutputStream(data.getData())) {
          if (out == null) throw new IOException("无法写入所选位置");
          out.write(j.toString(2).getBytes(StandardCharsets.UTF_8));
        }
        toast("课表备份已导出");
      } else if (req == RESTORE) {
        byte[] bytes = readLimited(data.getData(), 8 * 1024 * 1024);
        JSONObject backup = new JSONObject(new String(bytes, StandardCharsets.UTF_8));
        if (!"kejian-backup".equals(backup.optString("format")))
          throw new IllegalArgumentException("请选择课间导出的 JSON 备份");
        new AlertDialog.Builder(this)
            .setTitle("恢复备份？")
            .setMessage("将作为新学期导入，保留所有已有课程。显示偏好和服务地址不变。")
            .setNegativeButton("取消", null)
            .setPositiveButton(
                "恢复",
                (d, w) -> {
                  try {
                    int n = db.restore(backup);
                    ensureVisiblePeriods();
                    toast("已恢复 " + n + " 项安排，可在学期管理中切换");
                    showTerms();
                  } catch (Exception e) {
                    Ui.error(this, e);
                  }
                })
            .show();
      }
    } catch (Exception e) {
      Ui.error(this, e);
    }
  }

  public byte[] readLimited(Uri uri, int limit) throws IOException {
    try (InputStream in = getContentResolver().openInputStream(uri);
        ByteArrayOutputStream out = new ByteArrayOutputStream()) {
      if (in == null) throw new IOException("无法读取文件");
      byte[] buf = new byte[8192];
      int count;
      while ((count = in.read(buf)) != -1) {
        if (out.size() + count > limit) throw new IOException("文件不能超过8MB");
        out.write(buf, 0, count);
      }
      return out.toByteArray();
    }
  }

  @Override
  public void onBackPressed() {
    if (subpage) showTab(tab);
    else if (tab != 0) showTab(0);
    else super.onBackPressed();
  }

  @Override
  protected void onDestroy() {
    if (importer != null) importer.close();
    db.close();
    super.onDestroy();
  }
}
