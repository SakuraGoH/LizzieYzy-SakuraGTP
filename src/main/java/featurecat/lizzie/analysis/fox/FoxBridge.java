package featurecat.lizzie.analysis.fox;

import java.util.*;
import java.util.concurrent.locks.ReentrantLock;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 野狐命令分发 + GTP 桥接。 与 LizzieYzy 的交互通过 LizzieHost 抽象。
 *
 * <p>分工： - FoxBridge 负责野狐协议解析、GTP 命令收发、候选点收集、落子决策； - LizzieHost 负责把局面落到 LizzieYzy 的 Board，并触发
 * Leelaz.genmove。
 *
 * <p>落子显示规则（试点 KataGo）： - 对手手（FAMOVE）：FoxBridge 调 host.onFoxMove，由 host place，同步给引擎； - AI 手 genmove
 * 模式：KataGo 真实落子在 "play <vertex>" 行，由 Leelaz.parseLine 的 play 分支 place 到棋盘；
 * FoxBridge.onEngineOutput 从同一行提取 vertex 并发 AFPLAY 给野狐； - AI 手实时胜率模式：FoxBridge 调 host.onAiMove，由
 * host place。
 */
public class FoxBridge {

  /** LizzieYzy 宿主接口，由 LizzieFoxHost 实现 */
  public interface LizzieHost {
    boolean isEngineRunning();

    void sendGtp(String command);
    /** 通知宿主：局面已更新（用于刷新棋盘/胜率） */
    void onBoardUpdated();
    /** 通知宿主：轮到 AI，请触发 genmove 或 analyze */
    void onAiTurn(String color);
    /** 通知宿主：候选点更新 */
    void onCandidates(List<Candidate> cands);
    /** 通知宿主：对局结束 */
    void onGameEnd();

    void log(String msg);

    /** 野狐对局开始/结束：锁定或解锁棋盘手动落子 */
    void onInputLockChanged(boolean locked);

    // ---- 棋盘同步 ----
    /** FARULE：棋盘尺寸/贴目/让子变化 */
    void onRuleChanged(int boardSize, double komi, List<String> handicap);
    /** FASTATUS：重连时批量重放野狐历史落子 */
    void onReplayMoves(Map<Integer, String[]> foxMoves);
    /** FAMOVE：野狐确认的一手（对手手） */
    void onFoxMove(int x, int y, String color);
    /** AI 落子后通知宿主显示（实时胜率模式下使用） */
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

  // ---------- 正则常量 ----------
  private static final Pattern INFO_MOVE = Pattern.compile("info move ([A-Ta-t]\\d+)");
  private static final Pattern VISITS = Pattern.compile("visits (\\d+)");
  private static final Pattern WINRATE = Pattern.compile("winrate ([\\d.]+)");
  private static final Pattern SCORE_LEAD = Pattern.compile("scoreLead (-?[\\d.]+)");
  private static final Pattern ORDER = Pattern.compile("order (\\d+)");
  private static final Pattern GTP_VERTEX = Pattern.compile("^([A-HJ-T])(\\d{1,2})$");

  private LizzieHost host;
  private final FoxClientServer fox;
  private final ReentrantLock lock = new ReentrantLock();

  public int boardSize = 19;
  public double komi = 6.5;
  public String aiColor = "B";
  public Map<Integer, String[]> foxMoves = new LinkedHashMap<>();
  public int lastMoveNumber = 0;

  public boolean liveWinrate = false;
  public double maxTime = 5.0;
  public int maxVisits = 0;
  public int maxPlayouts = 0;

  private boolean analyzing = false;
  private long analyzeStart = 0;
  private String analyzeColor = "B";
  private final Map<String, Candidate> candidates = new LinkedHashMap<>();
  private volatile boolean candidatesDirty = false;

  // 等待 genmove 落子（KataGo 在 "play <vertex>" 行给出）。
  // 由 startThinking() 置 true；onEngineOutput 抓到 play 行后置 false。
  private volatile boolean waitingGenmoveResult = false;

