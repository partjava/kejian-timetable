package com.kejian.app;

import android.app.*;
import android.content.*;
import android.database.Cursor;
import android.graphics.Color;
import android.net.Uri;
import android.provider.OpenableColumns;
import android.text.InputType;
import android.view.*;
import android.widget.*;
import java.io.*;
import java.net.HttpURLConnection;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import org.json.*;

/**
 * File picker, a direct call to the configured AI provider, and an editable review before saving.
 *
 * Reading the worksheet and calling the model both happen here on the phone; there is no desktop
 * service in the path any more.
 */
public class ImportController {
  public static final int PICK_FILE = 101;
  private static final int MAX_FILE = 8 * 1024 * 1024;

  private final MainActivity a;
  private final ExecutorService executor = Executors.newSingleThreadExecutor();
  private Uri uri;
  private String filename = "";
  private boolean closed, busy;
  private int generation;
  private final AtomicReference<HttpURLConnection> active = new AtomicReference<>();
  private final List<Course> parsed = new ArrayList<>();
  private final List<Boolean> selected = new ArrayList<>();
  private JSONArray pending = new JSONArray();
  private JSONObject result;
  private long targetTerm;

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
      a.toast("正在识别，请稍候");
      return;
    }
    if (result != null) {
      review();
      return;
    }
    LinearLayout root = a.subScreen("智能导入", "选择课表文件，由你配置的 AI 识别");
    LinearLayout b = body(root);
    LinearLayout hero = Ui.card(a);
    hero.setBackground(Ui.bg(Color.rgb(239, 237, 255), Ui.dp(a, 20)));
    View icon = Ui.icon(a, "spark", Ui.PRIMARY);
    hero.addView(icon, new LinearLayout.LayoutParams(Ui.dp(a, 34), Ui.dp(a, 34)));
    Ui.gap(hero, 18);
    hero.addView(Ui.text(a, "轻松导入个人课表", 23, Ui.INK, true));
    Ui.gap(hero, 10);
    hero.addView(Ui.text(a, "Excel / CSV 课表\n先识别，再检查，最后一键保存。", 14, Ui.MUTED, false));
    b.addView(hero);
    Ui.gap(b, 24);
    b.addView(Ui.button(a, uri == null ? "选择课表文件" : "重新选择文件", false, this::pick));
    Ui.gap(b, 12);
    b.addView(
        Ui.text(
            a,
            uri == null ? "支持 .xls .csv，最大8MB" : filename,
            13,
            Ui.MUTED,
            false));
    Ui.gap(b, 20);
    b.addView(Ui.text(a, "当前导入学期：" + a.term().name, 13, Ui.INK, false));
    Ui.gap(b, 12);
    boolean ready = AiConfig.hasKey(a);
    b.addView(
        Ui.text(
            a,
            ready
                ? "表格文字会通过 "
                    + AiConfig.normalize(AiConfig.url(a))
                    + " 发送给你配置的 AI 服务商，识别结果先给你确认，不会直接保存。"
                : "尚未配置 AI。请先到「AI 配置」填写接口地址、模型和密钥。",
            12,
            ready ? Ui.MUTED : Color.rgb(167, 95, 26),
            false));
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
              if (!AiConfig.hasKey(a)) {
                a.toast("尚未配置 API 密钥，请先到 AI 配置填写");
                settings();
                return;
              }
              new AlertDialog.Builder(a)
                  .setTitle("发送给 AI 识别？")
                  .setMessage(
                      filename
                          + "\n\n课表文字将发送至 "
                          + AiConfig.normalize(AiConfig.url(a))
                          + "。\n识别后可检查和修改，不会直接保存。")
                  .setNegativeButton("取消", null)
                  .setPositiveButton("开始", (d, w) -> parse())
                  .show();
            }));
    Ui.gap(b, 12);
    b.addView(Ui.link(a, "AI 配置 / 测试连接", this::settings));
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
    LinearLayout root = a.subScreen("AI 配置", "密钥经系统加密保存在本机，不写入安装包与课表备份");
    LinearLayout b = body(root);
    EditText endpoint = Ui.input(a, "API 地址", AiConfig.url(a), b);
    endpoint.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
    EditText model = Ui.input(a, "模型名称", AiConfig.model(a), b);
    EditText key = Ui.input(a, "API 密钥", "", b);
    key.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
    key.setHint(AiConfig.hasKey(a) ? "已保存，留空则不修改" : "粘贴你的 API 密钥");
    TextView status = Ui.text(a, "不确定模型名称时，可先点「获取模型列表」", 14, Ui.MUTED, false);
    b.addView(status);
    Ui.gap(b, 18);
    b.addView(Ui.button(a, "获取模型列表", false, () -> listModels(endpoint, key, model, status)));
    Ui.gap(b, 12);
    b.addView(Ui.button(a, "测试连接", false, () -> test(endpoint, model, key, status)));
    Ui.gap(b, 12);
    b.addView(Ui.button(a, "保存配置", true, () -> save(endpoint, model, key, status)));
    Ui.gap(b, 22);
    b.addView(
        Ui.text(
            a,
            "地址填聊天补全接口的完整地址，例如：\n"
                + "DeepSeek：https://api.deepseek.com/chat/completions\n"
                + "OpenAI：https://api.openai.com/v1/chat/completions\n"
                + "Azure OpenAI 可带 ?api-version= 查询参数。\n\n"
                + "模型名例如 deepseek-chat、gpt-4o-mini；填不准就点「获取模型列表」让它列出来。\n"
                + "密钥只存在本机，经系统 Keystore 加密，不会进入安装包，也不会随课表备份迁走。\n"
                + "换机或改锁屏密码后需要重新填写。",
            13,
            Ui.MUTED,
            false));
    Ui.gap(b, 16);
    b.addView(Ui.link(a, "返回导入", this::open));
  }

  private void test(EditText endpoint, EditText model, EditText key, TextView status) {
    String url = AiConfig.normalize(endpoint.getText().toString());
    List<String> problems = AiConfig.validate(url);
    if (!problems.isEmpty()) {
      status.setText("请先修正：" + problems.get(0));
      return;
    }
    String modelName = model.getText().toString().trim();
    if (modelName.isEmpty()) {
      status.setText("请填写模型名称");
      return;
    }
    String entered = key.getText().toString().trim();
    String secret = entered.isEmpty() ? AiConfig.key(a) : entered;
    if (secret == null || secret.isEmpty()) {
      status.setText("请填写 API 密钥");
      return;
    }
    status.setText("正在测试连接…");
    executor.execute(
        () -> {
          try {
            AiClient.test(url, modelName, secret);
            deliver(() -> status.setText("连接成功：地址、密钥和模型都可以使用"));
          } catch (Exception e) {
            deliver(() -> status.setText("连接失败：" + e.getMessage()));
          }
        });
  }

  /**
   * Asks the provider which models the key may use and offers them as a list. Providers without a
   * /models route keep working: the field stays editable and the reason lands in the status line.
   */
  private void listModels(EditText endpoint, EditText key, EditText model, TextView status) {
    String url = AiConfig.normalize(endpoint.getText().toString());
    List<String> problems = AiConfig.validate(url);
    if (!problems.isEmpty()) {
      status.setText("请先修正：" + problems.get(0));
      return;
    }
    String entered = key.getText().toString().trim();
    String secret = entered.isEmpty() ? AiConfig.key(a) : entered;
    if (secret == null || secret.isEmpty()) {
      status.setText("请先填写 API 密钥");
      return;
    }
    status.setText("正在获取模型列表…");
    executor.execute(
        () -> {
          try {
            List<String> ids = AiClient.models(url, secret);
            deliver(() -> chooseModel(ids, model, status));
          } catch (Exception e) {
            deliver(() -> status.setText("获取失败：" + e.getMessage()));
          }
        });
  }

  private void chooseModel(List<String> ids, EditText model, TextView status) {
    String current = model.getText().toString().trim();
    String[] labels = new String[ids.size()];
    for (int i = 0; i < ids.size(); i++)
      labels[i] = ids.get(i).equals(current) ? ids.get(i) + "　（当前使用）" : ids.get(i);
    status.setText("共 " + ids.size() + " 个模型，点击即可选用");
    new AlertDialog.Builder(a)
        .setTitle("选择模型")
        .setItems(
            labels,
            (d, which) -> {
              model.setText(ids.get(which));
              status.setText("已选择 " + ids.get(which) + "，请先测试连接，再保存");
            })
        .setNegativeButton("取消", null)
        .show();
  }

  private void save(EditText endpoint, EditText model, EditText key, TextView status) {
    try {
      String url = AiConfig.normalize(endpoint.getText().toString());
      List<String> problems = AiConfig.validate(url);
      if (!problems.isEmpty()) throw new IllegalArgumentException(problems.get(0));
      String modelName = model.getText().toString().trim();
      if (modelName.isEmpty()) throw new IllegalArgumentException("请填写模型名称");
      AiConfig.save(a, url, modelName, key.getText().toString());
      key.setText("");
      key.setHint(AiConfig.hasKey(a) ? "已保存，留空则不修改" : "粘贴你的 API 密钥");
      status.setText("已保存");
      a.toast("AI 配置已保存");
    } catch (Exception e) {
      Ui.error(a, e);
    }
  }

  private void parse() {
    if (busy) return;
    busy = true;
    targetTerm = a.termId;
    final int token = ++generation;
    final Uri selectedUri = uri;
    final String name = filename;
    LinearLayout root = a.subScreen("正在识别", "识别完成后，你可以检查每一项安排");
    LinearLayout b = body(root);
    Ui.gap(b, 60);
    ProgressBar progress = new ProgressBar(a);
    b.addView(progress, new LinearLayout.LayoutParams(-1, Ui.dp(a, 60)));
    Ui.gap(b, 24);
    TextView info = Ui.text(a, "正在读取课表并请求 AI 识别…\n" + name, 16, Ui.INK, true);
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
              HttpURLConnection conn = active.getAndSet(null);
              if (conn != null) conn.disconnect();
              open();
            }));
    executor.execute(
        () -> {
          try {
            String secret = AiConfig.key(a);
            if (secret == null)
              throw new IllegalStateException("尚未配置 API 密钥，请先到「AI 配置」填写");
            byte[] bytes = a.readLimited(selectedUri, MAX_FILE);
            String content = Sheets.toPromptJson(Sheets.read(name, bytes));
            JSONObject raw =
                AiClient.parse(
                    AiConfig.normalize(AiConfig.url(a)),
                    AiConfig.model(a),
                    secret,
                    content,
                    active);
            JSONObject response = ImportValidator.validate(raw);
            JSONArray records = response.getJSONArray("courses");
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
    LinearLayout summary = Ui.card(a);
    summary.setBackground(Ui.bg(Color.rgb(239, 237, 255), Ui.dp(a, 18)));
    summary.addView(
        Ui.text(a, parsed.size() + " 项安排 · " + pending.length() + " 项待补充", 20, Ui.PRIMARY, true));
    Ui.gap(summary, 8);
    summary.addView(
        Ui.text(
            a,
            "识别方式：AI 智能识别（"
                + AiConfig.model(a)
                + "）\n目标学期："
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
                      (updated, colorPicked) -> {
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
                  // Shown seeded, so the preview matches what commit() will store. importCourses
                  // recomputes colours from the title at commit time, so a colour chosen here is a
                  // preview rather than a promise — the timetable's colours stay deterministic.
                  c.color = CourseColors.seed(c.title);
                  CourseEditor.open(
                      a,
                      c,
                      (v, colorPicked) -> {
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

  private void deliver(Runnable r) {
    a.runOnUiThread(
        () -> {
          if (!closed && !a.isFinishing() && !a.isDestroyed()) r.run();
        });
  }

  public void close() {
    closed = true;
    generation++;
    HttpURLConnection conn = active.getAndSet(null);
    if (conn != null) conn.disconnect();
    executor.shutdownNow();
  }
}
