package featurecat.lizzie.analysis.fox;

import java.util.*;
import java.util.concurrent.locks.ReentrantLock;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 野狐命令分发 + GTP 桥接。
 *
 * <p>纯 genmove 模式：AI 回合只走 genmove，等 KataGo 输出 "play <vertex>" 落子； 不解析 info
 * move，不展示候选点，不算吻合度，不写任何分析注释。
 */
public class FoxBridge {

  /** LizzieYzy 宿主接口，由 LizzieFoxHost 实现 */
  public interface LizzieHost {
    boolean isEngineRunning();

    void sendGtp(String command);

    void onBoardUpdated();

    void onAiTurn(String color);

    void onCandidates(List<Candidate> cands);

    void onGameEnd();

    void log(String msg);

    void onInputLockChanged(boolean locked);

    void onRuleChanged(int boardSize, double komi, List<String> handicap);

    void onReplayMoves(Map<Integer, String[]> foxMoves);

    void onFoxMove(int x, int y, String color);

    void onAiMove(int x, int y, String color);
  }

  public static class Candidate {
    public String vertex;
    public int x, y;
    public long visits;
    public double winrate;
    public double score;
    public int order;
  }

  /** 兼容旧接口，纯 genmove 模式下永不触发。 */
  public interface MatchListener {
    void onOpponentMoveEvaluated(
        List<Candidate> candidates, String playedVertex, int order, double matchRatio);
  }

  // ---------- 正则常量 ----------
  private static final Pattern GTP_VERTEX = Pattern.compile("^([A-HJ-T])(\\d{1,2})$");

  private LizzieHost host;
  private final FoxClientServer fox;
  private final ReentrantLock lock = new ReentrantLock();

  public int boardSize = 19;
  public double komi = 6.5;
  public String aiColor = "B";
  public Map<Integer, String[]> foxMoves = new LinkedHashMap<>();
  public int lastMoveNumber = 0;

  // 纯 genmove 模式，固定 false
  public final boolean analyzeMode = false;

  // 分析相关参数保留字段但不再使用
  public double maxTime = -1;
  public int maxVisits = 0;
  public int maxPlayouts = 0;

  private boolean analyzing = false;
  private long analyzeStart = 0;
  private String analyzeColor = "B";

  // 候选点缓存（纯 genmove 模式下始终为空）
  private final Map<String, Candidate> candidates = new LinkedHashMap<>();
  private final Map<String, Candidate> currentCandidates = new LinkedHashMap<>();
  private volatile boolean candidatesDirty = false;

  private volatile boolean commitPending = false;

  // 是否在等 genmove 的 "play <vertex>" 应答
  private volatile boolean waitingGenmoveResult = false;

  private volatile boolean flushStarted = false;
  private volatile boolean timerStarted = false;

  private String pendingScoreOpponent = "0";

  private volatile double lastOpponentMatch = -1;
  private volatile int lastOpponentOrder = -1;
  private volatile String lastOpponentVertex = null;
  private volatile String lastAiVertex = null;

  private volatile Runnable onCandidatesChanged;
  private volatile MatchListener matchListener;

  public void setOnCandidatesChanged(Runnable r) {
    this.onCandidatesChanged = r;
  }

  public void setMatchListener(MatchListener l) {
    this.matchListener = l;
  }

  public FoxBridge(LizzieHost host, FoxClientServer fox) {
    this.host = host;
    this.fox = fox;
  }

  public void setHost(LizzieHost host) {
    this.host = host;
    if (!flushStarted) {
      flushStarted = true;
      startFlushThread();
    }
    if (!timerStarted) {
      timerStarted = true;
      startTimerThread();
    }
  }

  // ---------- 配置（兼容接口，纯 genmove 下无实际作用） ----------
  public void setAnalyzeMode(boolean enabled) {
    if (host != null) host.log("对弈模式: genmove 模式（分析模式已禁用）");
  }

  public boolean isAnalyzeMode() {
    return false;
  }

  public void setMaxTime(double t) {
    this.maxTime = t;
    if (host != null) host.log("maxTime = " + t + "（纯 genmove 模式，按 gtp.cfg）");
  }

  public void setMaxVisits(int v) {
    this.maxVisits = v;
    if (host != null) host.log("maxVisits = " + v + "（纯 genmove 模式，忽略）");
  }

  public void setMaxPlayouts(int p) {
    this.maxPlayouts = p;
    if (host != null) host.log("maxPlayouts = " + p + "（纯 genmove 模式，忽略）");
  }