  private volatile boolean flushStarted = false;
  private volatile boolean timerStarted = false;

  // final_score 相关
  private String pendingScoreOpponent = "0";

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

  // ---------- 配置 ----------
  public void setLiveWinrate(boolean enabled) {
    this.liveWinrate = enabled;
    if (host != null) host.log("实时胜率模式: " + (enabled ? "开" : "关"));
  }

  public void setMaxTime(double t) {
    this.maxTime = t;
    if (host != null) host.log("maxTime = " + t);
  }

  public void setMaxVisits(int v) {
    this.maxVisits = v;
    if (host != null) host.log("maxVisits = " + v);
  }

  public void setMaxPlayouts(int p) {
    this.maxPlayouts = p;
    if (host != null) host.log("maxPlayouts = " + p);
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
    candidatesDirty = false;
    waitingGenmoveResult = false;
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
      // 去重：野狐重连时 FASTATUS 已重放历史手，
      // FAMOVE 可能再次送同一手，已存在则只更新 lastMoveNumber，不重复 place。
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
        // 无论 isAiTurn 与否，这一手都必须先 place 到棋盘，让 Leelaz 把 play 同步给引擎。
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
    stopAnalyze();
    waitingGenmoveResult = false;
    foxMoves.clear();
    lastMoveNumber = 0;
    candidates.clear();
    candidatesDirty = false;
    host.onCandidates(Collections.emptyList());
    host.onInputLockChanged(false);
    host.onGameEnd();
  }

  private void handleFascore(String[] items) {
    pendingScoreOpponent = items.length > 0 ? items[0] : "0";
    sendGtp("final_score");
  }

  private void startThinking() {
    candidates.clear();
    candidatesDirty = false;
    analyzeColor = aiColor;
    if (liveWinrate) {
      analyzing = true;
      analyzeStart = System.currentTimeMillis();
      if (maxTime > 0) sendGtp("kata-set-param maxTime " + maxTime);
      sendGtp("kata-analyze " + aiColor + " interval 50 maxmoves 10");
      host.log("开始实时分析 " + aiColor);
    } else {
      // 交给 Leelaz.genmove，让 isThinking 和 waitingGenmoveResponse 置位。
      // KataGo 会在后续 "play <vertex>" 行给出真实落子。
      waitingGenmoveResult = true;
      host.log("请求 genmove " + aiColor);
    }
    host.onAiTurn(aiColor);
  }

  private void stopAnalyze() {
    if (!analyzing) return;
    analyzing = false;
    candidatesDirty = false;
    sendGtp("stop");
    candidates.clear();
    host.onCandidates(Collections.emptyList());
  }

  // ---------- 定时器 ----------
  private void startTimerThread() {
    Thread t =
        new Thread(
            () -> {
              while (true) {
                try {
                  Thread.sleep(100);
                } catch (InterruptedException ignored) {
                }
                if (!analyzing) continue;
                if (host == null) continue;
                double elapsed = (System.currentTimeMillis() - analyzeStart) / 1000.0;
                if (elapsed >= maxTime) commitLiveMove();
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
                  Thread.sleep(30);
                } catch (InterruptedException ignored) {
                }
                if (candidatesDirty && analyzing) {
                  candidatesDirty = false;
                  if (host == null) continue;
                  List<Candidate> list = new ArrayList<>(candidates.values());
                  list.sort(Comparator.comparingInt(c -> c.order));
                  host.onCandidates(list.subList(0, Math.min(20, list.size())));
                }
              }
            },
            "FoxFlush");
    t.setDaemon(true);
    t.start();
  }

