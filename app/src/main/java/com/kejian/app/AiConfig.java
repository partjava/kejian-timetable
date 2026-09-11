package com.kejian.app;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.security.Key;
import java.security.KeyStore;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/**
 * The AI endpoint this device talks to. The API key is sealed with a key held by the Android
 * Keystore, so the ciphertext in SharedPreferences is useless off the device and is never written
 * into the APK, the source tree, or a timetable backup.
 */
public final class AiConfig {
  public static final String DEFAULT_URL = "https://api.deepseek.com/chat/completions";
  public static final String DEFAULT_MODEL = "deepseek-chat";

  private static final String PREFS = "preferences";
  private static final String KEY_URL = "aiUrl", KEY_MODEL = "aiModel", KEY_KEY = "aiKey";
  private static final String ALIAS = "kejian-ai-key";
  private static final String TRANSFORM = "AES/GCM/NoPadding";
  private static final int TAG_BITS = 128, IV_BYTES = 12;
  /** Hosts allowed to use plain HTTP: the device itself and the emulator's host loopback. */
  private static final List<String> LOOPBACK =
      Arrays.asList("127.0.0.1", "localhost", "10.0.2.2", "::1");

  private AiConfig() {}

  private static SharedPreferences prefs(Context c) {
    return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
  }

  public static String url(Context c) {
    return prefs(c).getString(KEY_URL, DEFAULT_URL);
  }

  public static String model(Context c) {
    return prefs(c).getString(KEY_MODEL, DEFAULT_MODEL);
  }

  public static boolean hasKey(Context c) {
    return !prefs(c).getString(KEY_KEY, "").isEmpty();
  }

  /**
   * The decrypted key, or null when none is stored or the stored one cannot be opened. A key
   * becomes unreadable when the app is restored onto a different device or the lock screen
   * credential changes, because Keystore keys do not travel with a backup. That is a recoverable
   * state, not an error: the stale ciphertext is dropped so the user is asked for the key again.
   */
  public static String key(Context c) {
    String stored = prefs(c).getString(KEY_KEY, "");
    if (stored.isEmpty()) return null;
    try {
      byte[] blob = Base64.decode(stored, Base64.NO_WRAP);
      if (blob.length <= IV_BYTES) throw new IllegalStateException("密钥数据不完整");
      Cipher cipher = Cipher.getInstance(TRANSFORM);
      cipher.init(
          Cipher.DECRYPT_MODE, secretKey(), new GCMParameterSpec(TAG_BITS, blob, 0, IV_BYTES));
      return new String(
          cipher.doFinal(blob, IV_BYTES, blob.length - IV_BYTES), StandardCharsets.UTF_8);
    } catch (Exception e) {
      clearKey(c);
      return null;
    }
  }

  /** Stores the endpoint. A null or empty key leaves any stored key untouched. */
  public static void save(Context c, String url, String model, String key) throws Exception {
    SharedPreferences.Editor edit =
        prefs(c)
            .edit()
            .putString(KEY_URL, normalize(url))
            .putString(KEY_MODEL, model == null ? "" : model.trim());
    if (key != null && !key.trim().isEmpty()) edit.putString(KEY_KEY, encrypt(key.trim()));
    edit.apply();
  }

  public static void clearKey(Context c) {
    prefs(c).edit().remove(KEY_KEY).apply();
  }

  /** Trims the input and drops trailing slashes so the path can be appended without doubling. */
  public static String normalize(String input) {
    return input == null ? "" : input.trim().replaceAll("/+$", "");
  }

  /** Problems with the address, empty when it is usable. */
  public static List<String> validate(String input) {
    List<String> problems = new ArrayList<>();
    String s = input == null ? "" : input.trim();
    if (s.isEmpty()) {
      problems.add("请填写 API 地址");
      return problems;
    }
    URI u;
    try {
      u = new URI(s);
    } catch (URISyntaxException e) {
      problems.add("API 地址格式不正确");
      return problems;
    }
    String host = u.getHost();
    if (host == null || host.isEmpty()) problems.add("API 地址缺少主机名");
    if (u.getUserInfo() != null) problems.add("API 地址不能包含用户名或密码");
    if (u.getFragment() != null) problems.add("API 地址不能包含 # 片段");
    // A query string is allowed: Azure OpenAI needs ?api-version= on the endpoint itself.
    boolean secure = "https".equals(u.getScheme());
    if (!secure && !("http".equals(u.getScheme()) && LOOPBACK.contains(host)))
      problems.add("请使用 HTTPS 地址，HTTP 仅限本机地址");
    return problems;
  }

  private static SecretKey secretKey() throws Exception {
    KeyStore store = KeyStore.getInstance("AndroidKeyStore");
    store.load(null);
    Key existing = store.getKey(ALIAS, null);
    if (existing instanceof SecretKey) return (SecretKey) existing;
    KeyGenerator generator =
        KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
    generator.init(
        new KeyGenParameterSpec.Builder(
                ALIAS, KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            // Requiring authentication would prompt for the lock screen on every import.
            .setUserAuthenticationRequired(false)
            .build());
    return generator.generateKey();
  }

  private static String encrypt(String plain) throws Exception {
    Cipher cipher = Cipher.getInstance(TRANSFORM);
    cipher.init(Cipher.ENCRYPT_MODE, secretKey());
    byte[] iv = cipher.getIV(), body = cipher.doFinal(plain.getBytes(StandardCharsets.UTF_8));
    byte[] blob = new byte[iv.length + body.length];
    System.arraycopy(iv, 0, blob, 0, iv.length);
    System.arraycopy(body, 0, blob, iv.length, body.length);
    return Base64.encodeToString(blob, Base64.NO_WRAP);
  }
}
