package com.kejian.app;

import java.io.IOException;
import java.util.*;

/**
 * Minimal OLE2 + BIFF8 reader for legacy .xls timetables.
 *
 * <p>Extracts only what the importer needs: cell text, the grid it sits in, and merged ranges.
 * Formatting, fonts, formulas and dates are ignored. Pure Java, no Android imports, so it can be
 * exercised by {@code tests/XlsReaderTest} outside the APK.
 */
public final class XlsReader {
  private XlsReader() {}

  private static final int MAX_SHEETS = 16;
  private static final int MAX_ROWS = 2000;
  private static final int MAX_COLS = 100;
  private static final long MAX_CELLS = 200000L;

  private static final int BOF = 0x0809;
  private static final int EOF = 0x000A;
  private static final int BOUNDSHEET = 0x0085;
  private static final int SST = 0x00FC;
  private static final int CONTINUE = 0x003C;
  private static final int LABELSST = 0x00FD;
  private static final int LABEL = 0x0204;
  private static final int RSTRING = 0x00D6;
  private static final int RK = 0x027E;
  private static final int NUMBER = 0x0203;
  private static final int MULRK = 0x00BD;
  private static final int BLANK = 0x0201;
  private static final int MULBLANK = 0x00BE;
  private static final int FORMULA = 0x0006;
  private static final int STRING = 0x0207;
  private static final int MERGEDCELLS = 0x00E5;

  public static List<Sheets.Sheet> read(byte[] data) throws IOException {
    return parseBiff(workbookStream(data));
  }

  // ---------------------------------------------------------------- OLE2 container

  /**
   * Pulls the {@code Workbook} stream out of the OLE2 compound file. Only the FAT, directory and
   * mini-stream machinery is needed to locate it.
   */
  private static byte[] workbookStream(byte[] data) throws IOException {
    if (data.length < 512 || u32(data, 0) != 0xE011CFD0L || u32(data, 4) != 0xE11AB1A1L)
      throw new IOException("不是有效的 .xls 文件（缺少 OLE2 文件头）");

    int sectorShift = u16(data, 30);
    int miniShift = u16(data, 32);
    if (sectorShift != 9 && sectorShift != 12)
      throw new IOException("暂不支持该 .xls 的扇区大小");
    int sectorSize = 1 << sectorShift;
    int miniSize = 1 << miniShift;
    long miniCutoff = u32(data, 56);

    long[] fat = readFat(data, sectorSize, (int) u32(data, 44), u32(data, 68), u32(data, 72));

    List<DirEntry> entries = readDirectory(data, sectorSize, fat, u32(data, 48));
    DirEntry root = null;
    DirEntry workbook = null;
    for (DirEntry e : entries) {
      if (e.type == 5 && root == null) root = e;
      if (e.type == 2 && ("Workbook".equals(e.name) || "Book".equals(e.name)) && workbook == null)
        workbook = e;
    }
    if (workbook == null) throw new IOException("这个 .xls 里没有找到 Workbook 数据流");
    if (workbook.size <= 0) throw new IOException(".xls 的 Workbook 数据流是空的");

    if (workbook.size < miniCutoff) {
      if (root == null) throw new IOException(".xls 缺少根目录项");
      byte[] mini = readChain(data, sectorSize, fat, root.start, root.size, -1, false);
      long[] miniFat = readMiniFat(data, sectorSize, fat, u32(data, 60));
      return readChain(mini, miniSize, miniFat, workbook.start, workbook.size, workbook.size, true);
    }
    return readChain(data, sectorSize, fat, workbook.start, workbook.size, -1, false);
  }

