package com.kejian.app;

import android.app.*;
import android.content.*;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.view.*;
import android.widget.*;
import java.io.*;
import java.lang.reflect.Field;
import java.util.*;

/** Runs only in the isolated QA app. Screenshots contain fabricated data only. */
public class XmlLayoutRegressionTest extends Instrumentation {
  private MainActivity a;
  private String phase;
  private int passed, failed;
  private final StringBuilder report = new StringBuilder();
  interface Check { void run() throws Exception; }
  @Override public void onCreate(Bundle args) {
    super.onCreate(args); phase = args == null ? "after" : args.getString("phase", "after"); start();
  }
  @Override public void onStart() {
    Bundle output = new Bundle();
    if (!getTargetContext().getPackageName().equals("com.kejian.reviewfix20260913")) {
      output.putString("stream", "Refusing non-QA package\n"); finish(Activity.RESULT_CANCELED, output); return;
    }
    try {
      a = (MainActivity) startActivitySync(new Intent(getTargetContext(), MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
      runCase("settings toggles persist", this::settings);
      runCase("settings hierarchy preserves independent switches", this::settingsHierarchy);
      runCase("AI configuration inputs and return", this::aiSettings);
      runCase("editor preserves values and saves", this::editor);
      runCase("editor close does not save", this::closeEditor);
      runCase("invalid editor input does not save", this::invalidEditor);
      runCase("editor week shortcuts and database save", this::editorDatabase);
      runCase("settings navigation remains connected", this::settingsNavigation);
    } catch (Throwable e) { failed++; report.append("SETUP FAIL: ").append(e).append('\n'); }
    output.putString("stream", "\n"+report+"PASS="+passed+" FAIL="+failed+"\n");
    finish(failed == 0 ? Activity.RESULT_OK : Activity.RESULT_CANCELED, output);
  }
  private void runCase(String name, Check test) {
    try { test.run(); passed++; report.append("PASS: ").append(name).append('\n'); }
    catch (Throwable e) { failed++; report.append("FAIL: ").append(name).append(" — ").append(e).append('\n'); }
  }
  private void ui(Check action) throws Exception {
    Throwable[] error = {null};
    runOnMainSync(() -> { try { action.run(); } catch(Throwable e) { error[0] = e; } });
    waitForIdleSync(); if(error[0]!=null) throw new Exception(error[0]);
  }
  private static void require(boolean ok, String message) { if(!ok) throw new AssertionError(message); }
  private View root() { return a.getWindow().getDecorView(); }
  private ImportController importer() throws Exception {
    Field f = MainActivity.class.getDeclaredField("importer"); f.setAccessible(true); return (ImportController)f.get(a);
  }
  private void xmlId(View root, String name) {
    if (phase.equals("before")) return;
    int id = a.getResources().getIdentifier(name, "id", a.getPackageName());
    require(id != 0 && root.findViewById(id) != null, "Missing bound XML id " + name);
  }
  private void settings() throws Exception {
    ui(() -> a.showTab(3));
    capture("settings-top");
    List<Switch> switches = new ArrayList<>(); collect(root(), Switch.class, switches);
    require(switches.size()==2, "Expected two settings switches");
    for(int i=0; i<2; i++) {
      Switch s=switches.get(i); String key=i==0?"weekends":"highlight";
      boolean original=s.isChecked();
      ui(() -> s.setChecked(!original));
      require(a.prefs.getBoolean(key, original)==!original, "Toggle not persisted: "+key);
      ui(() -> s.setChecked(original));
    }
    ui(() -> a.showTab(3));
    List<ScrollView> scrolls=new ArrayList<>(); collect(root(), ScrollView.class, scrolls);
    ui(() -> scrolls.get(0).fullScroll(View.FOCUS_DOWN)); capture("settings-bottom");
    TextView backup=find(root(),"备份课表"); require(backup!=null, "Backup action missing");
    xmlId(root(), "settings_terms");
  }
  private void aiSettings() throws Exception {
    AiConfig.save(a, "https://example.com/v1/chat/completions", "test-model", "local-test");
    ui(() -> importer().settings());
    capture("ai-top");
    for(String id:new String[]{"ai_endpoint","ai_model","ai_key","ai_status","ai_test","ai_save"}) xmlId(root(),id);
    List<EditText> inputs=new ArrayList<>(); collect(root(),EditText.class,inputs);
    require(inputs.size()==3,"Expected three AI inputs");
    require(!inputs.get(2).isSaveEnabled(),"Key view state must not persist");
    require(inputs.get(2).getTransformationMethod() instanceof android.text.method.PasswordTransformationMethod,"Key not masked");
    ui(() -> inputs.get(1).setText("updated-model"));
    ui(() -> clickable(find(root(),"保存配置")).performClick());
    require(AiConfig.model(a).equals("updated-model"),"Model not saved");
    require(findContaining(root(),"尚未验证")!=null,"Unverified save status missing");
    List<ScrollView> scrolls=new ArrayList<>(); collect(root(),ScrollView.class,scrolls);
    ui(() -> scrolls.get(0).fullScroll(View.FOCUS_DOWN)); capture("ai-bottom");
    ui(() -> clickable(find(root(),"返回导入")).performClick());
    require(find(root(),"智能导入")!=null,"Return navigation failed");
  }
  private void settingsHierarchy() throws Exception {
    boolean weekends=a.prefs.getBoolean("weekends",true), highlight=a.prefs.getBoolean("highlight",true);
    try {
      ui(() -> {
        a.showTab(3);
        List<Switch> switches=new ArrayList<>(); collect(root(),Switch.class,switches);
        switches.get(0).setChecked(false); switches.get(1).setChecked(true);
        android.util.SparseArray<android.os.Parcelable> state=new android.util.SparseArray<>();
        root().saveHierarchyState(state);
        root().restoreHierarchyState(state);
      });
      require(!a.prefs.getBoolean("weekends",true),"Hierarchy restoration overwrote weekends preference");
      require(a.prefs.getBoolean("highlight",false),"Hierarchy restoration overwrote highlight preference");
    } finally {
      ui(() -> { a.prefs.edit().putBoolean("weekends",weekends).putBoolean("highlight",highlight).commit(); a.showTab(3); });
    }
  }
  private Course sample() {
    Course c=new Course(); c.title="XML test course"; c.teacher="Teacher"; c.room="Room 101";
    c.weeks=Arrays.asList(1,3); c.notes="Test note"; return c;
  }
  private void editor() throws Exception {
    Course original=sample(); Course[] saved={null}; Dialog[] dialog={null};
    ui(() -> dialog[0]=CourseEditor.open(a, original,(c,picked)->saved[0]=c));
    View r=dialog[0].getWindow().getDecorView(); capture("editor-top");
    for(String id:new String[]{"course_name","course_teacher","course_room","course_notes","course_save","course_close"}) xmlId(r,id);
    List<EditText> inputs=new ArrayList<>(); collect(r,EditText.class,inputs);
    require(inputs.size()>=5,"Editor fields missing");
    require(inputs.get(0).getText().toString().equals(original.title),"Initial name lost");
    List<Spinner> spinners=new ArrayList<>(); collect(r,Spinner.class,spinners);
    require(spinners.size()==3,"Expected three spinners");
    ui(() -> { inputs.get(0).setText("Edited XML course"); spinners.get(0).setSelection(6); });
    List<ScrollView> scrolls=new ArrayList<>(); collect(r,ScrollView.class,scrolls);
    ui(() -> scrolls.get(0).fullScroll(View.FOCUS_DOWN)); capture("editor-bottom");
    ui(() -> clickable(find(r,"保存课程")).performClick());
    require(saved[0]!=null,"Save callback missing");
    require(saved[0].title.equals("Edited XML course") && saved[0].day==7,"Saved fields incorrect");
    require(saved[0].weeks.equals(Arrays.asList(1,3)),"Sparse weeks changed");
    require(original.title.equals("XML test course"),"Original mutated before callback");
    require(!dialog[0].isShowing(),"Save did not dismiss");
  }
  private void closeEditor() throws Exception {
    boolean[] saved={false}; Dialog[] dialog={null};
    ui(() -> dialog[0]=CourseEditor.open(a,null,(c,p)->saved[0]=true));
    ui(() -> clickable(find(dialog[0].getWindow().getDecorView(),"关闭")).performClick());
    require(!saved[0] && !dialog[0].isShowing(),"Close saved or did not dismiss");
  }
  private void invalidEditor() throws Exception {
    boolean[] saved={false}; Dialog[] dialog={null};
    ui(() -> dialog[0]=CourseEditor.open(a,null,(c,p)->saved[0]=true));
    View r=dialog[0].getWindow().getDecorView();
    ui(() -> clickable(find(r,"保存课程")).performClick());
    require(!saved[0] && dialog[0].isShowing(),"Invalid blank title saved or editor closed");
    sendKeyDownUpSync(KeyEvent.KEYCODE_BACK); waitForIdleSync();
    ui(() -> dialog[0].dismiss());
  }
  private void editorDatabase() throws Exception {
    long term=a.db.saveTerm(new ScheduleDb.Term(0,"XML QA term","2026-09-07",20));
    long previous=a.termId;
    Dialog[] dialog={null}; Course[] saved={null};
    try {
      ui(() -> {
        a.activateTerm(term);
        dialog[0]=CourseEditor.open(a,null,(c,p)->{a.db.save(c);saved[0]=c;});
      });
      View r=dialog[0].getWindow().getDecorView();
      List<EditText> inputs=new ArrayList<>(); collect(r,EditText.class,inputs);
      ui(() -> { inputs.get(0).setText("XML persisted course"); clickable(find(r,"单周")).performClick(); });
      ui(() -> clickable(find(r,"保存课程")).performClick());
      require(saved[0]!=null,"New course not saved");
      List<Course> rows=a.db.courses(term); require(rows.size()==1,"Database write missing");
      require(rows.get(0).weeks.equals(Arrays.asList(1,3,5,7,9,11,13,15,17,19)),"Odd weeks changed");
      ui(() -> dialog[0]=CourseEditor.open(a,rows.get(0),(c,p)->a.db.save(c)));
      View reopened=dialog[0].getWindow().getDecorView();
      List<EditText> edited=new ArrayList<>(); collect(reopened,EditText.class,edited);
      require(edited.get(0).getText().toString().equals("XML persisted course"),"Reopen lost saved title");
      ui(() -> edited.get(2).setText("Updated room"));
      View editorRoot=reopened;
      ui(() -> clickable(find(editorRoot,"保存课程")).performClick());
      require(a.db.courses(term).size()==1 && a.db.courses(term).get(0).room.equals("Updated room"),"Edit duplicated/lost course");
    } finally {
      ui(() -> { if(dialog[0]!=null)dialog[0].dismiss(); a.activateTerm(previous); a.db.deleteTerm(term); });
    }
  }
  private void settingsNavigation() throws Exception {
    for(String[] pair:new String[][]{{"学期管理","学期管理"},{"作息时间","作息时间"},{"AI 配置","AI 配置"},{"导入课表文件","智能导入"},{"待补充事项","待补充事项"}}) {
      ui(() -> a.showTab(3));
      ui(() -> clickable(find(root(),pair[0])).performClick());
      require(find(root(),pair[1])!=null,"Settings route failed: "+pair[0]);
    }
  }
  private static View clickable(View v) {
    if(v==null) throw new AssertionError("Missing clickable control");
    while(!v.isClickable() && v.getParent() instanceof View) v=(View)v.getParent();
    return v;
  }
  private static TextView find(View v,String text) {
    if(v instanceof TextView && ((TextView)v).getText().toString().equals(text))return (TextView)v;
    if(v instanceof ViewGroup)for(int i=0;i<((ViewGroup)v).getChildCount();i++){TextView t=find(((ViewGroup)v).getChildAt(i),text);if(t!=null)return t;}
    return null;
  }
  private static TextView findContaining(View v,String text) {
    if(v instanceof TextView && ((TextView)v).getText().toString().contains(text))return (TextView)v;
    if(v instanceof ViewGroup)for(int i=0;i<((ViewGroup)v).getChildCount();i++){TextView t=findContaining(((ViewGroup)v).getChildAt(i),text);if(t!=null)return t;}
    return null;
  }
  private static <T> void collect(View v,Class<T> type,List<T> out) {
    if(type.isInstance(v))out.add(type.cast(v));
    if(v instanceof ViewGroup)for(int i=0;i<((ViewGroup)v).getChildCount();i++)collect(((ViewGroup)v).getChildAt(i),type,out);
  }
  private void capture(String name) throws Exception {
    waitForIdleSync();
    // Allow window transitions and Toasts to settle before visual comparison.
    android.os.SystemClock.sleep(name.equals("editor-top") ? 2500 : 300);
    Bitmap bitmap=getUiAutomation().takeScreenshot();
    require(bitmap!=null,"Screenshot failed");
    File dir=new File(a.getExternalFilesDir(null), "xml-qa/"+phase); dir.mkdirs();
    try(FileOutputStream out=new FileOutputStream(new File(dir,name+".png"))){bitmap.compress(Bitmap.CompressFormat.PNG,100,out);}
    bitmap.recycle();
  }
}
