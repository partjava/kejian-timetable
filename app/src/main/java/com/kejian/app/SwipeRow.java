package com.kejian.app;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Color;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.TextView;

/**
 * Wraps one list card so a left swipe reveals a red delete action behind it.
 *
 * The card keeps its own click listener — this class only borrows the horizontal drag, and only
 * once the gesture is clearly horizontal, so the surrounding ScrollView keeps every vertical
 * scroll. Framework only, no dependency.
 *
 * <p>RTL locales are deliberately out of scope. The delete button is pinned right and revealed by
 * a left swipe; the app's own strings are all Chinese, so mirroring the gesture would add branches
 * to the most fragile code here for nobody's benefit.
 */
@SuppressLint({"ClickableViewAccessibility", "RtlHardcoded"})
public class SwipeRow extends FrameLayout {
  private static final long SETTLE_MS = 160;
  private static final int ACTION_WIDTH_DP = 88;

  private final int actionWidth, touchSlop, pagingSlop;
  private View card;
  private Runnable onDelete;

  private boolean open; // set when an open animation starts, not when it ends
  private boolean tracking; // a gesture is being watched
  private boolean dragging; // the axis resolved to horizontal
  private float downX, downY, startTx;

  public SwipeRow(Context c) {
    this(c, "删除");
  }

  public SwipeRow(Context c, String label) {
    super(c);
    ViewConfiguration vc = ViewConfiguration.get(c);
    touchSlop = vc.getScaledTouchSlop();
    pagingSlop = vc.getScaledPagingTouchSlop();
    actionWidth = Ui.dp(c, ACTION_WIDTH_DP);
    TextView action = Ui.text(c, label, 15, Color.WHITE, true);
    action.setGravity(Gravity.CENTER);
    // 18dp matches Ui.card's corner radius, so the exposed red lines up with the card it replaces.
    action.setBackground(Ui.bg(Ui.DANGER, Ui.dp(c, 18)));
    action.setContentDescription(label);
    action.setClickable(true);
    action.setOnClickListener(
        v -> {
          if (onDelete != null) onDelete.run();
        });
    addView(action, new LayoutParams(actionWidth, LayoutParams.MATCH_PARENT, Gravity.RIGHT));
  }

  /** Wraps an already-built card. The card's own listeners are left alone. */
  public static SwipeRow wrap(Context c, View card, Runnable onDelete) {
    SwipeRow row = new SwipeRow(c);
    row.card = card;
    // FrameLayout.LayoutParams, never the LinearLayout ones the card was built with: passing those
    // to a FrameLayout throws at layout time.
    row.addView(card, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));
    row.onDelete = onDelete;
    return row;
  }

  public boolean isOpen() {
    return open;
  }

  /** Animates shut. Used when another row opens. */
  public void close() {
    settle(false);
  }

  /** Resets with no animation, for callers that are about to discard the view anyway. */
  public void closeNow() {
    open = false;
    tracking = false;
    dragging = false;
    if (card == null) return;
    card.animate().cancel();
    card.setTranslationX(0f);
  }

  @Override
  public boolean onInterceptTouchEvent(MotionEvent e) {
    switch (e.getActionMasked()) {
      case MotionEvent.ACTION_DOWN:
        beginGesture(e);
        // An open row swallows the whole gesture, so the card's click listener cannot fire; the
        // UP below closes it instead. A touch on the exposed delete button is left to the button.
        return open && e.getX() < getWidth() - actionWidth;
      case MotionEvent.ACTION_MOVE:
        if (dragging) return true;
        if (!tracking || !isHorizontal(e)) return false;
        dragging = true;
        disallowIntercept(true);
        return true;
      case MotionEvent.ACTION_UP:
      case MotionEvent.ACTION_CANCEL:
        // Only reached when the child kept the gesture; our own UP goes to onTouchEvent.
        tracking = false;
        dragging = false;
        return false;
      default:
        return false;
    }
  }

  @Override
  public boolean onTouchEvent(MotionEvent e) {
    switch (e.getActionMasked()) {
      case MotionEvent.ACTION_DOWN:
        // Reached only for a DOWN the interceptor swallowed; the state is already recorded, but
        // recording it twice is harmless and keeps the two paths from drifting apart.
        beginGesture(e);
        return true;
      case MotionEvent.ACTION_MOVE:
        if (!tracking) return false;
        if (!dragging) {
          if (!isHorizontal(e)) return true; // undecided: keep eating the gesture, move nothing
          dragging = true;
          disallowIntercept(true);
        }
        slide(startTx + (e.getX() - downX));
        return true;
      case MotionEvent.ACTION_UP:
      case MotionEvent.ACTION_CANCEL:
        return endGesture(e);
      default:
        return false;
    }
  }

  /** A MOVE that lands here without a preceding DOWN still has valid state — see beginGesture. */
  private void beginGesture(MotionEvent e) {
    tracking = true;
    dragging = false;
    downX = e.getX();
    downY = e.getY();
    startTx = card == null ? 0f : card.getTranslationX();
    if (card != null) card.animate().cancel(); // an in-flight settle would fight the drag
  }

  private boolean endGesture(MotionEvent e) {
    boolean wasDragging = dragging;
    tracking = false;
    dragging = false;
    // Unconditional: leaving the ScrollView locked out makes the list unscrollable for the rest of
    // the session, which reads as the app freezing.
    disallowIntercept(false);
    if (wasDragging) {
      float dx = e.getX() - downX;
      settle(dx < 0 && (dx <= -pagingSlop || cardTx() < -actionWidth / 2f));
    } else if (open) {
      settle(false); // a plain tap on an open row closes it
    }
    return true;
  }

  private boolean isHorizontal(MotionEvent e) {
    float dx = e.getX() - downX, dy = e.getY() - downY;
    return Math.abs(dx) > touchSlop && Math.abs(dx) > Math.abs(dy);
  }

  private float cardTx() {
    return card == null ? 0f : card.getTranslationX();
  }

  private void slide(float tx) {
    if (card != null) card.setTranslationX(Math.max(-actionWidth, Math.min(0f, tx)));
  }

  private void settle(boolean toOpen) {
    if (toOpen) closeOthers();
    open = toOpen;
    if (card == null) return;
    card.animate()
        .translationX(toOpen ? -actionWidth : 0f)
        .setDuration(SETTLE_MS)
        .setInterpolator(new DecelerateInterpolator())
        .start();
  }

  /** Reads the live view tree, so it cannot go stale and cannot leak. */
  private void closeOthers() {
    ViewParent parent = getParent();
    if (!(parent instanceof ViewGroup)) return;
    ViewGroup group = (ViewGroup) parent;
    for (int i = 0; i < group.getChildCount(); i++) {
      View child = group.getChildAt(i);
      // Ui.gap inserts plain spacer views between rows; the instanceof filters them out.
      if (child instanceof SwipeRow && child != this) ((SwipeRow) child).close();
    }
  }

  private void disallowIntercept(boolean disallow) {
    ViewParent parent = getParent();
    if (parent != null) parent.requestDisallowInterceptTouchEvent(disallow);
  }
}
