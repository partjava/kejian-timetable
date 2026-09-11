import com.kejian.app.Sheets;
import com.kejian.app.XlsReader;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * Builds throwaway .xls bytes in memory and checks the reader against them.
 *
 * <p>Fixtures are synthesised here on purpose: real timetable exports carry personal data and the
 * release audit forbids shipping one, so no sample file may be added to the tree.
 */
public class XlsReaderTest {
  static int checks = 0;

  static void check(boolean ok, String message) {
    checks++;
    if (!ok) throw new AssertionError(message);
  }

  static void eq(Object actual, Object expected, String message) {
    checks++;
    if (!Objects.equals(actual, expected))
      throw new AssertionError(message + " — expected <" + expected + "> but was <" + actual + ">");
  }

  public static void main(String[] args) throws Exception {
    readsChineseCellsFromMiniStream();
    readsRegularFatStream();
    readsUtf16AndCompressedStrings();
    skipsRichTextAndPhoneticData();
    joinsStringsSplitAcrossContinue();
    mapsMergedRangesToExclusiveEnds();
    ignoresBlankPlaceholders();
    readsNumericRecords();
    readsLabelAndFormulaStrings();
    rejectsNonOle2Input();
    rejectsTooManySheets();
    dispatchesByExtension();
    rendersPromptPayload();

    System.out.println(checks + " xls reader checks passed");
  }

  // ------------------------------------------------------------------ cases

  static void readsChineseCellsFromMiniStream() throws Exception {
    // Small workbooks keep the Workbook stream in the mini stream, which a real export does not —
    // so this is the path a hand-made fixture is most likely to get wrong.
    Book book = new Book("Sheet0");
    int header = book.str("节次"), course = book.str("软件工程/(1-1节)1-3周/教1-105/张莉");
    book.labelsst(0, 0, header).labelsst(1, 1, course);

    List<Sheets.Sheet> sheets = XlsReader.read(ole2(book.stream()));
    eq(sheets.size(), 1, "one sheet");
    eq(sheets.get(0).name, "Sheet0", "sheet name");
    eq(cell(sheets.get(0), 0, 0), "节次", "header cell");
    eq(cell(sheets.get(0), 1, 1), "软件工程/(1-1节)1-3周/教1-105/张莉", "chinese course cell");
  }

  static void readsRegularFatStream() throws Exception {
    // Anything at or above the 4096-byte mini-stream cutoff lives in the regular FAT instead.
    Book book = new Book("Big");
    book.labelsst(0, 0, book.str("填充")).pad(5000);
    byte[] stream = book.stream();
    check(stream.length >= 4096, "fixture really is past the mini-stream cutoff");

    List<Sheets.Sheet> sheets = XlsReader.read(ole2(stream));
    eq(cell(sheets.get(0), 0, 0), "填充", "cell read from the regular FAT chain");
  }

  static void readsUtf16AndCompressedStrings() throws Exception {
    // Row 0 is written as compressed (Latin-1) text, row 1 as UTF-16, exercising both branches.
    Book book = new Book("S");
    book.labelsst(0, 0, book.str("ABC", false)).labelsst(1, 0, book.str("中文", true));

    List<Sheets.Sheet> sheets = XlsReader.read(ole2(book.stream()));
    eq(cell(sheets.get(0), 0, 0), "ABC", "compressed string");
    eq(cell(sheets.get(0), 1, 0), "中文", "utf-16 string");
  }

  static void skipsRichTextAndPhoneticData() throws Exception {
    Book book = new Book("S");
    book.sstRaw(richSst("带格式", 2, 4)).labelsst(0, 0, 0);

    List<Sheets.Sheet> sheets = XlsReader.read(ole2(book.stream()));
    eq(cell(sheets.get(0), 0, 0), "带格式", "rich text runs and phonetic data skipped");
  }

  static void joinsStringsSplitAcrossContinue() throws Exception {
    // Character data cut by a CONTINUE record: the continuation restarts with a fresh option byte,
    // which must not be consumed as a character.
    String text = "跨记录字符串测试";
    int split = 3;
    byte[] head = cat(u32(1), u32(1), u16(text.length()), u8(0x01), utf16(text.substring(0, split)));
    byte[] tail = cat(u8(0x01), utf16(text.substring(split)));

    Book book = new Book("S");
    book.sstRaw(head).cont(tail).labelsst(0, 0, 0);

    List<Sheets.Sheet> sheets = XlsReader.read(ole2(book.stream()));
    eq(cell(sheets.get(0), 0, 0), text, "string reassembled across a CONTINUE boundary");
  }

