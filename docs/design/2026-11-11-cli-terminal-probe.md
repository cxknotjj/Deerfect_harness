# CLI 终端探测：为什么曾卡 40 秒（2026-11-11）

> JLine 在判断终端类型之前跑外部命令且 `waitFor` 无超时；排查手法与最终取舍。

## cli 终端探测：为什么会卡 40 秒（2026-11-11）

`TerminalInput.open()` 原来直接调 `TerminalBuilder.terminal()`。JLine 3.30 的 `doBuild()`
在判断终端类型**之前**，会先让每个 provider 判断「系统流归不归我管」；
`ExecTerminalProvider.isPosixSystemStream` 的实现是执行外部命令 `test -t 0`，然后
`Process.waitFor()` **不带超时**。

在 Surefire fork 里那个子进程（MSYS 的 `sh`）要 39~49 秒才退出，于是 `TerminalInputTest`
单类耗时 57 秒。两个看起来该管用的开关都无效：`org.jline.terminal.dumb=true` 与
`org.jline.terminal.type=dumb` 实测仍要 39 秒——探测发生在它们生效之前（实测，非推断）。

现在的做法是**先判断再探测**：`System.console() == null` 且 `TERM` 未设或为 `dumb` 时，
本就读不到按键，直接走行式降级，不进入 JLine。两个信号的取舍：

- 有控制台（cmd / Windows Terminal / 另开的终端宿主）→ 走 JLine。JDK 22+ 即使重定向了
  标准流，只要有控制台 `System.console()` 就非 null。
- 没有控制台但 `TERM` 是 `xterm-*` 之类 → 仍走 JLine。Windows 下的 mintty（Git Bash）
  是 POSIX 伪终端，只有 TERM 能反映出来，靠 exec provider 工作，不能误判成管道。
- 两者都没有 → 管道 / CI / Surefire，直接降级。

效果：`TerminalInputTest` 57.2s → 1.2s，全量 `mvn verify` 3:18 → 2:09。

排查手法也留在这里：`jstack` 附不上 Surefire fork（`AttachNotSupportedException: 拒绝访问`），
改成在 `open()` 里挂一个 8 秒的看门狗线程打 `Thread.getAllStackTraces()`，一次就定位到
`ProcessImpl.waitForInterruptibly ← isPosixSystemStream ← TerminalBuilder.doBuild`。
