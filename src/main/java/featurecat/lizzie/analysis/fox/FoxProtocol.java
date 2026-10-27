package featurecat.lizzie.analysis.fox;

import java.nio.charset.StandardCharsets;

/** 野狐私有协议编解码。 包格式：$ABXXXX,ddd,...,ddd*hh\r\n 校验和：从 '$' 之后开始逐字节异或，取低 8 位。 */
public final class FoxProtocol {

  private FoxProtocol() {}

  /** 异或校验和，跳过开头的 '$' */
  public static int calcChecksum(byte[] data) {
    if (data == null || data.length == 0) return 0;
    int start = (data[0] == '$') ? 1 : 0;
    int chk = 0;
    for (int i = start; i < data.length; i++) {
      chk ^= (data[i] & 0xFF);
    }
    return chk & 0xFF;
  }

  /** 构建数据包：$COMMAND,item1,item2*hh\r\n */
  public static byte[] buildPacket(String command, String... items) {
    StringBuilder sb = new StringBuilder("$").append(command);
    if (items != null) {
      for (String it : items) {
        sb.append(',').append(it == null ? "" : it);
      }
    }
    byte[] raw = sb.toString().getBytes(StandardCharsets.UTF_8);
    int chk = calcChecksum(raw);
    String tail = String.format("*%02X\r\n", chk);
    byte[] tailBytes = tail.getBytes(StandardCharsets.US_ASCII);
    byte[] packet = new byte[raw.length + tailBytes.length];
    System.arraycopy(raw, 0, packet, 0, raw.length);
    System.arraycopy(tailBytes, 0, packet, raw.length, tailBytes.length);
    return packet;
  }

  /** 解析一行数据包，返回 {command, restJoinedByComma} 或 null */
  public static String[] parsePacket(String line) {
    if (line == null) return null;
    line = line.trim();
    if (!line.startsWith("$")) return null;
    int star = line.indexOf('*');
    if (star >= 0) line = line.substring(0, star);
    String body = line.substring(1);
    String[] parts = body.split(",", -1);
    if (parts.length == 0) return null;
    String command = parts[0];
    StringBuilder rest = new StringBuilder();
    for (int i = 1; i < parts.length; i++) {
      if (i > 1) rest.append(',');
      rest.append(parts[i]);
    }
    return new String[] {command, rest.toString()};
  }

  /** 把逗号分隔的 items 拆成数组（用于 FAMOVE/FARULE 等） */
  public static String[] splitItems(String rest) {
    if (rest == null || rest.isEmpty()) return new String[0];
    return rest.split(",", -1);
  }
}
