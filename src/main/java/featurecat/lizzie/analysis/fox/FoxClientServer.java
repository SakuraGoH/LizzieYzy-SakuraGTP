package featurecat.lizzie.analysis.fox;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;

/** 监听野狐客户端 TCP 连接，按行切包后交给回调。 */
public class FoxClientServer {

  public interface Listener {
    void onCommand(String command, String[] items);

    void onLog(String msg);

    void onReconnect();
  }

  private final Listener listener;
  private ServerSocket serverSocket;
  private Socket clientSocket;
  private OutputStream clientOut;
  private final AtomicBoolean running = new AtomicBoolean(false);
  private Thread acceptThread;
  private Thread recvThread;
  private final Object sendLock = new Object();

  private int port = 6000;
  private volatile boolean autoReconnect = true;
  private volatile int reconnectInterval = 5;

  public FoxClientServer(Listener listener) {
    this.listener = listener;
  }

  public synchronized boolean start(int port) {
    if (running.get()) return false;
    this.port = port;
    try {
      serverSocket = new ServerSocket();
      serverSocket.setReuseAddress(true);
      serverSocket.bind(new InetSocketAddress("0.0.0.0", port));
      running.set(true);
      acceptThread = new Thread(this::acceptLoop, "FoxAccept");
      acceptThread.setDaemon(true);
      acceptThread.start();
      log("开始监听野狐客户端端口 " + port);
      return true;
    } catch (Exception e) {
      running.set(false);
      log("监听失败: " + e.getMessage());
      return false;
    }
  }

  private void acceptLoop() {
    while (running.get()) {
      try {
        serverSocket.setSoTimeout(1000);
        Socket conn = serverSocket.accept();
        clientSocket = conn;
        clientOut = conn.getOutputStream();
        log("野狐客户端已连接: " + conn.getRemoteSocketAddress());
        recvThread = new Thread(() -> recvLoop(conn), "FoxRecv");
        recvThread.setDaemon(true);
        recvThread.start();
      } catch (java.net.SocketTimeoutException te) {
        // 继续等
      } catch (Exception e) {
        if (running.get()) log("accept 异常: " + e.getMessage());
        break;
      }
    }
  }

  private void recvLoop(Socket conn) {
    StringBuilder buffer = new StringBuilder();
    byte[] buf = new byte[4096];
    try (InputStream in = conn.getInputStream()) {
      int n;
      while (running.get() && (n = in.read(buf)) != -1) {
        buffer.append(new String(buf, 0, n, StandardCharsets.UTF_8));
        int idx;
        while ((idx = buffer.indexOf("\n")) >= 0) {
          String line = buffer.substring(0, idx).trim();
          buffer.delete(0, idx + 1);
          if (line.isEmpty()) continue;
          log("野狐: " + line);
          String[] parsed = FoxProtocol.parsePacket(line);
          if (parsed != null) {
            String[] items = FoxProtocol.splitItems(parsed[1]);
            listener.onCommand(parsed[0], items);
          }
        }
      }
    } catch (Exception e) {
      log("接收异常: " + e.getMessage());
    }
    log("野狐客户端断开");
    synchronized (sendLock) {
      clientSocket = null;
      clientOut = null;
    }
    if (autoReconnect && running.get()) {
      new Thread(this::reconnectLoop, "FoxReconnect").start();
    }
  }

  private void reconnectLoop() {
    log(reconnectInterval + " 秒后自动重连...");
    try {
      Thread.sleep(reconnectInterval * 1000L);
    } catch (InterruptedException ignored) {
    }
    if (!running.get()) return;
    log("等待野狐客户端重新连接");
    listener.onReconnect();
  }

  public void setAutoReconnect(boolean enabled, int interval) {
    this.autoReconnect = enabled;
    this.reconnectInterval = Math.max(1, interval);
  }

  public boolean send(String command, String... items) {
    synchronized (sendLock) {
      if (clientOut == null) return false;
      try {
        byte[] packet = FoxProtocol.buildPacket(command, items);
        clientOut.write(packet);
        clientOut.flush();
        log("发送: " + new String(packet, StandardCharsets.UTF_8).trim());
        return true;
      } catch (Exception e) {
        log("发送失败: " + e.getMessage());
        return false;
      }
    }
  }

  public synchronized void stop() {
    running.set(false);
    autoReconnect = false;
    synchronized (sendLock) {
      if (clientSocket != null) {
        try {
          clientSocket.close();
        } catch (Exception ignored) {
        }
        clientSocket = null;
        clientOut = null;
      }
    }
    if (serverSocket != null) {
      try {
        serverSocket.close();
      } catch (Exception ignored) {
      }
      serverSocket = null;
    }
  }

  public boolean isListening() {
    return running.get() && serverSocket != null;
  }

  public boolean isConnected() {
    synchronized (sendLock) {
      return clientSocket != null;
    }
  }

  private void log(String msg) {
    if (listener != null) listener.onLog(msg);
  }
}
