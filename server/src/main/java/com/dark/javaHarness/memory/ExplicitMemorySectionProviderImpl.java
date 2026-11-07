package com.dark.javaHarness.memory;

import com.dark.javaHarness.domain.Memory;
import com.dark.javaHarness.prompt.ExplicitMemorySectionProvider;
import com.dark.javaHarness.prompt.MemoryPolicy;
import com.dark.javaHarness.service.MemoryService;
import java.time.format.DateTimeFormatter;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * 显式记忆段实现：把有效记忆渲染成 system 的【用户明确记住的事】段。
 *
 * <p>注入口径与画像一致（{@link MemoryPolicy}：general/lead）；段落次序在画像之前，
 * 并在段末声明「冲突时以显式记忆为准」——用户说「记住我的技术栈是 Go」之后，
 * 画像里旧推断出的 Java 不该继续压着这条事实。
 *
 * <p>全量注入 + 上限截断：记忆量小（个人几十条），按相关度检索的复杂度暂时不值当。
 * 超过 {@link MemoryService#MAX_INJECTION_ITEMS} 条或字符上限时按最新截断并注明剩余条数。
 */
@Component
public class ExplicitMemorySectionProviderImpl implements ExplicitMemorySectionProvider {

    private static final String SECTION_TITLE = "【用户明确记住的事】";

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    private final MemoryService memoryService;
    private final MemoryPolicy memoryPolicy = new MemoryPolicy();

    public ExplicitMemorySectionProviderImpl(MemoryService memoryService) {
        this.memoryService = memoryService;
    }

    @Override
    public String provide(String agentName) {
        if (!memoryPolicy.shouldInject(agentName)) {
            return null;
        }
        List<Memory> memories = memoryService.activeForInjection();
        if (memories.isEmpty()) {
            return null;
        }
        StringBuilder sb = new StringBuilder(SECTION_TITLE).append('\n');
        int used = 0;
        int included = 0;
        for (Memory memory : memories) {
            String line = render(memory);
            if (used + line.length() > MemoryService.MAX_INJECTION_CHARS) {
                break;
            }
            sb.append(line).append('\n');
            used += line.length();
            included++;
        }
        if (included == 0) {
            return null;
        }
        if (memories.size() > included) {
            sb.append("（另有 ").append(memories.size() - included).append(" 条记忆未展示）\n");
        }
        sb.append("以上是用户明确要求记住的事实，优先于画像推断；与画像冲突时以此为准。")
                .append("用户说「忘掉」时用 forget 工具删除，不要自行忽略。");
        return sb.toString();
    }

    private static String render(Memory memory) {
        StringBuilder line = new StringBuilder("- ");
        if (memory.key() != null) {
            line.append('[').append(memory.key()).append("] ");
        }
        line.append(memory.content());
        if (memory.createdAt() != null) {
            line.append("（").append(DATE.format(memory.createdAt())).append(" 记）");
        }
        return line.toString();
    }
}
