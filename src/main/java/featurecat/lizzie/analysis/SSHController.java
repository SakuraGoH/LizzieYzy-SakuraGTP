package featurecat.lizzie.analysis;

import com.jcraft.jsch.ChannelShell;
import com.jcraft.jsch.JSch;
import com.jcraft.jsch.Session;
import featurecat.lizzie.Lizzie;
import featurecat.lizzie.gui.LizzieFrame;
import featurecat.lizzie.util.Utils;
import java.io.File;
import java.io.InputStream;
import java.io.OutputStream;
import org.json.JSONException;

public class SSHController {
  private Session session;
  private ChannelShell channel;
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

      // 使用 JSch 连接，完美支持 autodl 的域名和高位端口
      session = jsch.getSession(userName, newConnect.getIp(), newConnect.getPort());
      if (password != null && !password.isEmpty()) {
        session.setPassword(password);
      }

      // 跳过 host key 检查（与原版行为一致，且避免首次连接时的弹窗）
      session.setConfig("StrictHostKeyChecking", "no");
      // 设置超时时间
      session.connect(5000);

      // 使用交互式 Shell（SakuraGTP 风格），比 exec 更稳定，支持复杂命令
      channel = (ChannelShell) session.openChannel("shell");
      // 关键1：不申请伪终端（pty），避免 autodl 输出带颜色控制符的乱码和登录欢迎界面
      channel.setPty(false);

      // 将 stderr 合并到 stdout，防止报错信息导致缓冲区阻塞
      channel.setExtOutputStream(null, true);

      // 初始化 I/O 流
      InputStream in = channel.getInputStream();
      OutputStream out = channel.getOutputStream();

      channel.connect(3000);

      // 关键2：先发个 clear 把可能残留的启动信息刷掉
      out.write("clear\n".getBytes());
      out.flush();

      // 发送用户的启动命令
      if (command != null && !command.trim().isEmpty()) {
        out.write((command + "\n").getBytes());
        out.flush();
      }

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

  public InputStream getStdout() {
    try {
      return channel.getInputStream();
    } catch (Exception e) {
      return null;
    }
  }

  public InputStream getSterr() {
    // JSch 的 Shell 模式没有独立 stderr，合并到 stdout 了，这里返回空流防止 Leelaz 崩溃
    return new java.io.ByteArrayInputStream(new byte[0]);
  }

  public OutputStream getStdin() {
    try {
      return channel.getOutputStream();
    } catch (Exception e) {
      return null;
    }
  }

  public boolean isConnected() {
    return session != null && session.isConnected() && channel != null && channel.isConnected();
  }
}
