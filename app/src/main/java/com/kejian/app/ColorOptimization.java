package com.kejian.app;

import android.app.AlertDialog;
import android.graphics.drawable.GradientDrawable;
import android.view.View;
import android.widget.*;
import java.util.*;

/** Selection and preview are read-only. Only the final confirmation writes colors. */
final class ColorOptimization {
  static void open(MainActivity a, long term) {
    List<Course> snapshot = a.db.courses(term);
    Map<String,Boolean> protectedTitles = new TreeMap<>();
    for (Course c : snapshot) protectedTitles.put(c.title,
        c.colorManual || Boolean.TRUE.equals(protectedTitles.get(c.title)));
    if (snapshot.isEmpty()) { a.toast("当前学期还没有课程"); return; }
    String[] titles = protectedTitles.keySet().toArray(new String[0]);
    String[] labels = new String[titles.length];
    boolean[] selected = new boolean[titles.length];
    for (int i=0;i<titles.length;i++) {
      selected[i] = !protectedTitles.get(titles[i]);
      labels[i] = titles[i] + (selected[i] ? " · 自动配色" : " · 手选或旧版颜色，默认保留");
    }
    AlertDialog dialog = new AlertDialog.Builder(a).setTitle("选择要优化的课程")
        .setMultiChoiceItems(labels,selected,(d,i,checked) -> selected[i]=checked)
        .setNegativeButton("取消",null).setPositiveButton("预览",null).create();
    dialog.setOnShowListener(d -> dialog.getButton(-1).setOnClickListener(v -> {
      Set<String> chosen = new HashSet<>();
      for(int i=0;i<titles.length;i++) if(selected[i]) chosen.add(titles[i]);
      if(chosen.isEmpty()) { a.toast("请勾选要优化的课程；未勾选的保持原色"); return; }
      dialog.dismiss(); preview(a,term,snapshot,ColorPlan.create(snapshot,chosen));
    }));
    dialog.show();
  }

  private static void preview(MainActivity a,long term,List<Course> snapshot,Map<String,String> colors) {
    ScrollView scroll = new ScrollView(a);
    LinearLayout body = Ui.col(a); Ui.pad(body,20,12); scroll.addView(body);
    body.addView(Ui.text(a,"左：当前颜色    右：优化后\n未勾选课程不变。确认后，所选课程改为自动配色。课程较多时仍可能有近似色。",13,Ui.MUTED,false));
    Set<String> shown = new HashSet<>();
    for(Course c : snapshot) if(colors.containsKey(c.title) && shown.add(c.title)) {
      View row = a.getLayoutInflater().inflate(R.layout.item_color_preview,body,false);
      ((TextView)row.findViewById(R.id.preview_title)).setText(c.title);
      swatch(row.findViewById(R.id.preview_before),c.color);
      swatch(row.findViewById(R.id.preview_after),colors.get(c.title));
      body.addView(row);
    }
    AlertDialog dialog = new AlertDialog.Builder(a).setTitle("配色预览（尚未保存）")
        .setView(scroll).setNegativeButton("取消",null).setPositiveButton("确认应用",null).create();
    dialog.setOnShowListener(d -> dialog.getButton(-1).setOnClickListener(v -> {
      try {
        a.db.applyColorPlan(term,snapshot,colors);
        dialog.dismiss(); a.showTab(3); a.toast("已应用所选课程的新配色");
      } catch(Exception e) { Ui.error(a,e); }
    }));
    dialog.show();
  }

  private static void swatch(View view,String color) {
    int[] colors = CourseColors.gradient(Ui.color(color));
    GradientDrawable background = new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
        new int[]{colors[0],colors[1]});
    background.setCornerRadius(Ui.dp(view.getContext(),10));
    view.setBackground(background);
    view.setContentDescription("颜色 " + color);
  }
  private ColorOptimization() {}
}