  private static long[] readFat(byte[] data, int sectorSize, int fatSectors, long difatStart, long difatCount)
      throws IOException {
    List<Long> difat = new ArrayList<>();
    for (int i = 0; i < 109; i++) difat.add(u32(data, 76 + i * 4));
    long next = difatStart;
    int perSector = sectorSize / 4;
    for (long seen = 0; isRegularSector(next) && seen <= difatCount + 8; seen++) {
      int base = sectorOffset(next, sectorSize);
      requireRange(data, base, sectorSize);
      for (int i = 0; i < perSector - 1; i++) difat.add(u32(data, base + i * 4));
      next = u32(data, base + (perSector - 1) * 4);
    }
    if (fatSectors <= 0 || fatSectors > difat.size())
      throw new IOException(".xls 的 FAT 扇区数量无效");

    long[] fat = new long[fatSectors * perSector];
    int n = 0;
    for (int i = 0; i < fatSectors; i++) {
      long sector = difat.get(i);
      if (!isRegularSector(sector)) throw new IOException(".xls 的 FAT 链断裂");
      int base = sectorOffset(sector, sectorSize);
      requireRange(data, base, sectorSize);
      for (int j = 0; j < perSector; j++) fat[n++] = u32(data, base + j * 4);
    }
    return fat;
  }

  private static long[] readMiniFat(byte[] data, int sectorSize, long[] fat, long start)
      throws IOException {
    byte[] raw = readChain(data, sectorSize, fat, start, -1, -1, false);
    long[] miniFat = new long[raw.length / 4];
    for (int i = 0; i < miniFat.length; i++) miniFat[i] = u32(raw, i * 4);
    return miniFat;
  }

  private static List<DirEntry> readDirectory(byte[] data, int sectorSize, long[] fat, long start)
      throws IOException {
    byte[] raw = readChain(data, sectorSize, fat, start, -1, -1, false);
    List<DirEntry> out = new ArrayList<>();
    for (int off = 0; off + 128 <= raw.length; off += 128) {
      int nameLength = u16(raw, off + 64);
      DirEntry e = new DirEntry();
      e.type = raw[off + 66] & 0xFF;
      e.name =
          nameLength >= 2
              ? new String(raw, off, nameLength - 2, java.nio.charset.StandardCharsets.UTF_16LE)
              : "";
      e.start = u32(raw, off + 116);
      e.size = u32(raw, off + 120);
      out.add(e);
    }
    return out;
  }

  /**
   * Follows a sector chain. When {@code miniStream} is set, {@code source} is the mini stream and
   * {@code table} the mini FAT; its sectors are packed back to back, so the file header shift that
   * applies to ordinary sectors must not be added.
   */
  private static byte[] readChain(
      byte[] source, int sectorSize, long[] table, long start, long size, long limit,
      boolean miniStream)
      throws IOException {
    java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
    long sector = start;
    long max = size >= 0 ? size : Long.MAX_VALUE;
    int guard = 0;
    while (isRegularSector(sector) && out.size() < max) {
      if (guard++ > table.length + 16) throw new IOException(".xls 的扇区链出现环");
      int base = miniStream ? (int) (sector * sectorSize) : sectorOffset(sector, sectorSize);
      requireRange(source, base, sectorSize);
      int take = (int) Math.min(sectorSize, max - out.size());
      out.write(source, base, take);
      if (sector >= table.length) break;
      sector = table[(int) sector];
    }
    byte[] raw = out.toByteArray();
    if (limit >= 0 && raw.length > limit) return Arrays.copyOf(raw, (int) limit);
    return raw;
  }

  private static final class DirEntry {
    String name = "";
    int type;
    long start;
    long size;
  }

  private static boolean isRegularSector(long sector) {
    return sector >= 0 && sector <= 0xFFFFFFFAL;
  }

  private static int sectorOffset(long sector, int sectorSize) {
    return (int) ((sector + 1) * sectorSize);
  }

  private static void requireRange(byte[] data, int offset, int length) throws IOException {
    if (offset < 0 || length < 0 || offset + length > data.length)
      throw new IOException(".xls 文件已损坏（扇区越界）");
  }

  // ---------------------------------------------------------------- BIFF records