  static void mapsMergedRangesToExclusiveEnds() throws Exception {
    // BIFF stores inclusive bounds; the prompt contract uses exclusive ends.
    Book book = new Book("S");
    book.labelsst(0, 0, book.str("x")).merged(0, 0, 1, 2);

    List<Sheets.Sheet> sheets = XlsReader.read(ole2(book.stream()));
    List<int[]> merges = sheets.get(0).merges;
    eq(merges.size(), 1, "one merge range");
    check(Arrays.equals(merges.get(0), new int[] {0, 2, 0, 3}), "inclusive bounds became exclusive");
  }

  static void ignoresBlankPlaceholders() throws Exception {
    Book book = new Book("S");
    book.labelsst(0, 0, book.str("a")).blank(0, 5).blank(3, 0).mulblank(3, 1, 4);

    List<Sheets.Sheet> sheets = XlsReader.read(ole2(book.stream()));
    eq(sheets.get(0).rows.size(), 1, "blank rows contribute no cells");
  }

  static void readsNumericRecords() throws Exception {
    Book book = new Book("S");
    book.rk(0, 0, rkInt(7)) // integer stored in the RK mantissa
        .rk(0, 1, rkInt(150) | 0x01) // ...divided by 100
        .number(1, 0, 2.5)
        .mulrk(2, 0, rkInt(1), rkInt(2), rkInt(3));

    List<Sheets.Sheet> sheets = XlsReader.read(ole2(book.stream()));
    eq(cell(sheets.get(0), 0, 0), "7", "RK integer");
    eq(cell(sheets.get(0), 0, 1), "1.5", "RK divided by 100");
    eq(cell(sheets.get(0), 1, 0), "2.5", "NUMBER");
    eq(cell(sheets.get(0), 2, 0), "1", "MULRK first");
    eq(cell(sheets.get(0), 2, 2), "3", "MULRK last");
  }

  static void readsLabelAndFormulaStrings() throws Exception {
    Book book = new Book("S");
    book.label(0, 0, "直接标签").formulaString(1, 0, "公式结果").formulaNumber(2, 0, 42.0);

    List<Sheets.Sheet> sheets = XlsReader.read(ole2(book.stream()));
    eq(cell(sheets.get(0), 0, 0), "直接标签", "LABEL text");
    eq(cell(sheets.get(0), 1, 0), "公式结果", "FORMULA string result from the following STRING record");
    eq(cell(sheets.get(0), 2, 0), "42", "FORMULA numeric result");
  }

  static void rejectsNonOle2Input() {
    try {
      XlsReader.read("not an xls".getBytes(StandardCharsets.UTF_8));
      throw new AssertionError("accepted a file without an OLE2 header");
    } catch (Exception expected) {
      check(
          String.valueOf(expected.getMessage()).contains("OLE2"),
          "rejection explains the missing OLE2 header");
    }
  }

  static void rejectsTooManySheets() throws Exception {
    Book book = new Book("S");
    book.labelsst(0, 0, book.str("a"));
    for (int i = 0; i < 20; i++) book.extraSheet();
    try {
      XlsReader.read(ole2(book.stream()));
      throw new AssertionError("accepted a workbook with more sheets than the cap allows");
    } catch (Exception expected) {
      check(
          String.valueOf(expected.getMessage()).contains("工作表"),
          "rejection names the sheet limit");
    }
  }

  static void dispatchesByExtension() throws Exception {
    // .csv is handled by the same entry point, so the importer only ever calls one method.
    byte[] csv = "节次,星期一\n一,高等数学\n".getBytes(StandardCharsets.UTF_8);
    List<Sheets.Sheet> sheets = Sheets.read("课表.csv", csv);
    eq(cell(sheets.get(0), 1, 1), "高等数学", "csv cell");

    try {
      Sheets.read("课表.xlsx", new byte[] {'P', 'K', 3, 4});
      throw new AssertionError("accepted a file the reader cannot handle");
    } catch (Exception expected) {
      check(
          String.valueOf(expected.getMessage()).contains("xlsx"),
          "xlsx is rejected with an instruction rather than a parse error");
    }
  }

