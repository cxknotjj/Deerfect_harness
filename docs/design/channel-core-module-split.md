# Channel/Core 模块拆分设计：Maven 多模块边界与通用 SPI

> 范围：把原单体工程拆为「契约（`harness-api`）— 内核（`server`）— 渠道（`channel-qq`）」三层
> Maven 模块，用编译期依赖方向 + enforcer 规则固化边界，并新增渠道无关的回复后处理 SPI。
> 关键词：单向依赖、编译期隔离、`ReplyPostProcessor`、`ChatResponse.flags`。

## 一、三模块依赖图

```
                 harness-api（契约：端口接口 + DTO；不含任何渠道实现）
                       ▲                              ▲
       compile         │                              │         compile
                       │                              │
                  ┌────┴────┐                    ┌────┴─────────┐
                  │  server │                    │  channel-qq  │
                  │（core + │                    │（QQ / NapCat │
                  │  web /  │                    │  OneBot 11） │
                  │ Spring  │                    └──────────────┘
                  │ Boot app│
                  └────┬────┘
                       │  runtime（编译期不可见，仅运行期装配到 fat jar）
                       └──────────────────────────────────────────▶
```

- `harness-api` ←── compile ── `server`：内核编译期只认契约，不认渠道。
- `harness-api` ←── compile ── `channel-qq`：渠道编译期只认契约。
- `server` ──── runtime ───▶ `channel-qq`：**仅运行期**装配；`server` 的编译期 classpath 里没有渠道类。

模块清单见根 `pom.xml` 的 `<modules>`：`harness-api, channel-qq, server, cli, wms-mcp-adapter`。

## 二、依赖规则（强制）

### 规则 ①：core(server) 不得 compile 依赖 channel-qq

由 `server/pom.xml` 的 maven-enforcer `bannedDependencies` 守护：

```xml
<plugin>
  <artifactId>maven-enforcer-plugin</artifactId>
  <executions>
    <execution>
      <id>ban-core-on-channel</id>
      <goals><goal>enforce</goal></goals>
      <configuration>
        <rules>
          <bannedDependencies>
            <!-- core(server) 不得 compile 依赖渠道模块 -->
            <excludes>
              <exclude>com.dark:javaHarness-channel-qq:*:*:compile</exclude>
            </excludes>
            <searchTransitive>false</searchTransitive>
          </bannedDependencies>
        </rules>
        <fail>true</fail>
      </configuration>
    </execution>
  </executions>
</plugin>
```

- 渠道依赖 `channel-qq` 必须以 `runtime` scope 声明（`server/pom.xml` 中即如此），编译期无法 `import`
  任何 `com.dark.javaHarness.channel.qq.*` 类型——把「内核耦合渠道」变成编译错误。
- 若有人把 `runtime` 改成 `compile`，enforcer 立即让构建失败：隔离被破坏时**构建直接红**，
  而不是等到运行期才发现。

### 规则 ②：channel-qq 只依赖 harness-api

`channel-qq/pom.xml` 中仅有两类依赖：

- `com.dark:javaHarness-api`（契约）；
- 渠道自身所需的第三方库（spring-boot-starter-web、mybatis-plus、okhttp、jackson 等）。

渠道**不依赖** `server`，因此渠道代码不得引用任何 core 实现类——渠道只能面向 `harness-api` 编程。

### 规则 ③：新增渠道输出控制必须走 ReplyPostProcessor，不得改 core

渠道要对自己的回复做「清洗 / 打标」（例如 QQ 语音标记 `[[voice]]`），一律走通用 SPI：

1. 在**渠道侧**新增一个 `ReplyPostProcessor` 实现（`@Component`），负责剥离渠道私有标记并声明 flag；
2. 若还需新增出参字段，在**契约侧** `ChatResponse`（`harness-api`）上扩展通用字段（如已有的
   `flags`），而不是塞进渠道专有字段；
3. **不得修改 core** 去识别任何渠道私有约定。

SPI 定义（`harness-api`，`com.dark.javaHarness.service.ReplyPostProcessor`）：

```java
public interface ReplyPostProcessor {
    /** 处理结果：@param text 清洗后的文本 @param flags 声明出的标志（如 "voice"） */
    record Processed(String text, Set<String> flags) { }

    /** 处理原始回复文本；返回清洗后的文本与标志。入参非 null。 */
    Processed process(String rawReply);
}
```

约定：实现必须是**无副作用、幂等**的纯文本处理，不得抛异常（core 捕获并跳过，保证渠道故障不拖垮回复）。
core（`server` 的 `ChatServiceImpl`）用 `ObjectProvider<ReplyPostProcessor>` 收集全部实现，
在「写回会话记忆」与「构造 `ChatResponse`」之前按序调用；**无实现时零行为**（后处理为空、`flags` 为空集）。

渠道侧示例（`channel-qq` 的 `QqReplyProcessor`）：解析 `[[voice]]` → 剥离文本并声明 `voice` flag；
core 不认识 `[[voice]]`，只把 `flags` 透出到 `ChatResponse`，由渠道自行决定怎么用。

## 三、`cli` 与 `wms-mcp-adapter` 与本次拆分无关

- **`cli`** 是纯 HTTP 客户端：通过 REST 调 `server`，并**自带一份 DTO 副本**
  （如 `cli/.../domain/dto/ChatResponse.java`，与 `harness-api` 的同名 record）。
  它不依赖 `harness-api`、不依赖 `channel-qq`，本次拆分不改其代码。
  DTO 副本与 `harness-api` 的差异（例如 `flags` 字段）**对旧客户端无影响**：Jackson 反序列化
  默认忽略未知字段，服务端多透出一个 `flags` 不会破坏 `cli` 的解析——**向后兼容**。
- **`wms-mcp-adapter`** 独立于本次拆分，未受影响。

> 结论：本重构只动 `harness-api` / `channel-qq` / `server` 三个模块，`cli` 与 `wms-mcp-adapter` 零改动。
</content>
