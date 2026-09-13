import com.kejian.app.Sheets;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

public class CsvLimitsTest {
  private static int checks;
  private static void check(boolean ok, String message) {
    checks++;
    if (!ok) throw new AssertionError(message);
  }
  private static void rejects(String text, String reason) throws Exception {
    try {
      Sheets.read("limit.csv", text.getBytes(StandardCharsets.UTF_8));
      throw new AssertionError("Expected rejection: " + reason);
    } catch (IOException expected) {
      check(expected.getMessage().contains(reason), "Explain " + reason);
    }
  }
  public static void main(String[] args) throws Exception {
    rejects("row\n".repeat(2000) + "last", "2000");
    rejects("x,".repeat(100) + "last", "100");
    var rows = Sheets.read("exact.csv", "row\r\n".repeat(2000).getBytes(StandardCharsets.UTF_8));
    check(rows.get(0).rows.size() == 2000, "Trailing newline must not add a row");
    var cols = Sheets.read("exact.csv", ("x,".repeat(99) + "last").getBytes(StandardCharsets.UTF_8));
    check(cols.get(0).rows.get(0).length == 100, "100 columns accepted");
    var quoted = Sheets.read("quoted.csv", "\"a\nb\",\"say \"\"hi\"\"\"\r\n".getBytes(StandardCharsets.UTF_8));
    check(quoted.get(0).rows.size() == 1, "Quoted newline is not a new record");
    check(quoted.get(0).rows.get(0)[0].equals("a\nb"), "Quoted newline retained");
    check(quoted.get(0).rows.get(0)[1].equals("say \"hi\""), "Escaped quote retained");
    System.out.println(checks + " CSV limit checks passed");
  }
}
