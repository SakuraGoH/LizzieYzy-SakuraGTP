package featurecat.lizzie.analysis.fox;

import featurecat.lizzie.Lizzie;
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

  @Override
  public boolean isEngineRunning() {
    return Lizzie.leelaz != null && Lizzie.leelaz.isLoaded() && Lizzie.leelaz.isStarted();
  }

  @Override
  public void sendGtp(String command) {
    if (Lizzie.leelaz == null) return;
    Lizzie.leelaz.sendCommandNoLeelaz2(command);
  }

  @Override
  public void onBoardUpdated() {
    if (Lizzie.frame != null) Lizzie.frame.refresh();
  }

  @Override
  public void onAiTurn(String color) {
    if (Lizzie.leelaz == null) return;
    // 触发 Leelaz.genmove：isThinking=true, waitingGenmoveResponse=true。
    // KataGo 返回 "play <vertex>" 时：
    //   Leelaz.parseLine 的 play 分支 place 到棋盘；
    //   FoxBridge.onEngineOutput 抓同一行发 AFPLAY。
    Lizzie.leelaz.genmove(color);
  }

  @Override
  public void onCandidates(List<FoxBridge.Candidate> cands) {
    // 候选点由 Leelaz.parseInfoKatago 自己处理
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
    if (Lizzie.leelaz == null) {
      Lizzie.board.place(x, y, stone, false);
      return;
    }
    boolean old = Lizzie.leelaz.suppressPonderOnPlay;
    Lizzie.leelaz.suppressPonderOnPlay = true;
    try {
      Lizzie.board.place(x, y, stone, false);
    } finally {
      Lizzie.leelaz.suppressPonderOnPlay = old;
    }
  }
}