  static void rendersPromptPayload() throws Exception {
    Sheets.Sheet sheet =
        new Sheets.Sheet(
            "S",
            Arrays.asList(new String[] {"节次", ""}, new String[] {"", "课程\"引号\""}),
            Collections.singletonList(new int[] {0, 1, 0, 2}));
    String json = Sheets.toPromptJson(Collections.singletonList(sheet));
    check(
        json.startsWith("{\"untrustedWorksheetData\":[{\"name\":\"S\",\"merges\":[[0,1,0,2]]"),
        "payload keeps the documented shape");
    check(json.contains("{\"row\":1,\"column\":1,\"text\":\"节次\"}"), "1-based row/column");
    check(json.contains("\"课程\\\"引号\\\"\""), "quotes escaped");
    check(!json.contains("\"text\":\"\""), "empty cells omitted");
    check(json.contains("课程"), "non-ASCII is not escaped");
  }

  // ------------------------------------------------------------------ helpers

  static String cell(Sheets.Sheet sheet, int row, int col) {
    if (row >= sheet.rows.size()) return null;
    String[] cells = sheet.rows.get(row);
    return col < cells.length ? cells[col] : null;
  }

  /** Builds BIFF8 records for one-sheet workbook, then wraps them for the OLE2 container. */
  static final class Book {
    final String name;
    final List<Object[]> strings = new ArrayList<>();
    final List<byte[]> sheetRecords = new ArrayList<>();
    byte[] sstPayload;
    byte[] continuePayload;
    int padding;

    Book(String name) {
      this.name = name;
    }

    /** Registers a shared string and returns its SST index. */
    int str(String text, boolean utf16) {
      strings.add(new Object[] {text, utf16});
      return strings.size() - 1;
    }

    int str(String text) {
      return str(text, true);
    }

    Book labelsst(int row, int col, int index) {
      return raw(0x00FD, cat(u16(row), u16(col), u16(0), u32(index)));
    }

    Book label(int row, int col, String text) {
      return raw(0x0204, cat(u16(row), u16(col), u16(0), unicodeString(text)));
    }

    Book rk(int row, int col, int bits) {
      return raw(0x027E, cat(u16(row), u16(col), u16(0), u32(bits & 0xFFFFFFFFL)));
    }

    Book number(int row, int col, double value) {
      return raw(0x0203, cat(u16(row), u16(col), u16(0), f64(value)));
    }

    Book mulrk(int row, int firstCol, int... bits) {
      byte[] body = new byte[4 + bits.length * 6 + 2];
      put16(body, 0, row);
      put16(body, 2, firstCol);
      for (int i = 0; i < bits.length; i++) {
        put16(body, 4 + i * 6, 0);
        put32(body, 6 + i * 6, bits[i] & 0xFFFFFFFFL);
      }
      put16(body, 4 + bits.length * 6, firstCol + bits.length - 1);
      return raw(0x00BD, body);
    }

    Book blank(int row, int col) {
      return raw(0x0201, cat(u16(row), u16(col), u16(0)));
    }

    Book mulblank(int row, int firstCol, int count) {
      byte[] body = new byte[4 + count * 2 + 2];
      put16(body, 0, row);
      put16(body, 2, firstCol);
      put16(body, 4 + count * 2, firstCol + count - 1);
      return raw(0x00BE, body);
    }

    Book formulaString(int row, int col, String text) {
      raw(0x0006, formulaBody(row, col, stringResult()));
      return raw(0x0207, unicodeString(text));
    }

    Book formulaNumber(int row, int col, double value) {
      return raw(0x0006, formulaBody(row, col, f64(value)));
    }

    Book merged(int rowFirst, int colFirst, int rowLast, int colLast) {
      return raw(
          0x00E5, cat(u16(1), u16(rowFirst), u16(rowLast), u16(colFirst), u16(colLast)));
    }

    Book sstRaw(byte[] payload) {
      this.sstPayload = payload;
      return this;
    }

    Book cont(byte[] payload) {
      this.continuePayload = payload;
      return this;
    }

    Book pad(int bytes) {
      this.padding = bytes;
      return this;
    }

    /** Starts an extra worksheet, so the sheet cap can be exercised. */
    Book extraSheet() {
      raw(0x0809, bof(0x0010));
      return this;
    }

    Book raw(int id, byte[] payload) {
      ByteArrayOutputStream out = new ByteArrayOutputStream();
      put16(out, id);
      put16(out, payload.length);
      out.write(payload, 0, payload.length);
      sheetRecords.add(out.toByteArray());
      return this;
    }

