package com.kejian.app;

import android.app.*;
import android.content.*;
import android.database.Cursor;
import android.graphics.Color;
import android.net.Uri;
import android.provider.OpenableColumns;
import android.view.*;
import android.widget.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import org.json.*;

/** File picker, explicit mode selection, asynchronous transport and editable review. */
public class ImportController {
  public static final int PICK_FILE = 101;
  private final MainActivity a;
  private final ExecutorService executor = Executors.newSingleThreadExecutor();
  private Uri uri;
  private String filename = "";
  private boolean closed, busy;
  private int generation;
  private HttpURLConnection active;
  private final List<Course> parsed = new ArrayList<>();
  private final List<Boolean> selected = new ArrayList<>();
  private JSONArray pending = new JSONArray();
  private JSONObject result;
  private long targetTerm;
  private String mode = "rules";

  public ImportController(MainActivity a) {
    this.a = a;
    try {
      String raw = a.prefs.getString("importDraft", "");
      if (!raw.isEmpty()) {
        JSONObject draft = new JSONObject(raw);
        targetTerm = draft.getLong("targetTerm");
        result = draft.getJSONObject("result");
        filename = draft.optString("filename", "");
        pending = result.optJSONArray("pending");
        if (pending == null) pending = new JSONArray();
        JSONArray cs = result.getJSONArray("courses"), choices = draft.getJSONArray("selected");
        for (int i = 0; i < cs.length(); i++) {
          parsed.add(Course.from(cs.getJSONObject(i)));
          selected.add(choices.optBoolean(i, true));
        }
      }
    } catch (Exception e) {
      result = null;
      parsed.clear();
      selected.clear();
      a.prefs.edit().remove("importDraft").apply();
    }
  }

  private void saveDraft() {
    if (result == null) return;
    try {
      JSONArray cs = new JSONArray();
      for (Course c : parsed) cs.put(c.json());
      result.put("courses", cs).put("pending", pending);
      JSONObject draft =
          new JSONObject()
              .put("targetTerm", targetTerm)
              .put("filename", filename)
              .put("result", result)
              .put("selected", new JSONArray(selected));
      a.prefs.edit().putString("importDraft", draft.toString()).apply();
    } catch (JSONException e) {
      a.toast("识别草稿未保存，请保持页面打开");
    }
  }

  private void clearDraft() {
    result = null;
    a.prefs.edit().remove("importDraft").apply();
  }

  private LinearLayout body(LinearLayout root) {
    ScrollView s = new ScrollView(a);
    root.addView(s, new LinearLayout.LayoutParams(-1, 0, 1));
    LinearLayout b = Ui.col(a);
    Ui.pad(b, 22, 14);
    s.addView(b);
    return b;
  }

  public void open() {
    if (busy) {
      a.toast("正在解析，请稍候");
      return;
    }
    if (result != null) {
      review();
      return;
    }
    LinearLayout root = a.subScreen("智能导入", "上传课表，自动整理上课安排");
    LinearLayout b = body(root);
    LinearLayout hero = Ui.card(a);
    hero.setBackground(Ui.bg(Color.rgb(239, 237, 255), Ui.dp(a, 20)));
    View icon = Ui.icon(a, "spark", Ui.PRIMARY);
    hero.addView(icon, new LinearLayout.LayoutParams(Ui.dp(a, 34), Ui.dp(a, 34)));
    Ui.gap(hero, 18);
    hero.addView(Ui.text(a, "把课表，交给课间", 23, Ui.INK, true));
    Ui.gap(hero, 10);
    hero.addView(Ui.text(a, "Excel / CSV / 课表图片\n先识别，再检查，最后一键保存。", 14, Ui.MUTED, false));
    b.addView(hero);
    Ui.gap(b, 24);
    b.addView(Ui.button(a, uri == null ? "选择课表文件" : "重新选择文件", false, this::pick));
    Ui.gap(b, 12);
    TextView file =
        Ui.text(
            a,
            uri == null ? "支持 .xls .xlsx .csv .png .jpg .jpeg，最大8MB" : filename,
            13,
            Ui.MUTED,
            false);
    b.addView(file);
    Ui.gap(b, 24);
    b.addView(Ui.text(a, "识别方式", 15, Ui.INK, true));
    RadioGroup group = new RadioGroup(a);
    RadioButton rules = new RadioButton(a);
    rules.setId(View.generateViewId());
    rules.setText("本地表格解析 · 无需 AI 密钥");
    RadioButton ai = new RadioButton(a);
    ai.setId(View.generateViewId());
    ai.setText("AI 智能识别 · 需配置服务端 API");
    group.addView(rules);
    group.addView(ai);
    group.check(mode.equals("ai") ? ai.getId() : rules.getId());
    group.setOnCheckedChangeListener((g, id) -> mode = id == ai.getId() ? "ai" : "rules");
    b.addView(group);
    Ui.gap(b, 12);
    b.addView(
        Ui.text(
            a,
            "表格解析：文件发送到你配置的导入服务。\nAI 识别：文件内容还会发送给该服务配置的 AI 提供商。图片仅支持 AI 模式。",
            12,
            Ui.MUTED,
            false));
    Ui.gap(b, 20);
    b.addView(Ui.text(a, "当前导入学期：" + a.term().name, 13, Ui.INK, false));
    Ui.gap(b, 16);
    b.addView(
        Ui.button(
            a,
            "开始识别",
            true,
            () -> {
              if (uri == null) {
                a.toast("请先选择课表文件");
                return;
              }
              new AlertDialog.Builder(a)
                  .setTitle(mode.equals("ai") ? "发送给 AI 识别？" : "发送到导入服务解析？")
                  .setMessage(
                      filename
                          + "\n\n"
                          + (mode.equals("ai")
                              ? "课表内容将通过你的导入服务发送至所配置的 AI 服务商。"
                              : "文件将发送到你设置的导入服务，不调用外部 AI。")
                          + "\n识别后可检查和修改，不会直接保存。")
                  .setNegativeButton("取消", null)
                  .setPositiveButton("开始", (d, w) -> parse())
                  .show();
            }));
    Ui.gap(b, 12);
    b.addView(Ui.link(a, "配置服务地址 / 检查连接", this::settings));
  }

