package com.kejian.app;

import java.io.InterruptedIOException;
import java.net.HttpURLConnection;

/** One cancellation token per request; a cancelled token can never be reused. */
final class RequestCancellation {
  private boolean cancelled;
  private HttpURLConnection connection;

  synchronized void check() throws InterruptedIOException {
    if (cancelled || Thread.currentThread().isInterrupted())
      throw new InterruptedIOException("请求已取消");
  }

  synchronized void attach(HttpURLConnection value) throws InterruptedIOException {
    check();
    connection = value;
  }

  synchronized void detach(HttpURLConnection value) {
    if (connection == value) connection = null;
  }

  void cancel() {
    HttpURLConnection value;
    synchronized (this) {
      cancelled = true;
      value = connection;
      connection = null;
    }
    if (value != null) value.disconnect();
  }
}