  private static List<Sheets.Sheet> parseBiff(byte[] stream) throws IOException {
    List<String> names = new ArrayList<>();
    List<List<String[]>> grids = new ArrayList<>();
    List<List<int[]>> merges = new ArrayList<>();
    List<String> sst = new ArrayList<>();
    int sheet = -1;
    int bofs = 0;
    int pendingRow = -1, pendingCol = -1;
    boolean biff8 = true;

    for (int off = 0; off + 4 <= stream.length; ) {
      int id = u16(stream, off);
      int size = u16(stream, off + 2);
      int body = off + 4;
      if (body + size > stream.length) break;
      off = body + size;

      if (id == BOF) {
        bofs++;
        if (bofs == 1) {
          int version = u16(stream, body);
          biff8 = version == 0x0600;
        } else {
          sheet = bofs - 2;
          while (grids.size() <= sheet) {
            if (grids.size() >= MAX_SHEETS) throw new IOException("工作簿工作表过多，请只保留课表工作表");
            grids.add(new ArrayList<>());
            merges.add(new ArrayList<>());
          }
        }
        continue;
      }
      if (sheet < 0) {
        if (id == BOUNDSHEET) names.add(boundsheetName(stream, body, size));
        else if (id == SST) sst = readSst(gatherContinues(stream, body, size));
        continue;
      }

      switch (id) {
        case LABELSST:
          {
            int index = (int) u32(stream, body + 6);
            put(grids.get(sheet), u16(stream, body), u16(stream, body + 2), sstText(sst, index));
            break;
          }
        case LABEL:
        case RSTRING:
          put(grids.get(sheet), u16(stream, body), u16(stream, body + 2), unicodeString(stream, body + 6));
          break;
        case RK:
          {
            int rk = (int) u32(stream, body + 6);
            put(grids.get(sheet), u16(stream, body), u16(stream, body + 2), formatNumber(rkValue(rk)));
            break;
          }
        case NUMBER:
          put(grids.get(sheet), u16(stream, body), u16(stream, body + 2), formatNumber(doubleAt(stream, body + 6)));
          break;
        case MULRK:
          {
            int row = u16(stream, body);
            int first = u16(stream, body + 2);
            int count = (size - 6) / 6;
            for (int i = 0; i < count; i++)
              put(grids.get(sheet), row, first + i, formatNumber(rkValue((int) u32(stream, body + 4 + i * 6 + 2))));
            break;
          }
        case BLANK:
        case MULBLANK:
          // Deliberately ignored: blanks only exist to keep the grid aligned, and empty cells are
          // omitted from the prompt anyway.
          break;
        case FORMULA:
          {
            int row = u16(stream, body), col = u16(stream, body + 2);
            // A string result is flagged by bytes 12-13 of the payload (the tail of the 8-byte
            // cached result) being 0xFFFF with a 0x00 marker at byte 6; the text then arrives in
            // the STRING record that follows. This is the same test xlrd applies in sheet.py.
            if (u16(stream, body + 12) == 0xFFFF && (stream[body + 6] & 0xFF) == 0) {
              pendingRow = row;
              pendingCol = col;
            } else {
              put(grids.get(sheet), row, col, formatNumber(doubleAt(stream, body + 6)));
            }
            break;
          }
        case STRING:
          if (pendingRow >= 0) {
            put(grids.get(sheet), pendingRow, pendingCol, unicodeString(stream, body));
            pendingRow = pendingCol = -1;
          }
          break;
        case MERGEDCELLS:
          {
            int count = u16(stream, body);
            List<int[]> list = merges.get(sheet);
            for (int i = 0; i < count; i++) {
              int o = body + 2 + i * 8;
              if (o + 8 > stream.length) break;
              // BIFF stores inclusive bounds; the prompt contract (and xlrd) uses exclusive ends.
              list.add(
                  new int[] {
                    u16(stream, o), u16(stream, o + 2) + 1, u16(stream, o + 4), u16(stream, o + 6) + 1
                  });
            }
            break;
          }
        default:
          break;
      }
    }

    if (!biff8) throw new IOException("这个 .xls 是较旧的 BIFF 格式，暂时无法识别");
    if (grids.isEmpty()) throw new IOException("这个 .xls 里没有可用的工作表");

    long cells = 0;
    List<Sheets.Sheet> out = new ArrayList<>();
    for (int i = 0; i < grids.size(); i++) {
      List<String[]> rows = grids.get(i);
      int width = 0;
      for (String[] row : rows) width = Math.max(width, row.length);
      if (rows.size() > MAX_ROWS || width > MAX_COLS)
        throw new IOException("工作表过大，请只保留课表工作表");
      cells += (long) rows.size() * width;
      if (cells > MAX_CELLS) throw new IOException("表格内容过多，请只保留课表工作表");
      out.add(new Sheets.Sheet(i < names.size() ? names.get(i) : "Sheet" + (i + 1), rows, merges.get(i)));
    }
    return out;
  }

