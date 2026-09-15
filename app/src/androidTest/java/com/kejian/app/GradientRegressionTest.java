package com.kejian.app;

import android.app.*;
import android.graphics.*;
import android.os.Bundle;
import android.view.View;
import java.util.*;

/** Pixel regression; fabricated courses only, no database or network access. */
public class GradientRegressionTest extends Instrumentation {
  @Override public void onCreate(Bundle args) { super.onCreate(args); start(); }
  @Override public void onStart() {
    Bundle result = new Bundle();
    Throwable[] failure = {null};
    runOnMainSync(() -> {
      try {
        Course source = new Course(); source.title = "标记测试"; source.weeks.add(1);
        org.json.JSONObject legacy = source.json(); legacy.remove("colorManual");
        check(Course.from(legacy).json().optBoolean("colorManual", false), "Legacy colors must default to protected");
        check(source.json().has("colorManual"), "New courses must persist color origin");
        check(Course.from(legacy).copy().colorManual, "Copy lost legacy protection");
        databaseChecks();
        todaySolidChecks();
        for (String color : new String[]{"#AFE4A3", "#CBE4FF", "#FFD7E8", "#DCD5FF", "#EEEEEE"}) {
          Course a = new Course(); a.title = "测试"; a.color = color; a.weeks.add(1);
          Course b = a.copy(); b.day = 2;
          TimetableView view = new TimetableView(getTargetContext(), Arrays.asList(a,b),5,2,1,null,null,-1,c -> {});
          int width = Ui.dp(getTargetContext(),638);
          view.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),0);
          view.layout(0,0,width,view.getMeasuredHeight());
          Bitmap first = render(view);
          java.lang.reflect.Field field = TimetableView.class.getDeclaredField("gradientCache");
          field.setAccessible(true);
          Map<?,?> cache = (Map<?,?>)field.get(view);
          Object originalEntry = cache.get(a);
          int x = Ui.dp(getTargetContext(),80), y = Ui.dp(getTargetContext(),75);
          int step = Ui.dp(getTargetContext(),120);
          check(close(first.getPixel(x,y), first.getPixel(x+step,y)), "Card order changes gradient: " + color);
          check(!close(first.getPixel(x,y), first.getPixel(x+Ui.dp(getTargetContext(),55),y)), "Gradient missing: " + color);
          Bitmap again = render(view);
          check(cache.get(a) == originalEntry, "Unchanged gradient not reused");
          check(first.sameAs(again), "Repeated draw changes pixels");
          a.color = "#FF948C";
          Bitmap changed = render(view);
          check(cache.get(a) != originalEntry, "Color change retained stale cache");
          check(!close(first.getPixel(x,y),changed.getPixel(x,y)), "Color change not reflected");
          Object beforeResize = cache.get(a);
          view.layout(0,0,width + Ui.dp(getTargetContext(),100),view.getMeasuredHeight());
          Bitmap resized = render(view);
          check(cache.get(a) != beforeResize, "Resize retained stale gradient bounds");
          resized.recycle();
          first.recycle(); again.recycle(); changed.recycle();
        }
      } catch (Throwable e) { failure[0] = e; }
    });
    if(failure[0] == null) try { previewUiChecks(); } catch(Throwable e) { failure[0]=e; }
    result.putString("stream", failure[0] == null ? "PASS: color origin, database preview/apply/import/stale protection; 5 palettes, order/gradient/redraw/color-change/cache/resize\n" : "FAIL: " + failure[0] + "\n");
    finish(failure[0] == null ? Activity.RESULT_OK : Activity.RESULT_CANCELED, result);
  }
  private void todaySolidChecks() throws Exception {
    for(String color : new String[]{"#AFE4A3","#CBE4FF","#101020"}) {
      for(int today : new int[]{-1,0,1}) {
        Course a=new Course(); a.title="课程"; a.color=color; a.weeks.add(1);
        Course b=a.copy(); b.day=2;
        TimetableView view=new TimetableView(getTargetContext(),Arrays.asList(a,b),5,2,1,null,null,today,c -> {});
        int width=Ui.dp(getTargetContext(),638);
        view.measure(View.MeasureSpec.makeMeasureSpec(width,View.MeasureSpec.EXACTLY),0);
        view.layout(0,0,width,view.getMeasuredHeight());
        Bitmap bitmap=render(view);
        int y=Ui.dp(getTargetContext(),75);
        for(int day=0;day<2;day++) {
          int left=bitmap.getPixel(Ui.dp(getTargetContext(),80+day*120),y);
          int right=bitmap.getPixel(Ui.dp(getTargetContext(),135+day*120),y);
          if(day==today) {
            check(left==Color.parseColor(color) && right==left,"Today must use exact saved solid color");
          } else check(!close(left,right),"Other days must remain gradient");
        }
        Bitmap again=render(view); check(bitmap.sameAs(again),"Today repeated draw differs");
        bitmap.recycle(); again.recycle();
      }
    }
  }
  private void previewUiChecks() throws Exception {
    MainActivity activity = (MainActivity)startActivitySync(new android.content.Intent(getTargetContext(),MainActivity.class)
        .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK));
    ScheduleDb original=activity.db;
    ScheduleDb temporary=new ScheduleDb(getTargetContext(),null);
    try {
      long term=temporary.terms().get(0).id;
      Course c=new Course(); c.title="示例课程（自动）"; c.semesterId=term; c.weeks.add(1); temporary.save(c);
      Course manual=c.copy(); manual.id=0; manual.title="示例课程（保留）"; manual.day=2; manual.colorManual=true; temporary.save(manual);
      runOnMainSync(() -> { activity.db=temporary; activity.termId=term; activity.showTab(3); ColorOptimization.open(activity,term); });
      waitForIdleSync();
      clickButton("预览");
      android.view.accessibility.AccessibilityNodeInfo root=activeRoot();
      check(!root.findAccessibilityNodeInfosByText("配色预览（尚未保存）").isEmpty(),"Preview dialog absent");
      check(root.findAccessibilityNodeInfosByText(manual.title).isEmpty(),"Protected title selected by default");
      Bitmap screenshot=getUiAutomation().takeScreenshot();
      java.io.File target=new java.io.File(getTargetContext().getExternalFilesDir(null),"color-preview.png");
      try(java.io.FileOutputStream output=new java.io.FileOutputStream(target)) { screenshot.compress(Bitmap.CompressFormat.PNG,100,output); }
      screenshot.recycle();
      clickButton("取消");
      check(temporary.colorFor(term,c.title).equals(c.color),"Cancel writes colors");
      runOnMainSync(() -> ColorOptimization.open(activity,term)); waitForIdleSync();
      clickButton("预览"); clickButton("确认应用");
      check(temporary.colorFor(term,manual.title).equals(manual.color) && temporary.isColorManual(term,manual.title),"UI apply changes protected title");
      check(!temporary.colorFor(term,c.title).equals(c.color),"UI apply did not save optimized color");
    } finally {
      runOnMainSync(() -> {activity.db=original; activity.finish();});
      temporary.close();
    }
  }
  private void clickButton(String label) {
    waitForIdleSync();
    android.view.accessibility.AccessibilityNodeInfo root=activeRoot();
    for(android.view.accessibility.AccessibilityNodeInfo node : root.findAccessibilityNodeInfosByText(label)) {
      if(label.contentEquals(node.getText()==null?"":node.getText()) && node.isClickable()) {
        check(node.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK),"Click failed: "+label);
        waitForIdleSync(); return;
      }
    }
    throw new AssertionError("Button absent: "+label);
  }
  private android.view.accessibility.AccessibilityNodeInfo activeRoot() {
    try { getUiAutomation().waitForIdle(300,5000); } catch(Exception ignored) {}
    for(int i=0;i<50;i++) {
      android.view.accessibility.AccessibilityNodeInfo root=getUiAutomation().getRootInActiveWindow();
      if(root!=null) return root;
      android.os.SystemClock.sleep(100);
    }
    throw new AssertionError("Accessibility window unavailable");
  }
  private void databaseChecks() throws Exception {
    try(ScheduleDb db = new ScheduleDb(getTargetContext(),null)) {
      long term = db.terms().get(0).id;
      Course a = new Course(); a.title="手选"; a.weeks.add(1); a.semesterId=term;
      a.color="#AFE4A3"; a.colorManual=true; db.save(a);
      Course b=a.copy(); b.id=0; b.title="自动"; b.day=2; b.colorManual=false; db.save(b);
      List<Course> before=db.courses(term);
      Map<String,String> plan=ColorPlan.create(before,new HashSet<>(Arrays.asList(b.title)));
      check(!plan.containsKey(a.title),"Unselected protected title recolored");
      check(db.colorFor(term,b.title).equals(b.color),"Preview writes data");
      db.applyColorPlan(term,before,plan);
      check(db.colorFor(term,a.title).equals(a.color) && db.isColorManual(term,a.title),"Manual color lost");
      check(db.colorFor(term,b.title).equals(plan.get(b.title)),"Plan not applied");
      boolean rejected=false;
      try { db.applyColorPlan(term,before,plan); } catch(IllegalStateException expected) { rejected=true; }
      check(rejected,"Stale preview accepted");
      Course incoming=a.copy(); incoming.id=0; incoming.day=3; incoming.color="#FFD7E8"; incoming.colorManual=false;
      db.importCourses(Arrays.asList(incoming),term);
      check(incoming.color.equals(a.color) && incoming.colorManual,"Import overwrites manual color");
      Course fresh=incoming.copy(); fresh.id=0; fresh.title="新导入"; fresh.day=4;
      db.importCourses(Arrays.asList(fresh),term);
      check(!fresh.colorManual,"AI metadata locks new imported color");
      List<Course> current=db.courses(term);
      Map<String,String> bad=new HashMap<>(); bad.put(b.title,"invalid");
      rejected=false;
      try { db.applyColorPlan(term,current,bad); } catch(IllegalArgumentException expected) { rejected=true; }
      check(rejected && db.colorFor(term,b.title).equals(plan.get(b.title)),"Invalid plan writes data");
    }
  }
  private static Bitmap render(View view) {
    Bitmap b = Bitmap.createBitmap(view.getWidth(),view.getHeight(),Bitmap.Config.ARGB_8888);
    view.draw(new Canvas(b)); return b;
  }
  private static boolean close(int a,int b) {
    return Math.abs(Color.red(a)-Color.red(b)) <= 2 && Math.abs(Color.green(a)-Color.green(b)) <= 2 && Math.abs(Color.blue(a)-Color.blue(b)) <= 2;
  }
  private static void check(boolean ok,String message) { if(!ok) throw new AssertionError(message); }
}