    byte[] stream() {
      ByteArrayOutputStream out = new ByteArrayOutputStream();
      record(out, 0x0809, bof(0x0005)); // globals
      record(out, 0x0085, boundsheet(name));
      record(out, 0x00FC, sstPayload != null ? sstPayload : encodeSst());
      if (continuePayload != null) record(out, 0x003C, continuePayload);
      record(out, 0x000A, new byte[0]);
      record(out, 0x0809, bof(0x0010)); // worksheet
      for (byte[] r : sheetRecords) out.write(r, 0, r.length);
      if (padding > 0) record(out, 0x00FF, new byte[padding]); // filler to pass the cutoff
      record(out, 0x000A, new byte[0]);
      return out.toByteArray();
    }

    byte[] encodeSst() {
      byte[] out = cat(u32(strings.size()), u32(strings.size()));
      for (Object[] entry : strings) {
        String text = (String) entry[0];
        boolean utf16 = Boolean.TRUE.equals(entry[1]);
        out = cat(out, u16(text.length()), u8(utf16 ? 0x01 : 0x00));
        for (int i = 0; i < text.length(); i++) {
          out = utf16 ? cat(out, u16(text.charAt(i))) : cat(out, u8(text.charAt(i)));
        }
      }
      return out;
    }
  }

  static byte[] formulaBody(int row, int col, byte[] result) {
    return cat(u16(row), u16(col), u16(0), result, u16(0), u32(0), u16(0));
  }

  /** The 8-byte cached result xlrd flags as "text follows in a STRING record". */
  static byte[] stringResult() {
    byte[] out = new byte[8];
    out[6] = (byte) 0xFF;
    out[7] = (byte) 0xFF;
    return out;
  }

  /** A string carrying rich-text runs and phonetic data, both of which must be skipped. */
  static byte[] richSst(String text, int runs, int extBytes) {
    return cat(
        u32(1),
        u32(1),
        u16(text.length()),
        u8(0x01 | 0x04 | 0x08), // fHighByte | fExtSt | fRichSt
        u16(runs),
        u32(extBytes),
        utf16(text),
        new byte[runs * 4 + extBytes]);
  }

  /** Packs an integer into RK form: bit 1 marks "the 30-bit mantissa is an integer", not a double. */
  static int rkInt(int value) {
    return (value << 2) | 0x02;
  }

  static byte[] bof(int type) {
    return cat(u16(0x0600), u16(type), u16(0x0DBB), u16(0x07CC), u32(0x41), u32(6));
  }

  static byte[] boundsheet(String name) {
    // lbPlyPos(4) + hidden state and sheet type(2) + cch(1) + name option byte(1) + characters.
    return cat(u32(0), u16(0), u8(name.length()), u8(0x01), utf16(name));
  }

  /** A BIFF8 unicode string: 2-byte character count, option byte, then the characters. */
  static byte[] unicodeString(String text) {
    return cat(u16(text.length()), u8(0x01), utf16(text));
  }

  static void record(ByteArrayOutputStream out, int id, byte[] payload) {
    put16(out, id);
    put16(out, payload.length);
    out.write(payload, 0, payload.length);
  }

  static byte[] utf16(String text) {
    byte[] out = new byte[text.length() * 2];
    for (int i = 0; i < text.length(); i++) put16(out, i * 2, text.charAt(i));
    return out;
  }

  static byte[] f64(double value) {
    long bits = Double.doubleToLongBits(value);
    return cat(u32(bits & 0xFFFFFFFFL), u32(bits >>> 32));
  }

  static byte[] cat(byte[]... parts) {
    int size = 0;
    for (byte[] part : parts) size += part.length;
    byte[] out = new byte[size];
    int at = 0;
    for (byte[] part : parts) {
      System.arraycopy(part, 0, out, at, part.length);
      at += part.length;
    }
    return out;
  }

  static byte[] u8(int v) {
    return new byte[] {(byte) v};
  }

  static byte[] u16(int v) {
    return new byte[] {(byte) v, (byte) (v >> 8)};
  }

  static byte[] u32(long v) {
    return new byte[] {(byte) v, (byte) (v >> 8), (byte) (v >> 16), (byte) (v >> 24)};
  }

  static void put16(ByteArrayOutputStream out, int v) {
    out.write(v & 0xFF);
    out.write((v >> 8) & 0xFF);
  }

  static void put16(byte[] out, int at, int v) {
    out[at] = (byte) (v & 0xFF);
    out[at + 1] = (byte) ((v >> 8) & 0xFF);
  }

  static void put32(byte[] out, int at, long v) {
    out[at] = (byte) (v & 0xFF);
    out[at + 1] = (byte) ((v >> 8) & 0xFF);
    out[at + 2] = (byte) ((v >> 16) & 0xFF);
    out[at + 3] = (byte) ((v >> 24) & 0xFF);
  }