  /**
   * Concatenates a record with the CONTINUE records that follow it. BIFF splits records larger than
   * the 8224-byte limit, and SST is the one that matters here.
   */
  private static List<byte[]> gatherContinues(byte[] stream, int body, int size) {
    List<byte[]> blocks = new ArrayList<>();
    blocks.add(Arrays.copyOfRange(stream, body, body + size));
    int cursor = body + size;
    while (cursor + 4 <= stream.length && u16(stream, cursor) == CONTINUE) {
      int next = u16(stream, cursor + 2);
      blocks.add(Arrays.copyOfRange(stream, cursor + 4, cursor + 4 + next));
      cursor += 4 + next;
    }
    return blocks;
  }

  /**
   * Decodes the shared string table. Each string carries an option byte, and when character data is
   * split across a CONTINUE boundary the continuation restarts with a fresh option byte.
   */
  private static List<String> readSst(List<byte[]> blocks) {
    List<String> out = new ArrayList<>();
    BlockCursor c = new BlockCursor(blocks);
    if (c.remaining() < 8) return out;
    c.u32();
    long unique = c.u32();
    for (long i = 0; i < unique && !c.atEnd(); i++) {
      int cch = c.u16();
      int grbit = c.u8();
      boolean high = (grbit & 0x01) != 0;
      boolean ext = (grbit & 0x04) != 0;
      boolean rich = (grbit & 0x08) != 0;
      int runs = rich ? c.u16() : 0;
      int extSize = ext ? (int) c.u32() : 0;
      StringBuilder text = new StringBuilder(cch);
      int left = cch;
      while (left > 0) {
        // Character data resuming in a later CONTINUE block is preceded by a fresh option byte,
        // which may also flip the encoding mid-string. Ask the cursor *after* it has skipped any
        // exhausted block, or the option byte would be swallowed as a character.
        if (c.atContinuationStart()) high = (c.u8() & 0x01) != 0;
        int take = Math.min(left, c.charsLeft(high));
        if (take <= 0) break;
        for (int j = 0; j < take; j++) text.append(high ? (char) c.u16() : (char) c.u8());
        left -= take;
      }
      c.skip((long) runs * 4 + extSize);
      out.add(text.toString());
    }
    return out;
  }

  /** Wraps the SST block list so reads can straddle CONTINUE boundaries. */
  private static final class BlockCursor {
    private final List<byte[]> blocks;
    private int block;
    private int pos;

    BlockCursor(List<byte[]> blocks) {
      this.blocks = blocks;
    }

    /** True when the cursor sits at the start of a CONTINUE block, i.e. after a block boundary. */
    boolean atContinuationStart() {
      atEnd();
      return block > 0 && pos == 0;
    }

    boolean atEnd() {
      while (block < blocks.size() && pos >= blocks.get(block).length) {
        block++;
        pos = 0;
      }
      return block >= blocks.size();
    }

    int remaining() {
      return atEnd() ? 0 : blocks.get(block).length - pos;
    }