  public void setRules(String rule) {
    if (host == null) return;
    if (!host.isEngineRunning()) {
      host.log("引擎未运行，无法切换规则");
      return;
    }
    sendGtp("kata-set-rules " + rule);
    host.log("已切换规则: " + rule);
  }

  // ---------- 野狐命令分发 ----------
  public void handleFoxCommand(String command, String[] items) {
    if (host == null) return;
    switch (command) {
      case "FARULE":
        handleFarule(items);
        break;
      case "FASTATUS":
        handleFastatus(items);
        break;
      case "FAMOVE":
        handleFamove(items);
        break;
      case "FASKIP":
        handleFaskip(items);
        break;
      case "FATIMELEFT":
        handleFatimeleft(items);
        break;
      case "FARESULT":
        handleFaresult(items);
        break;
      case "FASCORE":
        handleFascore(items);
        break;
      default:
        break;
    }
  }

  private void handleFarule(String[] items) {
    if (items.length < 5) return;
    boardSize = Integer.parseInt(items[0]);
    String mainTime = items[1];
    String byoTime = items[2];
    String byoCount = items[3];
    komi = Integer.parseInt(items[4]) / 100.0;
    List<String> handicap = new ArrayList<>();
    for (int i = 5; i < items.length; i++) {
      try {
        String[] p = items[i].split("\\^");
        String v = xyToGtp(Integer.parseInt(p[1]), Integer.parseInt(p[2]));
        if (v != null) handicap.add(v);
      } catch (Exception ignored) {
      }
    }

    host.onRuleChanged(boardSize, komi, handicap);

    if (host.isEngineRunning()) {
      sendGtp("time_settings " + mainTime + " " + byoTime + " " + byoCount);
    }

    foxMoves.clear();
    lastMoveNumber = 0;
    candidates.clear();
    currentCandidates.clear();
    candidatesDirty = false;
    commitPending = false;
    waitingGenmoveResult = false;
    analyzing = false;
    lastOpponentMatch = -1;
    lastOpponentOrder = -1;
    lastOpponentVertex = null;
    lastAiVertex = null;
    host.onCandidates(Collections.emptyList());
    host.onInputLockChanged(true);
    host.log("规则: 棋盘" + boardSize + ", 贴目" + komi + ", 让子" + handicap);
  }

  private void handleFastatus(String[] items) {
    if (items.length == 0 || "0".equals(items[0])) {
      host.log("非对弈状态");
      return;
    }
    aiColor = items.length > 1 ? items[1] : "B";
    foxMoves.clear();
    lastMoveNumber = 0;
    for (int i = 2; i < items.length; i++) {
      try {
        String[] p = items[i].split("\\^");
        int n = Integer.parseInt(p[0]);
        String vertex = xyToGtp(Integer.parseInt(p[1]), Integer.parseInt(p[2]));
        String color = "B".equals(p[3]) ? "B" : "W";
        foxMoves.put(n, new String[] {color, vertex});
        lastMoveNumber = n;
      } catch (Exception e) {
        host.log("重放落子解析失败: " + items[i] + " " + e.getMessage());
      }
    }
    host.onReplayMoves(foxMoves);
    host.onBoardUpdated();
    host.log("重连重放完成，已同步到第 " + lastMoveNumber + " 手");
  }

  private void handleFamove(String[] items) {
    if (items.length == 0) return;
    boolean isAiTurn = "1".equals(items[0]);
    if (items.length > 1) aiColor = items[1];

    int parsedN = -1;
    String parsedVertex = null;
    String parsedColor = null;
    if (items.length > 2 && !"0^0^0^N".equals(items[2])) {
      try {
        String[] p = items[2].split("\\^");
        parsedN = Integer.parseInt(p[0]);
        parsedVertex = xyToGtp(Integer.parseInt(p[1]), Integer.parseInt(p[2]));
        parsedColor = "B".equals(p[3]) ? "B" : "W";
      } catch (Exception e) {
        host.log("落子解析失败: " + items[2] + " " + e.getMessage());
      }
    }

    if (parsedN > 0 && parsedVertex != null && parsedColor != null) {
      String[] existed = foxMoves.get(parsedN);
      boolean alreadyPlaced =
          existed != null && existed[0].equals(parsedColor) && existed[1].equals(parsedVertex);

      foxMoves.put(parsedN, new String[] {parsedColor, parsedVertex});
      lastMoveNumber = Math.max(lastMoveNumber, parsedN);

      if (alreadyPlaced) {
        host.log(
            "野狐重复落子已跳过: "
                + parsedColor
                + " "
                + parsedVertex
                + "（第 "
                + parsedN
                + " 手） isAiTurn="
                + isAiTurn);
      } else {
        int[] xy = gtpToXy(parsedVertex);
        if (xy != null) {
          host.onFoxMove(xy[0], xy[1], parsedColor);
        }
        host.log(
            "野狐确认落子: "
                + parsedColor
                + " "
                + parsedVertex
                + "（第 "
                + parsedN
                + " 手） isAiTurn="
                + isAiTurn);
      }

      commitPending = false;
    }

    host.onBoardUpdated();

    if (isAiTurn) {
      host.onInputLockChanged(true);
      try {
        Thread.sleep(30);
      } catch (InterruptedException ignored) {
      }
      startThinking();
    }
    // 对手回合：纯 genmove 模式，不做任何分析，只等野狐下一手
  }

