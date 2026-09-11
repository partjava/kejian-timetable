package com.kejian.app;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * Reads a picked timetable file into a grid of cell text, and renders it for the AI prompt.
 *
 * <p>Pure Java on purpose: the prompt payload must match the shape the model was tuned on, and
 * keeping the JSON writer here (rather than org.json) lets {@code tests/} exercise this on the JVM.
 */
public final class Sheets {
  private Sheets() {}

  /** Characters we will send to the model. Mirrors the limit the desktop service enforced. */
  private static final int MAX_PROMPT_CHARS = 200000;

  /** One worksheet: its name, a dense grid of cell text, and merged ranges. */
  public static final class Sheet {
    public final String name;
    public final List<String[]> rows;
    public final List<int[]> merges;

    public Sheet(String name, List<String[]> rows, List<int[]> merges) {
      this.name = name;
      this.rows = rows;
      this.merges = merges;
    }
  }

  public static List<Sheet> read(String filename, byte[] data) throws IOException {
    if (data == null || data.length == 0) throw new IOException("文件是空的");
    String name = filename == null ? "" : filename.toLowerCase(Locale.ROOT);
    if (name.endsWith(".xls")) return XlsReader.read(data);
    if (name.endsWith(".csv") || name.endsWith(".txt")) return readCsv(data);
    if (name.endsWith(".xlsx"))
      throw new IOException("暂不支持 .xlsx，请在 Excel 中另存为 .xls 或 .csv 后再导入");
    if (name.endsWith(".png") || name.endsWith(".jpg") || name.endsWith(".jpeg"))
      throw new IOException("这一版只识别表格，课表截图请在教务系统导出 .xls 或 .csv 后再导入");
    throw new IOException("仅支持 .xls 和 .csv 文件");
  }

  private static List<Sheet> readCsv(byte[] data) {
    List<String> lines = splitCsv(decode(data));
    List<String[]> rows = new ArrayList<>();
    for (String line : lines) {
      if (rows.size() >= 2000) break;
      List<String> cells = parseCsvLine(line);
      String[] row = new String[Math.min(cells.size(), 100)];
      for (int i = 0; i < row.length; i++) row[i] = cells.get(i).trim();
      rows.add(row);
    }
    return Collections.singletonList(new Sheet("Sheet1", rows, new ArrayList<>()));
  }

  /** RFC 4180-ish split: quoted fields may contain commas, quotes and newlines. */
  private static List<String> splitCsv(String text) {
    List<String> lines = new ArrayList<>();
    StringBuilder line = new StringBuilder();
    boolean quoted = false;
    for (int i = 0; i < text.length(); i++) {
      char ch = text.charAt(i);
      if (ch == '"') quoted = !quoted;
      if (!quoted && (ch == '\n' || ch == '\r')) {
        if (ch == '\r' && i + 1 < text.length() && text.charAt(i + 1) == '\n') i++;
        lines.add(line.toString());
        line.setLength(0);
        continue;
      }
      line.append(ch);
    }
    lines.add(line.toString());
    return lines;
  }

  private static List<String> parseCsvLine(String line) {
    List<String> out = new ArrayList<>();
    StringBuilder field = new StringBuilder();
    boolean quoted = false;
    for (int i = 0; i < line.length(); i++) {
      char ch = line.charAt(i);
      if (quoted) {
        if (ch == '"') {
          if (i + 1 < line.length() && line.charAt(i + 1) == '"') {
            field.append('"');
            i++;
          } else {
            quoted = false;
          }
        } else {
          field.append(ch);
        }
      } else if (ch == '"') {
        quoted = true;
      } else if (ch == ',') {
        out.add(field.toString());
        field.setLength(0);
      } else {
        field.append(ch);
      }
    }
    out.add(field.toString());
    return out;
  }

  private static String decode(byte[] data) {
    for (String name : new String[] {"UTF-8", "GB18030"}) {
      try {
        String text =
            Charset.forName(name)
                .newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(data))
                .toString();
        // Escaped rather than a literal BOM: an invisible character in source is a trap for the
        // next editor that touches this file.
        return text.startsWith("\uFEFF") ? text.substring(1) : text;
      } catch (Exception ignored) {
        // Try the next encoding.
      }
    }
    return new String(data, StandardCharsets.UTF_8);
  }

  /**
   * Builds the user message for the model. The shape is fixed by the prompt the project has always
   * used, so it is reproduced exactly: 1-based row/column, empty cells omitted, merges as
   * {@code [rowFirst, rowLast, colFirst, colLast]} with exclusive ends.
   */
  public static String toPromptJson(List<Sheet> sheets) throws IOException {
    StringBuilder out = new StringBuilder();
    out.append("{\"untrustedWorksheetData\":[");
    for (int s = 0; s < sheets.size(); s++) {
      Sheet sheet = sheets.get(s);
      if (s > 0) out.append(',');
      out.append("{\"name\":");
      quote(out, sheet.name);
      out.append(",\"merges\":[");
      for (int i = 0; i < sheet.merges.size(); i++) {
        int[] m = sheet.merges.get(i);
        if (i > 0) out.append(',');
        out.append('[')
            .append(m[0])
            .append(',')
            .append(m[1])
            .append(',')
            .append(m[2])
            .append(',')
            .append(m[3])
            .append(']');
      }
      out.append("],\"cells\":[");
      boolean first = true;
      for (int r = 0; r < sheet.rows.size(); r++) {
        String[] row = sheet.rows.get(r);
        for (int c = 0; c < row.length; c++) {
          String text = row[c];
          if (text == null || text.isEmpty()) continue;
          if (!first) out.append(',');
          first = false;
          out.append("{\"row\":").append(r + 1).append(",\"column\":").append(c + 1).append(",\"text\":");
          quote(out, text);
          out.append('}');
        }
      }
      out.append("]}");
      if (out.length() > MAX_PROMPT_CHARS)
        throw new IOException("表格文字过多，请只保留课表工作表");
    }
    out.append("]}");
    if (out.length() > MAX_PROMPT_CHARS) throw new IOException("表格文字过多，请只保留课表工作表");
    return out.toString();
  }

  private static void quote(StringBuilder out, String value) {
    out.append('"');
    for (int i = 0; i < value.length(); i++) {
      char ch = value.charAt(i);
      switch (ch) {
        case '"':
          out.append("\\\"");
          break;
        case '\\':
          out.append("\\\\");
          break;
        case '\n':
          out.append("\\n");
          break;
        case '\r':
          out.append("\\r");
          break;
        case '\t':
          out.append("\\t");
          break;
        default:
          if (ch < 0x20 || Character.isSurrogate(ch)) {
            // Lone surrogates would make the JSON invalid; send the replacement character instead.
            if (Character.isHighSurrogate(ch) && i + 1 < value.length() && Character.isLowSurrogate(value.charAt(i + 1))) {
              out.append(ch).append(value.charAt(++i));
            } else if (Character.isSurrogate(ch)) {
              out.append('�');
            } else {
              out.append(String.format("\\u%04x", (int) ch));
            }
          } else {
            out.append(ch);
          }
      }
    }
    out.append('"');
  }
}