  // ------------------------------------------------------------------ OLE2 container

  static final int SECTOR = 512;
  static final int MINI = 64;
  static final long END_OF_CHAIN = 0xFFFFFFFEL;
  static final long FREE = 0xFFFFFFFFL;
  static final long FAT_SECTOR = 0xFFFFFFFDL;

  /** Wraps a BIFF stream in a minimal but structurally valid OLE2 compound file. */
  static byte[] ole2(byte[] workbook) {
    boolean mini = workbook.length < 4096;
    byte[] miniStream = mini ? pad(workbook, MINI) : new byte[0];
    int miniSectors = mini ? miniStream.length / MINI : 0;
    int dataSectors = mini ? 0 : ceil(workbook.length, SECTOR);

    int fatAt = 0;
    int dirAt = 1;
    int miniFatAt = mini ? 2 : -1;
    int payloadAt = mini ? 3 : 2;
    int payloadCount = mini ? miniSectors : dataSectors;

    List<Long> fat = new ArrayList<>();
    for (int i = 0; i < 128; i++) fat.add(FREE);
    fat.set(fatAt, FAT_SECTOR);
    fat.set(dirAt, END_OF_CHAIN);
    if (mini) fat.set(miniFatAt, END_OF_CHAIN);
    for (int i = 0; i < payloadCount; i++) {
      fat.set(payloadAt + i, i == payloadCount - 1 ? END_OF_CHAIN : (long) (payloadAt + i + 1));
    }

    // Sector indices count from the first block after the 512-byte header, so the file is one
    // block longer than the number of sectors it holds.
    int sectors = mini ? 3 + payloadCount : 2 + payloadCount;
    byte[] out = new byte[(1 + sectors) * SECTOR];
    out[0] = (byte) 0xD0;
    out[1] = (byte) 0xCF;
    out[2] = 0x11;
    out[3] = (byte) 0xE0;
    out[4] = (byte) 0xA1;
    out[5] = (byte) 0xB1;
    out[6] = 0x1A;
    out[7] = (byte) 0xE1;
    put16(out, 24, 0x003E);
    put16(out, 26, 0x0003);
    put16(out, 28, 0xFFFE);
    put16(out, 30, 9);
    put16(out, 32, 6);
    put32(out, 44, 1);
    put32(out, 48, dirAt);
    put32(out, 56, 4096);
    put32(out, 60, mini ? miniFatAt : END_OF_CHAIN);
    put32(out, 64, mini ? 1 : 0);
    put32(out, 68, END_OF_CHAIN);
    put32(out, 72, 0);
    put32(out, 76, fatAt);
    for (int i = 1; i < 109; i++) put32(out, 76 + i * 4, FREE);

    for (int i = 0; i < 128; i++) put32(out, sectorAt(fatAt), fat.get(i));

    int dir = sectorAt(dirAt);
    writeDirEntry(
        out,
        dir,
        "Root Entry",
        5,
        mini ? payloadAt : END_OF_CHAIN,
        mini ? miniStream.length : 0);
    writeDirEntry(out, dir + 128, "Workbook", 2, mini ? 0 : payloadAt, workbook.length);

    if (mini) {
      for (int i = 0; i < miniSectors; i++)
        put32(out, sectorAt(miniFatAt) + i * 4, i == miniSectors - 1 ? END_OF_CHAIN : (long) (i + 1));
      System.arraycopy(miniStream, 0, out, sectorAt(payloadAt), miniStream.length);
    } else {
      System.arraycopy(workbook, 0, out, sectorAt(payloadAt), workbook.length);
    }
    return out;
  }

  static void writeDirEntry(byte[] out, int at, String name, int type, long start, long size) {
    byte[] utf16 = (name + "\0").getBytes(StandardCharsets.UTF_16LE);
    System.arraycopy(utf16, 0, out, at, utf16.length);
    put16(out, at + 64, utf16.length);
    out[at + 66] = (byte) type;
    out[at + 67] = 1;
    put32(out, at + 68, FREE);
    put32(out, at + 72, FREE);
    put32(out, at + 76, FREE);
    put32(out, at + 116, start);
    put32(out, at + 120, size);
  }

  static int sectorAt(int sector) {
    return (sector + 1) * SECTOR;
  }

  static int ceil(int value, int unit) {
    return (value + unit - 1) / unit;
  }

  static byte[] pad(byte[] data, int unit) {
    int size = ceil(data.length, unit) * unit;
    byte[] out = new byte[size];
    System.arraycopy(data, 0, out, 0, data.length);
    return out;
  }
}