  private void handleFaskip(String[] items) {
    if (items.length == 0) return;
    boolean isAiTurn = "1".equals(items[0]);
    if (items.length > 1) aiColor = items[1];
    if (isAiTurn) startThinking();
  }

  private void handleFatimeleft(String[] items) {
    sendGtp("time_left " + aiColor + " 999999 0");
  }

  private void handleFaresult(String[] items) {
    host.log("对局结果: " + Arrays.toString(items));
    waitingGenmoveResult = false;
    commitPending = false;
    foxMoves.clear();
    lastMoveNumber = 0;
    candidates.clear();
    currentCandidates.clear();
    candidatesDirty = false;
    lastOpponentMatch = -1;
    lastOpponentOrder = -1;
    lastOpponentVertex = null;
    lastAiVertex = null;
    host.onCandidates(Collections.emptyList());
    host.onInputLockChanged(false);
    host.onGameEnd();
  }

  private void handleFascore(String[] items) {
    pendingScoreOpponent = items.length > 0 ? items[0] : "0";
    sendGtp("final_score");
  }

  /** AI 回合：只走 genmove，等 "play <vertex>" 决定落子。 */
  private void startThinking() {
    candidates.clear();
    candidatesDirty = false;
    commitPending = false;
    analyzeColor = aiColor;
    waitingGenmoveResult = true;
    analyzing = false;
    host.log("请求 genmove " + aiColor);
    host.onAiTurn(aiColor);
  }

  // ---------- 定时器（纯 genmove 下不参与落子决策） ----------
  private void startTimerThread() {
    Thread t =
        new Thread(
            () -> {
              while (true) {
                try {
                  Thread.sleep(200);
                } catch (InterruptedException ignored) {
                }
                // 纯 genmove 模式：不做分析计时，不自动落子
              }
            },
            "FoxTimer");
    t.setDaemon(true);
    t.start();
  }

  private void startFlushThread() {
    Thread t =
        new Thread(
            () -> {
              while (true) {
                try {
                  Thread.sleep(100);
                } catch (InterruptedException ignored) {
                }
                // 纯 genmove 模式：不推送候选点
              }
            },
            "FoxFlush");
    t.setDaemon(true);
    t.start();
  }

  // ---------- GTP 输出处理 ----------
  public void onEngineOutput(String line) {
    if (line == null || host == null) return;
    String stripped = line.trim();
    if (stripped.isEmpty()) return;

    if (stripped.startsWith("?")) {
      host.log("引擎报错: " + stripped);
      return;
    }

    // genmove 落子在 "play <vertex>" 行
    if (waitingGenmoveResult && stripped.startsWith("play")) {
      String[] params = stripped.split("\\s+");
      if (params.length >= 2) {
        String vertex = params[1].trim();
        waitingGenmoveResult = false;
        sendMoveToFox(vertex);
        return;
      }
    }

    if (stripped.startsWith("=")) {
      String body = stripped.substring(1).trim();
      int sp = body.indexOf(' ');
      if (sp > 0 && body.substring(0, sp).chars().allMatch(Character::isDigit)) {
        body = body.substring(sp + 1).trim();
      }
      if (isFinalScoreResult(body)) {
        sendScoreToFox(body);
        return;
      }
    }

    // genmove 期间的 info 行：保留给 Leelaz 自己解析（胜率曲线依赖它），
    // 但 FoxBridge 不再把它当候选点推给 UI。
    if (stripped.startsWith("info")) {
      return;
    }
  }