    /** Characters still available in the current block, honouring the active encoding. */
    int charsLeft(boolean high) {
      int bytes = remaining();
      return high ? bytes / 2 : bytes;
    }

    int u8() {
      if (atEnd()) return 0;
      return blocks.get(block)[pos++] & 0xFF;
    }

    int u16() {
      int lo = u8(), hi = u8();
      return lo | (hi << 8);
    }

    long u32() {
      return (u16() & 0xFFFFL) | ((long) u16() << 16);
    }

    void skip(long count) {
      long left = count;
      while (left > 0 && !atEnd()) {
        int take = (int) Math.min(left, remaining());
        pos += take;
        left -= take;
      }
    }
  }

  private static String boundsheetName(byte[] stream, int body, int size) {
    if (size < 8) return "";
    int cch = stream[body + 6] & 0xFF;
    boolean high = (stream[body + 7] & 0x01) != 0;
    int offset = body + 8;
    StringBuilder name = new StringBuilder(cch);
    for (int i = 0; i < cch; i++) {
      if (high) {
        if (offset + 2 > stream.length) break;
        name.append((char) u16(stream, offset));
        offset += 2;
      } else {
        if (offset + 1 > stream.length) break;
        name.append((char) (stream[offset++] & 0xFF));
      }
    }
    return name.toString();
  }

  private static String sstText(List<String> sst, int index) {
    return index >= 0 && index < sst.size() ? sst.get(index) : "";
  }

  /** Reads a BIFF8 unicode string at {@code offset} (2-byte length, option byte, then characters). */
  private static String unicodeString(byte[] data, int offset) {
    if (offset + 3 > data.length) return "";
    int cch = u16(data, offset);
    int grbit = data[offset + 2] & 0xFF;
    boolean high = (grbit & 0x01) != 0;
    boolean ext = (grbit & 0x04) != 0;
    boolean rich = (grbit & 0x08) != 0;
    int cursor = offset + 3;
    if (rich) cursor += 2;
    if (ext) cursor += 4;
    StringBuilder text = new StringBuilder(cch);
    for (int i = 0; i < cch && cursor < data.length; i++) {
      if (high) {
        if (cursor + 2 > data.length) break;
        text.append((char) u16(data, cursor));
        cursor += 2;
      } else {
        text.append((char) (data[cursor++] & 0xFF));
      }
    }
    return text.toString();
  }

  private static void put(List<String[]> rows, int row, int col, String value) {
    if (row < 0 || row >= MAX_ROWS || col < 0 || col >= MAX_COLS || value.isEmpty()) return;
    while (rows.size() <= row) rows.add(new String[0]);
    String[] line = rows.get(row);
    if (line.length <= col) {
      line = Arrays.copyOf(line, col + 1);
      rows.set(row, line);
    }
    line[col] = value;
  }

  private static double rkValue(int rk) {
    boolean div100 = (rk & 0x01) != 0;
    double value;
    if ((rk & 0x02) != 0) {
      value = rk >> 2;
    } else {
      value = Double.longBitsToDouble(((long) (rk & 0xFFFFFFFC)) << 32);
    }
    return div100 ? value / 100 : value;
  }

  /** Numbers are rare in timetables; render them without a trailing ".0" when they are integral. */
  private static String formatNumber(double value) {
    if (value == Math.rint(value) && Math.abs(value) < 1e15) return String.valueOf((long) value);
    return String.valueOf(value);
  }

  private static double doubleAt(byte[] b, int o) {
    return Double.longBitsToDouble(u32(b, o) | (u32(b, o + 4) << 32));
  }

  private static int u16(byte[] b, int o) {
    return (b[o] & 0xFF) | ((b[o + 1] & 0xFF) << 8);
  }

  private static long u32(byte[] b, int o) {
    return (b[o] & 0xFFL) | ((b[o + 1] & 0xFFL) << 8) | ((b[o + 2] & 0xFFL) << 16) | ((b[o + 3] & 0xFFL) << 24);
  }
}
