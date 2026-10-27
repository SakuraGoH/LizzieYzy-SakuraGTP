package featurecat.lizzie.analysis;

import com.jcraft.jsch.ChannelExec;
import com.jcraft.jsch.JSch;
import com.jcraft.jsch.Session;
import featurecat.lizzie.Lizzie;
import featurecat.lizzie.gui.LizzieFrame;
import featurecat.lizzie.util.Utils;
import java.io.*;
import org.json.JSONException;

public class SSHController {
  private Session session;
  private ChannelExec channel;
  private RemoteConnect newConnect;
  private Leelaz owner;

  public SSHController(Leelaz owner, String ip, String port) {
    this.owner = owner;
    this.newConnect = new RemoteConnect();
    this.newConnect.setIp(ip);
    this.newConnect.setPort(port);
  }

  public Boolean login(String command, String userName, String password) {
    return doLogin(command, userName, Utils.doDecrypt(password), null);
  }

  public Boolean loginByFileKey(String command, String userName, File keyFile) {
    return doLogin(command, userName, null, keyFile);
  }

  private Boolean doLogin(String command, String userName, String password, File keyFile) {
    try {
      JSch jsch = new JSch();
      if (keyFile != null && keyFile.exists()) {
        jsch.addIdentity(keyFile.getAbsolutePath());
      }

      session = jsch.getSession(userName, newConnect.getIp(), newConnect.getPort());
      if (password != null && !password.isEmpty()) {
        session.setPassword(password);
      }

      session.setConfig("StrictHostKeyChecking", "no");
      session.connect(5000);

      channel = (ChannelExec) session.openChannel("exec");
      // 必须加 2>&1，把 stderr 合并到 stdout，这样报错也能被读到
      channel.setCommand(command + " 2>&1");
      channel.setPty(false); // 禁用伪终端，杜绝提示符

      channel.connect(3000);

      return Boolean.TRUE;
    } catch (Exception e) {
      owner.isLoaded = false;
      e.printStackTrace();
      String err = e.getLocalizedMessage();
      try {
        owner.tryToDignostic(
            String.valueOf(Lizzie.resourceBundle.getString("SSHController.engineFailed"))
                + ": "
                + ((err == null)
                    ? Lizzie.resourceBundle.getString("Leelaz.engineStartNoExceptionMessage")
                    : err),
            true);
        LizzieFrame.openMoreEngineDialog();
      } catch (JSONException e1) {
        e1.printStackTrace();
      }
      close();
      return Boolean.FALSE;
    }
  }

  public void close() {
    if (channel != null) {
      try {
        channel.disconnect();
      } catch (Exception ignored) {
      }
      channel = null;
    }
    if (session != null) {
      try {
        session.disconnect();
      } catch (Exception ignored) {
      }
      session = null;
    }
  }

  public boolean isConnected() {
    return session != null && session.isConnected() && channel != null && channel.isConnected();
  }

  public InputStream getStdout() {
    try {
      // 用我们的 AnsiFilter 包装底层的流
      return new AnsiFilterInputStream(channel.getInputStream());
    } catch (Exception e) {
      return null;
    }
  }

  public InputStream getSterr() {
    return new ByteArrayInputStream(new byte[0]); // stderr 被 2>&1 合并了，这里不需要
  }

  public OutputStream getStdin() {
    try {
      return channel.getOutputStream();
    } catch (Exception e) {
      return null;
    }
  }

  /** SakuraGTP 级别的 ANSI 过滤器。 它不会卡死 readLine，并且精确剔除了所有的颜色控制字符。 */
  private static class AnsiFilterInputStream extends FilterInputStream {
    protected AnsiFilterInputStream(InputStream in) {
      super(in);
    }

    @Override
    public int read() throws IOException {
      int c = super.read();
      if (c == -1) return -1;

      // 如果读到转义字符 (ESC, 27)
      if (c == 27) {
        int next = super.read();
        if (next == '[') {
          // 进入 CSI 序列，一直读到字母（@ 到 ~ 之间）为止
          while ((next = super.read()) != -1) {
            if (next >= '@' && next <= '~') {
              break;
            }
          }
        } else if (next == ']') {
          // 进入 OSC 序列，一直读到 BEL(7) 或 ST(ESC \) 为止
          while ((next = super.read()) != -1) {
            if (next == 7) break;
            if (next == 27) {
              if (super.read() == '\\') break;
            }
          }
        }
        // 整个控制序列被丢弃，继续读下一个有效字符
        return read();
      }
      return c;
    }

    @Override
    public int read(byte[] b, int off, int len) throws IOException {
      int count = 0;
      while (count < len) {
        int c = read();
        if (c == -1) {
          return count == 0 ? -1 : count;
        }
        b[off + count] = (byte) c;
        count++;
        // 遇到换行符立刻返回，保证 BufferedReader.readLine() 畅通无阻
        if (c == '\n') {
          break;
        }
      }
      return count;
    }
  }
}