  private void pick() {
    Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
    i.addCategory(Intent.CATEGORY_OPENABLE);
    i.setType("*/*");
    a.startActivityForResult(i, PICK_FILE);
  }

  public void selected(Uri chosen) {
    uri = chosen;
    filename = "课表文件";
    try (Cursor c =
        a.getContentResolver()
            .query(uri, new String[] {OpenableColumns.DISPLAY_NAME}, null, null, null)) {
      if (c != null && c.moveToFirst()) filename = c.getString(0);
    } catch (Exception ignored) {
    }
    open();
  }

  public void settings() {
    LinearLayout root = a.subScreen("识别服务设置", "API 密钥只保存在后端，不写入安卓应用");
    LinearLayout b = body(root);
    EditText endpoint =
        Ui.input(a, "导入服务地址", a.prefs.getString("server", "http://10.0.2.2:8765"), b);
    endpoint.setInputType(
        android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_VARIATION_URI);
    TextView status = Ui.text(a, "尚未检查连接", 14, Ui.MUTED, false);
    b.addView(status);
    Ui.gap(b, 18);
    b.addView(
        Ui.button(
            a,
            "保存并检查连接",
            true,
            () -> {
              try {
                String url = validateUrl(endpoint.getText().toString());
                a.prefs.edit().putString("server", url).apply();
                status.setText("正在连接…");
                executor.execute(
                    () -> {
                      try {
                        JSONObject j = request(url + "/health", null);
                        boolean configured =
                            j.optBoolean("aiConfigured", j.optBoolean("configured", false));
                        deliver(
                            () ->
                                status.setText(
                                    "导入服务已连接\n"
                                        + (configured ? "AI 已配置，可尝试智能识别" : "AI 尚未配置，可先使用本地表格解析")));
                      } catch (Exception e) {
                        deliver(() -> status.setText("连接失败：" + e.getMessage() + "\n请先启动电脑上的导入服务。"));
                      }
                    });
              } catch (Exception e) {
                Ui.error(a, e);
              }
            }));
    Ui.gap(b, 22);
    b.addView(
        Ui.text(
            a,
            "安卓模拟器：使用 http://10.0.2.2:8765\n"
                + "USB 真机：电脑执行 adb reverse tcp:8765 tcp:8765 后，使用 http://127.0.0.1:8765\n"
                + "远程服务：请使用 HTTPS 地址。\n\n"
                + "AI_API_URL、AI_API_KEY、AI_MODEL 在电脑后端配置。API 地址需支持聊天补全接口；图片识别还需要模型支持视觉输入。",
            13,
            Ui.MUTED,
            false));
    Ui.gap(b, 16);
    b.addView(Ui.link(a, "返回导入", this::open));
  }

