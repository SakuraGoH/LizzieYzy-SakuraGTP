# LizzieYzy-SakuraGo

A fork of [LizzieYzy](https://github.com/yzyray/lizzieyzy) that adds a **direct Fox Weiqi (野狐围棋) client bridge**, allowing a KataGo-based analysis engine to play live games on the Fox Weiqi platform without any external relay tool.

---

## Table of Contents

- [Overview](#overview)
- [Features](#features)
- [Requirements](#requirements)
- [Quick Start](#quick-start)
- [Building from Source](#building-from-source)
- [How It Works](#how-it-works)
- [Configuration](#configuration)
- [Usage](#usage)
- [Safety Notes](#safety-notes)
- [Known Limitations](#known-limitations)
- [Credits & Upstream Projects](#credits--upstream-projects)
- [License](#license)

---

## Overview

LizzieYzy is a popular Go analysis GUI for Leela Zero and KataGo. It is excellent for reviewing games and analyzing positions, but it does not natively support playing live games on the Fox Weiqi server.

This fork adds a lightweight bridge that:

1. Listens on a local TCP port for the Fox Weiqi client (the same way the original Python SakuraGTP does).
2. Parses the Fox private protocol (`$FARULE`, `$FAMOVE`, `$FASTATUS`, `$FARESULT`, ...).
3. Keeps the local LizzieYzy board in sync with the Fox game.
4. Drives KataGo through standard GTP (`kata-genmove_analyze`, `play`, `kata-set-rules`, ...).
5. Sends the engine's move back to the Fox client as `$AFPLAY` / `$AFSKIP` / `$AFGIVEUP` / `$AFSCORE`.

The whole bridge lives under `src/main/java/featurecat/lizzie/analysis/fox/` and is wired into the main window via **Sync → Fox Direct (协议直连)** in the menu bar.

---

## Features

- **Direct Fox client connection** — no intermediate relay, no external Python process.
- **GTP-based engine control** — works with any GTP-speaking engine, optimized for KataGo.
- **Automatic board synchronization** — every `FAMOVE` and `FASTATUS` is mirrored to the LizzieYzy board and to the engine.
- **Automatic genmove response** — the engine's `play <vertex>` output is captured and forwarded to Fox as `AFPLAY`.
- **Reconnect handling** — `FASTATUS` replays the full move history when the Fox client reconnects; duplicate `FAMOVE` messages are de-duplicated.
- **Manual placement lock** — during a Fox game, clicking on the board is disabled to prevent accidental moves. Analysis, winrate graph, and variation tree remain fully interactive.
- **Pass and resign support** — engine `pass` → `AFSKIP`, engine `resign` → `AFGIVEUP`.
- **Final score reporting** — `FASCORE` is answered with the engine's `final_score`.
- **Live winrate mode (optional)** — instead of a single `genmove`, the bridge can run `kata-analyze` continuously and commit the best move after a configurable time/visits budget.
- **Rule switching** — China, Japan, Korea, AGA, New Zealand, etc., via `kata-set-rules`.

---

## Requirements

| Component | Version |
|-----------|---------|
| Java | 8 or newer (11+ recommended) |
| Maven | 3.6+ (for building) |
| KataGo | 1.10+ recommended, with a working GPU backend |
| Fox Weiqi client | Windows desktop client with AI permission |
| OS | Windows 10 / 11 (Linux and macOS should work but are untested) |

For KataGo installation and model downloads, see the official KataGo project:

- **KataGo**: <https://github.com/lightvector/KataGo>
- **KataGo releases**: <https://github.com/lightvector/KataGo/releases>

For the upstream LizzieYzy project:

- **LizzieYzy**: <https://github.com/yzyray/lizzieyzy>
- **LizzieYzy releases**: <https://github.com/yzyray/lizzieyzy/releases>

---

## Quick Start

1. Download the latest `lizzie-yzy2.5.3-shaded.jar` from this repository's **Releases** page (or build it yourself, see below).
2. Place it next to the usual LizzieYzy launcher / `readboard.exe` / `java` runtime.
3. Start the Fox Weiqi client and log in with an AI-permitted account.
4. Launch LizzieYzy.
5. Configure KataGo (engine path, model, config) as usual via **Settings → Engine**.
6. In the menu bar, choose **Sync → Fox Direct (协议直连)**.
7. In the Fox Weiqi client, set the AI address to `127.0.0.1:6000`.
8. Start a game on Fox. LizzieYzy will keep the board in sync and play for you.

The GTP console (`E` key) will show bridge messages prefixed with `[Fox]`, for example:

```
[Fox] 开始监听野狐客户端端口 6000
[Fox] 野狐客户端已连接: /127.0.0.1:4752
[Fox] 规则: 棋盘19, 贴目6.5, 让子[]
[Fox] 野狐确认落子: B Q16（第 1 手） isAiTurn=false
[Fox] 请求 genmove W
[Fox] 发送落子: D4 -> AFPLAY W,2^3^15^W
```

---

## Building from Source

```bash
git clone <this-repo-url>
cd <this-repo-dir>
mvn clean package -DskipTests
```

The build produces:

```
target/lizzie-yzy2.5.3-shaded.jar
```

Copy it into your LizzieYzy distribution folder (replacing the old jar) and launch as usual.

### Building on Windows with PowerShell

A helper script is included:

```powershell
powershell -ExecutionPolicy Bypass -File .\build-fox.ps1
```

It runs `mvn clean package`, copies the jar to the configured distribution folder, and verifies the SHA-256 of the copy.

---

## How It Works

```
┌──────────────┐   Fox protocol (TCP)   ┌──────────────────────────┐
│ Fox client   │◄──────────────────────►│ FoxClientServer          │
└──────────────┘                        │  ↓ parses $FARULE/…      │
                                        │ FoxBridge                │
                                        │  ↓ GTP                   │
                                        │ Leelaz                   │
                                        │  ↓ subprocess            │
                                        │ KataGo                   │
                                        └──────────────────────────┘
```

### Move flow (opponent's move)

1. Fox sends `$FAMOVE,0,W,<n>^x^y^B*XX` (opponent just played B at `(x, y)`).
2. `FoxBridge.handleFamove` calls `LizzieFoxHost.onFoxMove`.
3. `LizzieFoxHost` places the stone on the LizzieYzy board with `suppressPonderOnPlay = true`, which sends `play B <vertex>` to KataGo via `Leelaz.playMove`.
4. `FoxBridge.startThinking` sets `waitingGenmoveResult = true` and asks `LizzieFoxHost.onAiTurn` to call `Leelaz.genmove("W")`.

### Move flow (AI's move)

1. `Leelaz.genmove` sets `isThinking = true` and `waitingGenmoveResponse = true`, then sends `kata-genmove_analyze W 10`.
2. KataGo outputs:
   ```
   =
   play D4
   MALKOVICH:Visits ...
   ```
3. `Leelaz.read` first calls `FoxBridge.onEngineOutput("play D4")`. `waitingGenmoveResult` matches, so `sendMoveToFox("D4")` emits `$AFPLAY,W,<n>^3^15^W*XX` to Fox.
4. `Leelaz.parseLine("play D4")` then sees `waitingGenmoveResponse`, calls `handleGenmoveResult("D4")`, which places D4 on the local board with `suppressPlayCommandOnGenmove = true` to avoid sending a redundant `play` back to KataGo.

### Safety guards

- `Leelaz.suppressPonderOnPlay` — prevents a `kata-analyze` from being issued immediately after a place (would fight with a pending `genmove`).
- `Leelaz.suppressPlayCommandOnGenmove` — prevents the genmove result from being echoed back to KataGo as a `play` command.
- `LizzieFrame.foxGameInputLocked` — disables left-click, right-click, and manual placement on the board while a Fox game is in progress. Set by `FARULE`/`FAMOVE`, cleared by `FARESULT` or by stopping the bridge.

---

## Configuration

The Fox bridge currently exposes only two settings, both controlled from the GTP console or by editing the code:

| Setting | Default | How to change |
|---------|---------|---------------|
| Listen port | `6000` | Edit `LizzieFrame.toggleFoxDirect` (search for `final int port`). |
| Live winrate | disabled | Call `FoxBridge.setLiveWinrate(true)` before starting a game; not yet exposed in the GUI. |

A proper settings dialog is on the roadmap. For now, if you need a different port, edit the hardcoded `6000` and rebuild.

---

## Usage

### Analysis only (no Fox)

Launch LizzieYzy normally. The Fox bridge is inactive until you explicitly start it.

### Fox game

1. **Settings → Engine** — configure KataGo:
   - Engine path: `C:\path\to\katago.exe`
   - Weight: `C:\path\to\model.bin.gz`
   - Config: `C:\path\to\gtp.cfg`
2. **Sync → Fox Direct** — start the TCP listener on port 6000.
3. In Fox, set the AI address to `127.0.0.1:6000`.
4. Start a game. The board auto-syncs and the engine plays.
5. When the game ends, `FARESULT` clears the board and unlocks manual placement.

### Stopping the bridge

Either:

- **Sync → Fox Direct** again (toggles off), or
- Close LizzieYzy. The TCP port is released on exit.

If the Fox client disconnects unexpectedly, the bridge automatically waits for reconnection (5-second interval by default, see `FoxClientServer.setAutoReconnect`).

### Common GTP console messages

| Message | Meaning |
|---------|---------|
| `[Fox] 开始监听野狐客户端端口 6000` | Listener started |
| `[Fox] 野狐客户端已连接` | Fox client connected |
| `[Fox] 规则: 棋盘19, 贴目6.5, 让子[]` | Game rules received |
| `[Fox] 重连重放完成，已同步到第 N 手` | Replay after reconnect |
| `[Fox] 请求 genmove W` | AI's turn, engine is thinking |
| `[Fox] 发送落子: D4 -> AFPLAY W,...` | Move sent to Fox |
| `[Fox] 发送停一手: AFSKIP W` | AI passed |
| `[Fox] 发送认输: AFGIVEUP W` | AI resigned |
| `[Fox] 棋盘手动落子 已锁定` | Manual placement disabled |
| `[Fox] 野狐重复落子已跳过` | Duplicate `FAMOVE` ignored |

---

## Safety Notes

- **The manual placement lock is intentional.** In a live Fox game, an accidental click on the board would desync the local board from the Fox server and could cause the engine to play an illegal move. The lock protects against this. Analysis, variation tree, and winrate graph remain interactive.
- **Use a dedicated Fox account.** The AI account must have permission to play rated games. Do not use your personal account.
- **The bridge does not cheat.** It only parses messages the Fox client itself sends to a local port. No memory reading, no packet injection outside the standard Fox AI protocol.
- **No credentials are stored.** The bridge never sees your Fox username or password; the client handles authentication separately.
- **The bridge does not report scores automatically.** `FASCORE` responses come from KataGo's `final_score`; if KataGo and Fox disagree on the rules, the score may differ.

---

## Known Limitations

- **Live winrate mode is not exposed in the GUI.** `setLiveWinrate` must be called programmatically.
- **The listen port is hardcoded** to `6000`. A settings dialog is planned.
- **Fox protocol variants are not exhaustively covered.** The bridge handles `FARULE`, `FAMOVE`, `FASTATUS`, `FASKIP`, `FATIMELEFT`, `FARESULT`, `FASCORE`. New commands added by Fox in the future may be silently ignored.
- **Undo / delete move / clear board are not locked.** Only placement on the board is blocked. If you also want the menu items `Clear Board`, `Delete Move`, `Delete Branch`, and `Undo` to be disabled during a Fox game, this must be added manually in `Menu.java`.
- **Windows only (tested).** The bridge uses standard `java.net` APIs and should work on Linux and macOS, but this has not been tested.
- **Double-engine mode is not supported.** `sendCommandNoLeelaz2` is used exclusively, so `Lizzie.leelaz2` will not participate in Fox games.

---

## Credits & Upstream Projects

This project would not exist without the work of the following open-source projects:

### LizzieYzy

- Author: **yzyray**
- Repository: <https://github.com/yzyray/lizzieyzy>
- License: GPL-3.0

LizzieYzy is the GUI this fork is built on. All board rendering, variation tree, winrate graph, engine management, and SGF handling come from the upstream project.

### KataGo

- Author: **David J. Wu** and contributors
- Repository: <https://github.com/lightvector/KataGo>
- License: MIT

KataGo is the recommended engine. The bridge was developed and tested against KataGo 1.10+ with `kata-genmove_analyze` and `kata-set-rules`.

### Leela Zero

- Repository: <https://github.com/leela-zero/leela-zero>
- License: GPL-3.0

Leela Zero is the original engine LizzieYzy targets. The GTP handling code paths in `Leelaz.java` are largely inherited from Lizzie and Leela Zero.

### SakuraGTP

- Repository: <https://github.com/SakuraGoH/SakuraGTP>
- License: GPL-3.0

SakuraGTP is a Python implementation of the same Fox bridge concept. The Fox protocol parsing, `FAMOVE`/`FASTATUS` semantics, and the `AFPLAY`/`AFSKIP`/`AFGIVEUP`/`AFSCORE` response shapes were cross-checked against SakuraGTP's behavior. The Java bridge is a from-scratch implementation, but its protocol understanding owes a lot to SakuraGTP.

### Original Lizzie

- Repository: <https://github.com/featurecat/lizzie>
- License: GPL-3.0

The base Lizzie project that all of the above derivatives stem from.

### Fox Weiqi (野狐围棋)

- Website: <https://www.foxwq.com/>

The Fox client is proprietary software. This bridge interacts with it only through the officially supported local AI socket, using the message shapes that the client itself sends.

---

## License

This project is distributed under the **GNU General Public License v3.0**, the same license as the upstream LizzieYzy project.

See [`LICENSE`](LICENSE) for the full text.

You are free to use, modify, and redistribute this software, provided that:

- The source code (including any modifications you make) is made available under the same license.
- The original copyright notices are preserved.
- Any derivative work is clearly marked as such.

If you distribute a compiled jar, you **must** also make the corresponding source code available, as required by the GPL.

---

## Disclaimer

This software is provided "as is", without warranty of any kind. The authors are not responsible for:

- Account bans or warnings issued by Fox Weiqi for any reason.
- Incorrect moves, missed moves, or board desynchronization caused by network issues, engine crashes, or protocol changes.
- Any loss of games, rating, or money resulting from use of this software.

Use at your own risk.