  private boolean isFinalScoreResult(String body) {
    if (body.isEmpty()) return false;
    if (body.equals("0") || body.equalsIgnoreCase("draw")) return true;
    return body.length() >= 3 && "WBwb".indexOf(body.charAt(0)) >= 0 && body.charAt(1) == '+';
  }

  private void sendMoveToFox(String move) {
    if (host == null) return;
    move = move == null ? "" : move.trim();
    if (move.isEmpty()) {
      host.log("genmove 返回空，忽略");
      return;
    }
    if ("resign".equalsIgnoreCase(move)) {
      fox.send("AFGIVEUP", aiColor);
      host.log("发送认输: AFGIVEUP " + aiColor);
      lastAiVertex = "resign";
      commitPending = false;
      analyzing = false;
      return;
    }
    if ("pass".equalsIgnoreCase(move)) {
      fox.send("AFSKIP", aiColor);
      host.log("发送停一手: AFSKIP " + aiColor);
      lastMoveNumber++;
      foxMoves.put(lastMoveNumber, new String[] {aiColor, "pass"});
      lastAiVertex = "pass";
      candidates.clear();
      candidatesDirty = false;
      commitPending = false;
      analyzing = false;
      host.onCandidates(Collections.emptyList());
      return;
    }
    int[] xy = gtpToXy(move);
    if (xy == null) {
      host.log("无法解析落子: " + move);
      return;
    }
    lastMoveNumber++;
    String moveStr = lastMoveNumber + "^" + xy[0] + "^" + xy[1] + "^" + aiColor;
    fox.send("AFPLAY", aiColor, moveStr);
    host.log("发送落子: " + move + " -> AFPLAY " + aiColor + " " + moveStr);
    foxMoves.put(lastMoveNumber, new String[] {aiColor, xyToGtp(xy[0], xy[1])});
    lastAiVertex = move;
    candidates.clear();
    candidatesDirty = false;
    waitingGenmoveResult = false;
    commitPending = false;
    analyzing = false;
    host.onCandidates(Collections.emptyList());
  }

  private void sendScoreToFox(String score) {
    score = score == null ? "" : score.trim();
    String result;
    if (score.equals("0") || "draw".equalsIgnoreCase(score)) {
      result = "0";
    } else if (score.length() >= 3
        && "WB".indexOf(Character.toUpperCase(score.charAt(0))) >= 0
        && score.charAt(1) == '+') {
      char color = Character.toUpperCase(score.charAt(0));
      String rest = score.substring(2).trim();
      if ("R".equalsIgnoreCase(rest) || "RESIGN".equalsIgnoreCase(rest)) {
        result = color + "+R";
      } else {
        try {
          result = color + "+" + (int) (Double.parseDouble(rest) * 100);
        } catch (NumberFormatException e) {
          result = score;
        }
      }
    } else {
      result = score;
    }
    fox.send("AFSCORE", pendingScoreOpponent, result);
    host.log("发送分数: AFSCORE " + pendingScoreOpponent + " " + result);
  }

  // ---------- 坐标 ----------
  private static final String LETTERS = "ABCDEFGHJKLMNOPQRST";

  public String xyToGtp(int x, int y) {
    if (x >= 0 && x < LETTERS.length() && y >= 0 && y < 19) {
      return "" + LETTERS.charAt(x) + (19 - y);
    }
    return "pass";
  }

  public int[] gtpToXy(String vertex) {
    if (vertex == null) return null;
    vertex = vertex.toUpperCase();
    if ("PASS".equals(vertex)) return null;
    Matcher m = GTP_VERTEX.matcher(vertex);
    if (!m.matches()) return null;
    int x = LETTERS.indexOf(m.group(1).charAt(0));
    int y = 19 - Integer.parseInt(m.group(2));
    if (x < 0 || y < 0 || y >= 19) return null;
    return new int[] {x, y};
  }

  private void sendGtp(String cmd) {
    if (host == null) return;
    if (!host.isEngineRunning()) {
      host.log("引擎未运行，跳过 GTP: " + cmd);
      return;
    }
    host.sendGtp(cmd);
    host.log("GTP发送: " + cmd);
  }

  // ---------- UI 读取用（纯 genmove 下无意义，保留兼容） ----------
  public double getLastOpponentMatch() {
    return lastOpponentMatch;
  }

  public int getLastOpponentOrder() {
    return lastOpponentOrder;
  }

  public String getLastOpponentVertex() {
    return lastOpponentVertex;
  }

  public String getLastAiVertex() {
    return lastAiVertex;
  }

  public void setLastAiVertex(String v) {
    this.lastAiVertex = v;
  }
}
