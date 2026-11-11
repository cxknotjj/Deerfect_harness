package com.dark.javaHarness.tool.builtin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.dark.javaHarness.file.InMemoryFileArtifactStore;
import com.dark.javaHarness.contract.api.FileArtifactStore;
import com.dark.javaHarness.tool.trace.ToolCallTracer;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;

/**
 * send_file 单测：用假 Shell 顶替容器，验「读—校验—登记」三段，不碰 Docker。
 *
 * <p>真实链路（agentscope 沙箱 + stat/base64）只能在有 Docker 的环境手工验；这里锁的是
 * 边界判定：越界路径、超限、空文件、文件名与 base64 折行。
 */
class SendFileToolTest {

    private static final String TURN = "turn-1";
    private static final long MAX = 1_000_000;

    @Test
    void registersFileWithDecodedBytes() {
        FileArtifactStore store = new InMemoryFileArtifactStore();
        byte[] content = "报告正文".getBytes(StandardCharsets.UTF_8);
        ToolCallback tool = SendFileTool.callback(fakeShell(content.length, content), store, MAX);

        String result = tool.call("{\"path\":\"reports/a.md\"}", context(TURN));

        assertTrue(result.contains("已附上"), result);
        Optional<FileArtifactStore.FileArtifact> artifact = store.findByTurn(TURN);
        assertTrue(artifact.isPresent(), "工件应已登记");
        assertEquals("a.md", artifact.get().fileName());
        assertEquals("报告正文", new String(artifact.get().content(), StandardCharsets.UTF_8));
    }

    @Test
    void nameParamOverridesFileName() {
        FileArtifactStore store = new InMemoryFileArtifactStore();
        ToolCallback tool = SendFileTool.callback(fakeShell(2, "ok".getBytes(StandardCharsets.UTF_8)), store, MAX);

        tool.call("{\"path\":\"reports/a.md\",\"name\":\"季度报告.md\"}", context(TURN));

        assertEquals("季度报告.md", store.findByTurn(TURN).orElseThrow().fileName());
    }

    @Test
    void oversizeFile_isRejectedWithoutRegistering() {
        FileArtifactStore store = new InMemoryFileArtifactStore();
        ToolCallback tool = SendFileTool.callback(fakeShell(2_000_000, new byte[]{1}), store, MAX);

        String result = tool.call("{\"path\":\"big.bin\"}", context(TURN));

        assertTrue(result.contains("超过上限"), result);
        assertTrue(store.findByTurn(TURN).isEmpty(), "超限不得登记：半个文件比没有更误导");
    }

    @Test
    void statFailure_isReportedAsMissing() {
        FileArtifactStore store = new InMemoryFileArtifactStore();
        ToolCallback tool = SendFileTool.callback(command ->
                command.startsWith("stat") ? "stat: cannot stat 'x': No such file" : "", store, MAX);

        String result = tool.call("{\"path\":\"missing.md\"}", context(TURN));

        assertTrue(result.contains("读不到文件"), result);
    }

    @Test
    void emptyFile_isRejected() {
        FileArtifactStore store = new InMemoryFileArtifactStore();
        ToolCallback tool = SendFileTool.callback(fakeShell(0, new byte[0]), store, MAX);

        String result = tool.call("{\"path\":\"empty.md\"}", context(TURN));

        assertTrue(result.contains("文件为空"), result);
        assertTrue(store.findByTurn(TURN).isEmpty());
    }

    @Test
    void pathOutsideWorkspace_isRejected() {
        FileArtifactStore store = new InMemoryFileArtifactStore();
        ToolCallback tool = SendFileTool.callback(fakeShell(2, new byte[]{1, 2}), store, MAX);

        String result = tool.call("{\"path\":\"/etc/passwd\"}", context(TURN));

        assertTrue(result.contains("路径不可用"), result);
        assertTrue(store.findByTurn(TURN).isEmpty());
    }

    @Test
    void missingTurn_isRejected() {
        FileArtifactStore store = new InMemoryFileArtifactStore();
        ToolCallback tool = SendFileTool.callback(fakeShell(2, new byte[]{1, 2}), store, MAX);

        String result = tool.call("{\"path\":\"a.md\"}", new ToolContext(Map.of()));

        assertTrue(result.contains("无法关联本轮回复"), result);
    }

    @Test
    void wrappedBase64_isDecoded() {
        FileArtifactStore store = new InMemoryFileArtifactStore();
        byte[] content = new byte[60];
        for (int i = 0; i < content.length; i++) {
            content[i] = (byte) i;
        }
        // busybox 的 base64 会按 76 列折行：MIME 解码器必须能吃掉换行
        String wrapped = Base64.getMimeEncoder(8, new byte[]{'\n'}).encodeToString(content);
        ToolCallback tool = SendFileTool.callback(command -> command.startsWith("stat")
                ? String.valueOf(content.length)
                : wrapped, store, MAX);

        tool.call("{\"path\":\"a.bin\"}", context(TURN));

        assertEquals(content.length, store.findByTurn(TURN).orElseThrow().content().length);
    }

    @Test
    void parseSize_acceptsOnlyDigits() {
        assertEquals(123L, SendFileTool.parseSize("123\n"));
        assertEquals(-1L, SendFileTool.parseSize("No such file"));
        assertEquals(-1L, SendFileTool.parseSize(""));
        assertEquals(-1L, SendFileTool.parseSize(null));
    }

    @Test
    void fileName_stripsHeaderBreakingChars() {
        Map<String, Object> args = new HashMap<>();
        args.put("name", "a\"b\r\nc.txt");

        assertEquals("abc.txt", SendFileTool.fileName(args, "/workspace/x.md"));
        assertEquals("x.md", SendFileTool.fileName(Map.of(), "/workspace/x.md"));
    }

    /** 假 Shell：stat 返回给定长度，base64 返回内容编码 */
    private static SendFileTool.Shell fakeShell(long size, byte[] content) {
        String encoded = Base64.getEncoder().encodeToString(content);
        return command -> command.startsWith("stat") ? String.valueOf(size) : encoded;
    }

    private static ToolContext context(String turnId) {
        return new ToolContext(Map.of(ToolCallTracer.CTX_TURN_ID, turnId));
    }
}
