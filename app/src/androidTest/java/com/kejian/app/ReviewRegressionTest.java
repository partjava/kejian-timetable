package com.kejian.app;

import android.app.*;
import android.content.*;
import android.net.Uri;
import android.os.Bundle;
import android.view.*;
import android.widget.*;
import java.io.*;
import java.lang.reflect.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.json.*;

/** Behaviour regressions. Only runs in the isolated reviewfix app, never in the user's app. */
public class ReviewRegressionTest extends Instrumentation {
  private MainActivity a;
  private int passed, failed;
  private final StringBuilder report = new StringBuilder();
  interface Check { void run() throws Exception; }
  @Override public void onCreate(Bundle args) { super.onCreate(args); start(); }
  @Override public void onStart() {
    Bundle output = new Bundle();
    if (!getTargetContext().getPackageName().contains("reviewfix")) {
      output.putString("stream", "Refusing to touch a non-QA app\n");
      finish(Activity.RESULT_CANCELED, output); return;
    }
    try {
      Intent intent = new Intent(getTargetContext(), MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
      a = (MainActivity) startActivitySync(intent);
      runCase("deleted term clears live draft", this::deletedDraft);
      runCase("database refuses orphan course", this::orphanCourse);
      runCase("orphan pending and empty import rejected", this::orphanPending);
      runCase("unrelated deletion preserves draft", this::unrelatedDraft);
      runCase("orphan disk draft discarded", this::orphanDraft);
      runCase("cancelled token cannot attach", this::cancelledToken);
      runCase("queued cancellation sends no request", this::cancelQueued);
      runCase("editing during test ignores stale success", () -> configEdit(true));
      runCase("editing after success invalidates it", () -> configEdit(false));
      runCase("save distinguishes tested and untested", this::saveStatus);
    } catch (Exception e) { failed++; report.append("SETUP FAIL: ").append(e).append('\n'); }
    output.putString("stream", "\n" + report + "PASS=" + passed + " FAIL=" + failed + "\n");
    finish(failed == 0 ? Activity.RESULT_OK : Activity.RESULT_CANCELED, output);
  }
  private void runCase(String name, Check check) {
    try { check.run(); passed++; report.append("PASS: ").append(name).append('\n'); }
    catch (Throwable e) { failed++; report.append("FAIL: ").append(name).append(" — ").append(e).append('\n'); }
  }
  private static void require(boolean ok, String reason) { if (!ok) throw new AssertionError(reason); }
  private void ui(Check code) throws Exception {
    final Throwable[] error = {null};
    runOnMainSync(() -> { try { code.run(); } catch (Throwable e) { error[0] = e; } });
    waitForIdleSync();
    if (error[0] != null) throw new Exception(error[0]);
  }
  private static Object field(Object target, String name) throws Exception {
    Field f = target.getClass().getDeclaredField(name); f.setAccessible(true); return f.get(target);
  }
  private static void field(Object target, String name, Object value) throws Exception {
    Field f = target.getClass().getDeclaredField(name); f.setAccessible(true); f.set(target, value);
  }
  private static Object call(Object target, String name, Class<?>[] types, Object... values) throws Exception {
    Method m = target.getClass().getDeclaredMethod(name, types); m.setAccessible(true);
    return m.invoke(target, values);
  }
  private ImportController controller() throws Exception {
    ImportController old = (ImportController) field(a, "importer"); old.close();
    a.prefs.edit().remove("importDraft").commit();
    ImportController c = new ImportController(a); field(a, "importer", c); return c;
  }
  private Course course() {
    Course c = new Course(); c.title = "Regression course"; c.weeks = Arrays.asList(1, 3); return c;
  }
  private void deletedDraft() throws Exception {
    ImportController c = controller();
    long deleted = a.db.saveTerm(new ScheduleDb.Term(0, "Delete test", "2026-09-07", 20));
    Course row = course(); row.semesterId = deleted;
    JSONObject result = new JSONObject().put("courses", new JSONArray().put(row.json()))
        .put("pending", new JSONArray()).put("warnings", new JSONArray());
    field(c, "result", result); field(c, "targetTerm", deleted);
    ((List<Course>) field(c, "parsed")).add(row); ((List<Boolean>) field(c, "selected")).add(true);
    ui(() -> { a.activateTerm(deleted); c.open(); });
    ui(() -> call(a, "deleteTerm", new Class<?>[]{ScheduleDb.Term.class}, a.db.term(deleted)));
    require(field(c, "result") == null, "Memory result survived deleted term");
    require(!a.prefs.contains("importDraft"), "Disk draft survived deleted term");
    ui(c::open);
    require(field(c, "result") == null, "Opening import resurrected draft");
  }
  private void orphanCourse() throws Exception {
    Course c = course(); c.semesterId = Long.MAX_VALUE;
    try { a.db.save(c); throw new AssertionError("Orphan course accepted"); }
    catch (IllegalArgumentException expected) { }
  }
  private void orphanPending() throws Exception {
    try { a.db.savePending(Long.MAX_VALUE, new JSONArray()); throw new AssertionError("Orphan pending accepted"); }
    catch (IllegalArgumentException expected) { }
    try { a.db.importResult(new ArrayList<>(), Long.MAX_VALUE, new JSONArray()); throw new AssertionError("Orphan import accepted"); }
    catch (IllegalArgumentException expected) { }
  }
  private void unrelatedDraft() throws Exception {
    ImportController c = controller();
    long term = a.db.terms().get(0).id;
    JSONObject result = new JSONObject().put("courses", new JSONArray()).put("pending", new JSONArray());
    field(c, "targetTerm", term); field(c, "result", result);
    ui(c::open);
    ui(() -> c.discardTerm(Long.MAX_VALUE));
    require(field(c, "result") == result && a.prefs.contains("importDraft"), "Unrelated draft removed");
    c.close();
  }
  private void orphanDraft() throws Exception {
    ImportController c = controller(); c.close();
    a.prefs.edit().putString("importDraft", new JSONObject().put("targetTerm", Long.MAX_VALUE)
        .put("result", new JSONObject().put("courses", new JSONArray())).put("selected", new JSONArray()).toString()).commit();
    c = new ImportController(a); field(a, "importer", c);
    require(field(c, "result") == null && !a.prefs.contains("importDraft"), "Orphan disk draft loaded");
    c.close();
  }
  private void cancelledToken() throws Exception {
    RequestCancellation request = new RequestCancellation(); request.cancel();
    HttpURLConnection connection = (HttpURLConnection)new URL("http://127.0.0.1:1").openConnection();
    try { request.attach(connection); throw new AssertionError("Cancelled token attached"); }
    catch (InterruptedIOException expected) { }
    finally { connection.disconnect(); }
  }
  private void saveStatus() throws Exception {
    ImportController c = controller();
    try (LocalApi api = new LocalApi(false)) {
      AiConfig.save(a, api.url(), "test-model", "local-test");
      ui(c::settings);
      ui(() -> findText(a.getWindow().getDecorView(), "保存配置").performClick());
      require(hasText("尚未验证"), "Unverified save implied success");
      ui(() -> findExact(a.getWindow().getDecorView(), "测试连接").performClick());
      require(api.received.await(5, TimeUnit.SECONDS), "No request");
      ((ExecutorService)field(c, "executor")).submit(() -> {}).get(5, TimeUnit.SECONDS); waitForIdleSync();
      ui(() -> findExact(a.getWindow().getDecorView(), "保存配置").performClick());
      require(hasText("已通过测试"), "Tested save lost status");
    } finally { c.close(); }
  }
  private static TextView findExact(View v, String text) {
    if (v instanceof TextView && ((TextView)v).getText().toString().equals(text)) return (TextView)v;
    if (v instanceof ViewGroup) for (int i=0; i<((ViewGroup)v).getChildCount(); i++) {
      TextView found = findExact(((ViewGroup)v).getChildAt(i), text); if (found != null) return found;
    }
    return null;
  }
  private void cancelQueued() throws Exception {
    ImportController c = controller();
    CountDownLatch release = new CountDownLatch(1);
    try (LocalApi api = new LocalApi(false)) {
      AiConfig.save(a, api.url(), "test-model", "local-test");
      File csv = new File(a.getCacheDir(), "regression.csv");
      try (FileOutputStream out = new FileOutputStream(csv)) { out.write("course,day\ntest,Monday".getBytes(StandardCharsets.UTF_8)); }
      field(c, "uri", Uri.fromFile(csv)); field(c, "filename", "regression.csv");
      ExecutorService executor = (ExecutorService) field(c, "executor");
      executor.execute(() -> { try { release.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); } });
      ui(() -> call(c, "parse", new Class<?>[0]));
      ui(() -> findText(a.getWindow().getDecorView(), "取消识别").performClick());
      release.countDown();
      executor.submit(() -> {}).get(5, TimeUnit.SECONDS);
      require(api.calls.get() == 0, "Cancelled queued import still sent HTTP");
    } finally { release.countDown(); c.close(); }
  }
  private void configEdit(boolean during) throws Exception {
    ImportController c = controller();
    try (LocalApi api = new LocalApi(during)) {
      AiConfig.save(a, api.url(), "test-model", "local-test");
      ui(c::settings);
      List<EditText> inputs = new ArrayList<>(); collect(a.getWindow().getDecorView(), inputs);
      ui(() -> findText(a.getWindow().getDecorView(), "测试连接").performClick());
      require(api.received.await(5, TimeUnit.SECONDS), "No local test request");
      ExecutorService executor = (ExecutorService) field(c, "executor");
      if (!during) { executor.submit(() -> {}).get(5, TimeUnit.SECONDS); waitForIdleSync();
        require(hasText("连接成功"), "Valid local reply did not succeed"); }
      ui(() -> inputs.get(1).setText("changed-model"));
      api.release.countDown();
      executor.submit(() -> {}).get(5, TimeUnit.SECONDS); waitForIdleSync();
      require(!hasText("连接成功"), "Stale success still shown after configuration edit");
      require(hasText("重新测试"), "No retest instruction");
    } finally { c.close(); }
  }
  private boolean hasText(String s) { return findText(a.getWindow().getDecorView(), s) != null; }
  private static TextView findText(View v, String text) {
    if (v instanceof TextView && ((TextView)v).getText().toString().contains(text)) return (TextView)v;
    if (v instanceof ViewGroup) for (int i=0; i<((ViewGroup)v).getChildCount(); i++) {
      TextView found = findText(((ViewGroup)v).getChildAt(i), text); if (found != null) return found;
    }
    return null;
  }
  private static void collect(View v, List<EditText> out) {
    if (v instanceof EditText) out.add((EditText)v);
    if (v instanceof ViewGroup) for (int i=0; i<((ViewGroup)v).getChildCount(); i++) collect(((ViewGroup)v).getChildAt(i), out);
  }
  private static final class LocalApi implements AutoCloseable {
    final ServerSocket server = new ServerSocket(0, 4, InetAddress.getByName("127.0.0.1"));
    final AtomicInteger calls = new AtomicInteger();
    final CountDownLatch received = new CountDownLatch(1), release;
    final Thread thread;
    volatile Socket current;
    LocalApi(boolean delayed) throws IOException {
      release = new CountDownLatch(delayed ? 1 : 0);
      thread = new Thread(() -> {
        try (Socket socket = server.accept()) {
          current = socket; socket.setSoTimeout(5000);
          InputStream in = socket.getInputStream();
          ByteArrayOutputStream header = new ByteArrayOutputStream(); int b;
          while ((b=in.read()) != -1) { header.write(b); if(header.toString("UTF-8").endsWith("\r\n\r\n")) break; }
          int length = 0;
          for(String line : header.toString("UTF-8").split("\r\n"))
            if(line.toLowerCase(Locale.ROOT).startsWith("content-length:")) length=Integer.parseInt(line.substring(15).trim());
          for(int i=0; i<length; i++) if(in.read() < 0) break;
          calls.incrementAndGet(); received.countDown(); release.await(5, TimeUnit.SECONDS);
          byte[] response = "{\"choices\":[{\"message\":{\"content\":\"{\\\"ok\\\":true}\"},\"finish_reason\":\"stop\"}]}".getBytes(StandardCharsets.UTF_8);
          OutputStream out=socket.getOutputStream();
          out.write(("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: "+response.length+"\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.UTF_8));
          out.write(response); out.flush();
        } catch(Exception ignored) { }
      }); thread.setDaemon(true); thread.start();
    }
    String url() { return "http://127.0.0.1:"+server.getLocalPort()+"/chat/completions"; }
    public void close() throws Exception { release.countDown(); server.close(); if(current!=null) current.close(); thread.join(1000); }
  }
}