  private void commitLiveMove() {
    if (host == null) return;
    if (candidates.isEmpty()) {
      host.log("无候选，无法落子");
      return;
    }
    Candidate best =
        candidates.values().stream().min(Comparator.comparingInt(c -> c.order)).orElse(null);
    if (best == null) return;
    analyzing = false;
    candidatesDirty = false;
    sendGtp("stop");
    candidates.clear();
    host.onCandidates(Collections.emptyList());

    host.onAiMove(best.x, best.y, analyzeColor);

    lastMoveNumber++;
    String moveStr = lastMoveNumber + "^" + best.x + "^" + best.y + "^" + analyzeColor;
    fox.send("AFPLAY", analyzeColor, moveStr);
    host.log("发送落子: " + best.vertex + " -> AFPLAY " + analyzeColor + " " + moveStr);
    foxMoves.put(lastMoveNumber, new String[] {analyzeColor, best.vertex});
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

    // ---- genmove 落子在 "play <vertex>" 行 ----
    // KataGo 的 kata-genmove_analyze 输出顺序：
    //   =
    //   play Q16
    //   MALKOVICH:...
    // "=" 只是同步应答，body 为空；真实落子在 "play Q16"。
    if (waitingGenmoveResult && stripped.startsWith("play")) {
      String[] params = stripped.split("\\s+");
      if (params.length >= 2) {
        String vertex = params[1].trim();
        waitingGenmoveResult = false;
        sendMoveToFox(vertex);
        return;
      }
    }

    // final_score 的应答是普通 "= B+3.5"，走这里
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

    if (stripped.startsWith("info move")) {
      parseInfoMove(stripped);
      return;
    }
  }

  private boolean isFinalScoreResult(String body) {
    if (body.isEmpty()) return false;
    if (body.equals("0") || body.equalsIgnoreCase("draw")) return true;
    return body.length() >= 3 && "WBwb".indexOf(body.charAt(0)) >= 0 && body.charAt(1) == '+';
  }

  private void parseInfoMove(String line) {
    try {
      Matcher mm = INFO_MOVE.matcher(line);
      if (!mm.find()) return;
      String move = mm.group(1);
      Candidate c = new Candidate();
      c.vertex = move;
      Matcher mv = VISITS.matcher(line);
      if (mv.find()) c.visits = Long.parseLong(mv.group(1));
      Matcher mw = WINRATE.matcher(line);
      if (mw.find()) c.winrate = Double.parseDouble(mw.group(1));
      Matcher ms = SCORE_LEAD.matcher(line);
      if (ms.find()) c.score = Double.parseDouble(ms.group(1));
      Matcher mo = ORDER.matcher(line);
      c.order = mo.find() ? Integer.parseInt(mo.group(1)) : 999;
      int[] xy = gtpToXy(move);
      if (xy == null) return;
      c.x = xy[0];
      c.y = xy[1];
      candidates.put(move, c);
      candidatesDirty = true;
      if (c.order == 0) {
        if (maxVisits > 0 && c.visits >= maxVisits) commitLiveMove();
        else if (maxPlayouts > 0 && c.visits >= maxPlayouts) commitLiveMove();
      }
    } catch (Exception e) {
      if (host != null) host.log("info move 解析失败: " + e.getMessage());
    }
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
      return;
    }
    if ("pass".equalsIgnoreCase(move)) {
      fox.send("AFSKIP", aiColor);
      host.log("发送停一手: AFSKIP " + aiColor);
      lastMoveNumber++;
      foxMoves.put(lastMoveNumber, new String[] {aiColor, "pass"});
      candidates.clear();
      candidatesDirty = false;
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
    candidates.clear();
    candidatesDirty = false;
    host.onCandidates(Collections.emptyList());
    // 棋盘已由 Leelaz.parseLine 的 play 分支 place，不重复落子
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

  /**
   * 发送 GTP 命令。 注意：不要给命令加 id 前缀。 Leelaz 内部用 cmdNumber 计数并自行拼接 id，外部再塞 id 会让 KataGo 收到 "123 genmove
   * W" 这种非法命令。
   */
  private void sendGtp(String cmd) {
    if (host == null) return;
    if (!host.isEngineRunning()) {
      host.log("引擎未运行，跳过 GTP: " + cmd);
      return;
    }
    host.sendGtp(cmd);
    host.log("GTP发送: " + cmd);
  }
}
