package com.dark.javaHarness.tool.external;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.ObjectMapper;

class HttpToolLoaderTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpTransport transport = (url, headers, timeout) ->
            new HttpTransport.Response(200, "{}");

    @TempDir
    Path dir;

    @Test
    void load_parsesManifestAndGroupsByPack() throws Exception {
        write("wms.yaml", """
                pack: wms.query
                base-url: ${HARNESS_TEST_WMS_BASE:}
                tool:
                  name: wms_query
                  description: 查询 WMS
                resources:
                  stock:
                    path: /inventory/list
                    params: [productId, pageNo]
                  detail:
                    path: /goods/queryById
                    params: [id]
                    requires: [id]
                """);

        HttpToolLoader loaded = loader(true);
        List<String> problems = loaded.reload();

        assertTrue(problems.isEmpty(), problems.toString());
        assertEquals(1, loaded.manifests().size());
        assertEquals("wms.query", loaded.manifests().get(0).pack());
        assertEquals(2, loaded.manifests().get(0).resources().size());
        assertEquals(1, loaded.callbacks("wms.query").size(), "pack 名解析为整组");
        assertEquals(1, loaded.callbacks("wms_query").size(), "工具名也能精确点名");
        assertTrue(loaded.callbacks("nope").isEmpty());
    }

    @Test
    void load_badPath_isReportedAndSkipped() throws Exception {
        write("bad.yaml", """
                pack: bad
                tool:
                  name: bad_query
                resources:
                  x:
                    path: inventory/list
                """);

        HttpToolLoader loader = loader(true);
        List<String> problems = loader.reload();

        assertEquals(1, problems.size());
        assertTrue(problems.get(0).contains("path"), problems.get(0));
        assertTrue(loader.manifests().isEmpty());
    }

    @Test
    void load_requiresMustBeSubsetOfParams() throws Exception {
        write("bad.yaml", """
                pack: bad
                tool:
                  name: bad_query
                resources:
                  x:
                    path: /x
                    params: [a]
                    requires: [b]
                """);

        List<String> problems = loader(true).reload();

        assertEquals(1, problems.size());
        assertTrue(problems.get(0).contains("requires"), problems.get(0));
    }

    @Test
    void load_duplicateToolName_isReported() throws Exception {
        write("a.yaml", """
                pack: a
                tool:
                  name: same
                resources:
                  x: { path: /a }
                """);
        write("b.yaml", """
                pack: b
                tool:
                  name: same
                resources:
                  x: { path: /b }
                """);

        HttpToolLoader loader = loader(true);
        List<String> problems = loader.reload();

        assertEquals(1, problems.size());
        assertTrue(problems.get(0).contains("重复"), problems.get(0));
        assertEquals(1, loader.manifests().size(), "后一个被跳过");
    }

    @Test
    void load_missingDirectory_isEmptyWithoutProblems() {
        HttpToolLoader loader = new HttpToolLoader(true, dir.resolve("nope"), false, mapper, transport);

        assertTrue(loader.reload().isEmpty());
        assertTrue(loader.manifests().isEmpty());
    }

    @Test
    void load_disabled_neverScans() throws Exception {
        write("wms.yaml", """
                pack: wms
                tool:
                  name: q
                resources:
                  x: { path: /x }
                """);
        HttpToolLoader loader = new HttpToolLoader(false, dir, false, mapper, transport);

        assertTrue(loader.reload().isEmpty());
        assertTrue(loader.manifests().isEmpty());
    }

    @Test
    void resolve_envPlaceholder_usesDefaultWhenUnset() {
        assertEquals("http://localhost:1", HttpToolLoader.resolve("${DEFINITELY_NOT_SET_ABC:http://localhost:1}"));
        assertEquals("", HttpToolLoader.resolve("${DEFINITELY_NOT_SET_ABC:}"));
        assertEquals("plain", HttpToolLoader.resolve("plain"));
    }

    @Test
    void reload_replacesSnapshot() throws Exception {
        write("wms.yaml", """
                pack: wms
                tool:
                  name: wms_query
                resources:
                  x: { path: /x }
                """);
        HttpToolLoader loader = loader(true);
        AtomicInteger before = new AtomicInteger(loader.reload().size());

        write("wms.yaml", """
                pack: wms
                tool:
                  name: wms_query
                resources:
                  x: { path: /x }
                  y: { path: /y }
                """);
        loader.reload();

        assertEquals(0, before.get());
        assertEquals(2, loader.manifests().get(0).resources().size(), "重载后读到新内容");
    }

    private HttpToolLoader loader(boolean enabled) {
        return new HttpToolLoader(enabled, dir, false, mapper, transport);
    }

    private void write(String name, String content) throws Exception {
        Files.writeString(dir.resolve(name), content, StandardCharsets.UTF_8);
    }
}
