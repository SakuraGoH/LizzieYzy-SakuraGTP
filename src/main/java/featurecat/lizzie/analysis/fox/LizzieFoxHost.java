package featurecat.lizzie.analysis.fox;

import featurecat.lizzie.Lizzie;
import featurecat.lizzie.analysis.Leelaz;
import featurecat.lizzie.rules.Board;
import featurecat.lizzie.rules.BoardHistoryNode;
import featurecat.lizzie.rules.Stone;
import java.util.Collections;
import java.util.List;
import java.util.Map;

public class LizzieFoxHost implements FoxBridge.LizzieHost {

  private final FoxBridge bridge;

  public LizzieFoxHost(FoxBridge bridge) {
    this.bridge = bridge;
  }

  private Leelaz engine() {
    return Lizzie.foxEngine != null ? Lizzie.foxEngine : Lizzie.leelaz;
  }

  @Override
  public boolean isEngineRunning() {
    Leelaz e = engine();
    return e != null && e.isLoaded() && e.isStarted();
  }

  @Override
  public void sendGtp(String command) {
    Leelaz e = engine();
    if (e == null) return;
    e.sendCommandNoLeelaz2(command);
  }

  @Override
  public void onBoardUpdated() {
    if (Lizzie.frame != null) Lizzie.frame.refresh();
  }

  @Override
  public void onAiTurn(String color) {
    Leelaz e = engine();
    if (e == null) return;
    // 纯 genmove：只发 genmove，等 "play <vertex>" 落子
    e.genmove(color);
  }

  @Override
  public void onCandidates(List<FoxBridge.Candidate> cands) {
    // 纯 genmove 模式：FoxBridge 不提供候选点，这里什么都不做。
    // 实时胜率/候选由 Leelaz.parseInfoKatago 正常写入，胜率曲线依赖它。
  }

  @Override
  public void onGameEnd() {
    if (Lizzie.board != null) Lizzie.board.clear(false);
  }

  @Override
  public void log(String msg) {
    if (Lizzie.gtpConsole != null) Lizzie.gtpConsole.addLine("[Fox] " + msg + "\n");
    else System.out.println("[Fox] " + msg);
  }

  @Override
  public void onInputLockChanged(boolean locked) {
    if (Lizzie.frame != null) {
      Lizzie.frame.foxGameInputLocked = locked;
    }
    log("棋盘手动落子 " + (locked ? "已锁定" : "已解锁"));
  }

  @Override
  public void onRuleChanged(int boardSize, double komi, List<String> handicap) {
    if (Board.boardWidth != boardSize || Board.boardHeight != boardSize) {
      Lizzie.board.reopen(boardSize, boardSize);
    } else {
      Lizzie.board.clear(false);
    }
    Lizzie.board.setKomi(komi);
    if (handicap != null && !handicap.isEmpty()) {
      for (String v : handicap) {
        int[] xy = bridge.gtpToXy(v);
        if (xy != null) {
          placeWithoutPonder(xy[0], xy[1], Stone.BLACK);
        }
      }
    }
  }

  @Override
  public void onReplayMoves(Map<Integer, String[]> foxMoves) {
    List<Integer> keys = new java.util.ArrayList<>(foxMoves.keySet());
    Collections.sort(keys);
    for (int n : keys) {
      String[] mv = foxMoves.get(n);
      int[] xy = bridge.gtpToXy(mv[1]);
      if (xy != null) {
        placeAtMainEndKeepView(xy[0], xy[1], "B".equals(mv[0]) ? Stone.BLACK : Stone.WHITE);
      }
    }
  }

  @Override
  public void onFoxMove(int x, int y, String color) {
    placeAtMainEndKeepView(x, y, "B".equals(color) ? Stone.BLACK : Stone.WHITE);
  }

  @Override
  public void onAiMove(int x, int y, String color) {
    try {
      bridge.setLastAiVertex(bridge.xyToGtp(x, y));
    } catch (Throwable ignored) {
    }
    placeAtMainEndKeepView(x, y, "B".equals(color) ? Stone.BLACK : Stone.WHITE);
  }

  /** 把新落子接到主分支末尾，但不改变用户当前回看位置。 */
  private void placeAtMainEndKeepView(int x, int y, Stone stone) {
    if (Lizzie.board == null) return;
    BoardHistoryNode viewNode = Lizzie.board.getHistory().getCurrentHistoryNode();
    BoardHistoryNode mainEnd = Lizzie.board.getHistory().getMainEnd();
    boolean userWasReviewing = (viewNode != mainEnd);

    if (userWasReviewing) {
      goToNode(mainEnd);
    }

    placeWithoutPonder(x, y, stone);

    if (userWasReviewing && viewNode != null) {
      goToNode(viewNode);
      if (Lizzie.frame != null) Lizzie.frame.refresh();
    }
  }

  private void goToNode(BoardHistoryNode target) {
    if (Lizzie.board == null || target == null) return;
    if (Lizzie.board.getHistory().getCurrentHistoryNode() == target) return;
    Lizzie.board.moveToAnyPosition(target);
    Lizzie.board.clearAfterMove();
  }

  private void placeWithoutPonder(int x, int y, Stone stone) {
    if (Lizzie.board == null) return;
    Leelaz e = engine();
    if (e == null) {
      Lizzie.board.place(x, y, stone, false);
      return;
    }
    boolean old = e.suppressPonderOnPlay;
    e.suppressPonderOnPlay = true;
    try {
      Lizzie.board.place(x, y, stone, false);
    } finally {
      e.suppressPonderOnPlay = old;
    }
  }
}