  private String validateUrl(String input) throws Exception {
    String s = input.trim().replaceAll("/+$", "");
    URI u = new URI(s);
    if (u.getHost() == null
        || u.getUserInfo() != null
        || u.getQuery() != null
        || u.getFragment() != null) throw new IllegalArgumentException("请输入有效的服务地址");
    boolean local = Arrays.asList("10.0.2.2", "127.0.0.1", "localhost").contains(u.getHost());
    if (!"https".equals(u.getScheme()) && !(local && "http".equals(u.getScheme())))
      throw new IllegalArgumentException("远程服务需使用 HTTPS；HTTP 仅限本机和模拟器地址");
    return s;
  }

  private void parse() {
    if (busy) return;
    busy = true;
    targetTerm = a.termId;
    final int token = ++generation;
    final String selectedMode = mode;
    final Uri selectedUri = uri;
    final String name = filename;
    LinearLayout root = a.subScreen("正在识别", "解析完成后，你可以检查每一项安排");
    LinearLayout b = body(root);
    Ui.gap(b, 60);
    ProgressBar progress = new ProgressBar(a);
    b.addView(progress, new LinearLayout.LayoutParams(-1, Ui.dp(a, 60)));
    Ui.gap(b, 24);
    TextView info = Ui.text(a, "正在读取并解析课表…\n" + name, 16, Ui.INK, true);
    info.setGravity(Gravity.CENTER);
    b.addView(info);
    Ui.gap(b, 20);
    b.addView(
        Ui.link(
            a,
            "取消识别",
            () -> {
              generation++;
              busy = false;
              HttpURLConnection conn = active;
              if (conn != null) conn.disconnect();
              open();
            }));
    executor.execute(
        () -> {
          try {
            byte[] bytes = a.readLimited(selectedUri, 8 * 1024 * 1024);
            JSONObject payload =
                new JSONObject()
                    .put("filename", name)
                    .put(
                        "contentBase64",
                        android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP))
                    .put("mode", selectedMode);
            String url = validateUrl(a.prefs.getString("server", "http://10.0.2.2:8765"));
            JSONObject response = request(url + "/parse", payload);
            JSONArray records = response.getJSONArray("courses");
            if (records.length() > 1000) throw new IOException("识别课程数量超过限制");
            List<Course> cs = new ArrayList<>();
            for (int i = 0; i < records.length(); i++)
              cs.add(Course.from(records.getJSONObject(i)));
            deliver(
                () -> {
                  if (token != generation) return;
                  busy = false;
                  result = response;
                  parsed.clear();
                  parsed.addAll(cs);
                  selected.clear();
                  for (Course c : cs) selected.add(true);
                  pending = response.optJSONArray("pending");
                  if (pending == null) pending = new JSONArray();
                  review();
                });
          } catch (Exception e) {
            deliver(
                () -> {
                  if (token != generation) return;
                  busy = false;
                  open();
                  Ui.error(a, e);
                });
          }
        });
  }

  private void review() {
    a.termId = targetTerm;
    saveDraft();
    LinearLayout root = a.subScreen("识别结果", "预览、修改，确认后才会保存");
    LinearLayout b = body(root);
    String actual = result.optString("mode", "unknown");
    LinearLayout summary = Ui.card(a);
    summary.setBackground(Ui.bg(Color.rgb(239, 237, 255), Ui.dp(a, 18)));
    summary.addView(
        Ui.text(a, parsed.size() + " 项安排 · " + pending.length() + " 项待补充", 20, Ui.PRIMARY, true));
    Ui.gap(summary, 8);
    summary.addView(
        Ui.text(
            a,
            "识别方式："
                + (actual.equals("ai")
                    ? "AI 智能识别"
                    : actual.equals("rules") ? "本地表格解析（未调用AI）" : actual)
                + "\n目标学期："
                + a.term().name,
            12,
            Ui.MUTED,
            false));
    b.addView(summary);
    Ui.gap(b, 12);
    JSONObject metadata = result.optJSONObject("semester");
    if (metadata != null && !metadata.optString("startDate").isEmpty())
      b.addView(Ui.link(a, "使用识别到的学期：" + metadata.optString("name"), () -> useTerm(metadata)));
    JSONArray warnings = result.optJSONArray("warnings");
    if (warnings != null)
      for (int i = 0; i < warnings.length(); i++) {
        b.addView(Ui.text(a, "提示：" + warnings.optString(i), 12, Ui.MUTED, false));
        Ui.gap(b, 6);
      }
    LinearLayout select = Ui.row(a);
    select.addView(
        Ui.link(
            a,
            "全选",
            () -> {
              Collections.fill(selected, true);
              review();
            }));
    select.addView(
        Ui.link(
            a,
            "全不选",
            () -> {
              Collections.fill(selected, false);
              review();
            }));
    b.addView(select);
    List<Course> existing = a.db.courses(targetTerm);
    for (int i = 0; i < parsed.size(); i++) {
      final int index = i;
      Course c = parsed.get(i);
      c.semesterId = targetTerm;
      LinearLayout card = Ui.card(a);
      CheckBox check = new CheckBox(a);
      check.setText(c.title);
      check.setTextSize(15);
      check.setTextColor(Ui.INK);
      check.setChecked(selected.get(i));
      check.setOnCheckedChangeListener(
          (v, on) -> {
            selected.set(index, on);
            saveDraft();
          });
      card.addView(check);
      Ui.gap(card, 7);
      card.addView(
          Ui.text(
              a,
              c.when()
                  + "\n"
                  + ScheduleRules.formatWeeks(c.weeks)
                  + "周\n"
                  + c.room
                  + "  ·  "
                  + c.teacher,
              13,
              Ui.MUTED,
              false));
      String alert = "";
      for (Course e : existing) {
        if (c.same(e)) {
          alert = "已存在：导入时自动跳过";
          break;
        }
        if (c.conflicts(e)) alert = "与已有课程时间冲突，请修改或取消勾选";
      }
      if (!alert.isEmpty()) {
        Ui.gap(card, 8);
        card.addView(Ui.text(a, alert, 12, Color.rgb(167, 95, 26), false));
      }
      card.addView(
          Ui.link(
              a,
              "修改这项安排",
              () ->
                  CourseEditor.open(
                      a,
                      c,
                      updated -> {
                        parsed.set(index, updated);
                        review();
                      })));
      b.addView(card);
      Ui.gap(b, 10);
    }
    if (pending.length() > 0) {
      Ui.gap(b, 14);
      b.addView(Ui.text(a, "待补充 · 不会自动排入课表", 16, Ui.INK, true));
      Ui.gap(b, 10);
      for (int i = 0; i < pending.length(); i++) {
        final int index = i;
        JSONObject p = pending.optJSONObject(i);
        if (p == null) continue;
        LinearLayout card = Ui.card(a);
        card.addView(Ui.text(a, p.optString("title", "未命名课程"), 15, Ui.INK, true));
        Ui.gap(card, 7);
        card.addView(Ui.text(a, p.optString("notes"), 12, Ui.MUTED, false));
        card.addView(
            Ui.link(
                a,
                "补充上课时间",
                () -> {
                  Course c = new Course();
                  c.title = p.optString("title");
                  c.notes = p.optString("notes");
                  c.weeks = ScheduleRules.parseWeeks("1-" + a.term().weeks, a.term().weeks);
                  CourseEditor.open(
                      a,
                      c,
                      v -> {
                        parsed.add(v);
                        selected.add(true);
                        pending.remove(index);
                        review();
                      });
                }));
        b.addView(card);
        Ui.gap(b, 10);
      }
    }
    Ui.gap(b, 18);
    b.addView(Ui.button(a, "确认导入所选课程", true, this::commit));
    Ui.gap(b, 16);
    b.addView(
        Ui.link(
            a,
            "放弃本次结果",
            () ->
                new AlertDialog.Builder(a)
                    .setTitle("放弃识别草稿？")
                    .setMessage("未导入的修改会被丢弃。")
                    .setNegativeButton("保留", null)
                    .setPositiveButton(
                        "放弃",
                        (d, w) -> {
                          clearDraft();
                          open();
                        })
                    .show()));
  }

  private void useTerm(JSONObject m) {
    try {
      String start = m.getString("startDate");
      java.time.LocalDate date = java.time.LocalDate.parse(start);
      if (date.getDayOfWeek() != java.time.DayOfWeek.MONDAY)
        throw new IllegalArgumentException("识别到的开学日不是周一，请先到学期管理中设置第一周周一");
      int weeks = m.getInt("totalWeeks");
      String name = m.getString("name");
      for (ScheduleDb.Term t : a.db.terms())
        if (t.name.equals(name) && t.start.equals(start) && t.weeks == weeks) {
          targetTerm = t.id;
          a.termId = t.id;
          review();
          return;
        }
      new AlertDialog.Builder(a)
          .setTitle("新建识别到的学期？")
          .setMessage(name + "\n" + start + " 开始，共" + weeks + "周")
          .setNegativeButton("取消", null)
          .setPositiveButton(
              "新建",
              (d, w) -> {
                try {
                  targetTerm = a.db.saveTerm(new ScheduleDb.Term(0, name, start, weeks));
                  a.termId = targetTerm;
                  review();
                } catch (Exception e) {
                  Ui.error(a, e);
                }
              })
          .show();
    } catch (Exception e) {
      Ui.error(a, e);
    }
  }

  private void commit() {
    try {
      List<Course> chosen = new ArrayList<>(), existing = a.db.courses(targetTerm);
      int duplicate = 0;
      for (int i = 0; i < parsed.size(); i++) {
        if (!selected.get(i)) continue;
        Course c = parsed.get(i).copy();
        c.semesterId = targetTerm;
        c.validate(a.term().weeks, 16);
        boolean repeated = false;
        for (Course other : existing) {
          if (c.same(other)) {
            repeated = true;
            break;
          }
          if (c.conflicts(other))
            throw new IllegalArgumentException(c.title + " 与已有课程“" + other.title + "”冲突，请修改或取消勾选");
        }
        for (Course other : chosen) {
          if (c.same(other)) {
            repeated = true;
            break;
          }
          if (c.conflicts(other))
            throw new IllegalArgumentException(
                "所选课程“" + c.title + "”与“" + other.title + "”时间重叠，请检查");
        }
        if (repeated) duplicate++;
        else chosen.add(c);
      }
      if (chosen.isEmpty() && pending.length() == 0) {
        a.toast(duplicate > 0 ? "所选安排已存在，无需重复导入" : "请至少勾选一项有效课程安排");
        return;
      }
      int skipped = duplicate;
      new AlertDialog.Builder(a)
          .setTitle("保存识别结果？")
          .setMessage(
              "学期："
                  + a.term().name
                  + "\n新增安排："
                  + chosen.size()
                  + "项\n自动跳过重复："
                  + skipped
                  + "项\n待补充："
                  + pending.length()
                  + "项（保存在设置→待补充事项）")
          .setNegativeButton("再检查一下", null)
          .setPositiveButton(
              "确认保存",
              (d, w) -> {
                try {
                  int count = a.db.importResult(chosen, targetTerm, pending);
                  a.ensureVisiblePeriods();
                  a.activateTerm(targetTerm);
                  clearDraft();
                  a.showTab(0);
                  a.toast("已导入 " + count + " 项课程安排，待补充事项也已保留");
                } catch (Exception e) {
                  Ui.error(a, e);
                }
              })
          .show();
    } catch (Exception e) {
      Ui.error(a, e);
    }
  }

  private JSONObject request(String url, JSONObject body) throws Exception {
    HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
    active = connection;
    connection.setConnectTimeout(10000);
    connection.setReadTimeout(body == null ? 10000 : 120000);
    connection.setInstanceFollowRedirects(false);
    try {
      if (body != null) {
        connection.setRequestMethod("POST");
        connection.setDoOutput(true);
        connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
        byte[] bytes = body.toString().getBytes(StandardCharsets.UTF_8);
        connection.setFixedLengthStreamingMode(bytes.length);
        try (OutputStream out = connection.getOutputStream()) {
          out.write(bytes);
        }
      }
      int status = connection.getResponseCode();
      InputStream raw =
          status >= 200 && status < 300 ? connection.getInputStream() : connection.getErrorStream();
      String text = "";
      if (raw != null)
        try (InputStream in = raw;
            ByteArrayOutputStream out = new ByteArrayOutputStream()) {
          byte[] block = new byte[8192];
          int n;
          while ((n = in.read(block)) != -1) {
            if (out.size() + n > 4 * 1024 * 1024) throw new IOException("服务响应过大");
            out.write(block, 0, n);
          }
          text = out.toString("UTF-8");
        }
      JSONObject j;
      try {
        j = new JSONObject(text);
      } catch (JSONException e) {
        throw new IOException("服务返回了非 JSON 内容（HTTP " + status + "）");
      }
      if (status < 200 || status >= 300)
        throw new IOException(j.optString("error", "服务请求失败：HTTP " + status));
      return j;
    } finally {
      connection.disconnect();
      if (active == connection) active = null;
    }
  }

  private void deliver(Runnable r) {
    a.runOnUiThread(
        () -> {
          if (!closed && !a.isFinishing() && !a.isDestroyed()) r.run();
        });
  }

  public void close() {
    closed = true;
    generation++;
    if (active != null) active.disconnect();
    executor.shutdownNow();
  }
}
