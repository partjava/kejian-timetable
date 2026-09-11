package com.kejian.app;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Intent;
import android.os.Bundle;

/** Run on a fresh isolated QA installation; never clears an existing user's data. */
public class CleanStartTest extends Instrumentation {
  @Override public void onCreate(Bundle args) { super.onCreate(args); start(); }
  @Override public void onStart() {
    Bundle out = new Bundle();
    try {
      Intent intent = new Intent(getTargetContext(), MainActivity.class);
      intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
      MainActivity activity = (MainActivity) startActivitySync(intent);
      waitForIdleSync();
      if (activity.db.terms().isEmpty()) throw new AssertionError("Default term missing");
      for (ScheduleDb.Term term : activity.db.terms()) {
        if (!activity.db.courses(term.id).isEmpty()) throw new AssertionError("Unexpected preloaded courses");
        if (activity.db.pending(term.id).length() != 0) throw new AssertionError("Unexpected pending items");
      }
      for (String asset : getTargetContext().getAssets().list("")) {
        if (asset.endsWith(".xls") || asset.endsWith(".csv") || asset.equals("sample-result.json"))
          throw new AssertionError("Unexpected timetable asset");
      }
      out.putString("stream", "\nPASS: default term exists, zero courses, zero pending, no timetable assets\n");
      finish(Activity.RESULT_OK, out);
    } catch (Throwable error) {
      out.putString("stream", "\nFAIL: " + error + "\n");
      finish(Activity.RESULT_CANCELED, out);
    }
  }
}
