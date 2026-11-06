package com.dark.javaHarness.contract;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.dark.javaHarness.domain.Goal;
import com.dark.javaHarness.service.AgentService;
import com.dark.javaHarness.service.SessionService;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/**
 * 高扇入基座契约快照（principal §5：契约与行为测试为主）。
 *
 * <p>{@link AgentService}（25 扇入）、{@link SessionService}（23 扇入）、{@link Goal}（20 扇入）
 * 任一公开签名变化都会波及 server / channel-qq / cli 的大量调用方。本测试固化「公开构造器 +
 * 公开方法（含继承的 ChatMemory 端口）擦除签名」，改动时必须同步更新调用方与本快照。
 *
 * <p>失败信息会指明受影响模块与变化项，便于定位。
 */
class PortSignatureGuardTest {

    private static final String AFFECTED = "受影响模块：server / channel-qq / cli / harness-api";

    @Test
    void agentService_signatureSnapshot() {
        assertEquals(new TreeSet<>(Set.of(
                        "submit(String,String):Goal",
                        "executeSync(String,String):Goal",
                        "executeSync(String,String,String,String):Goal",
                        "executeSync(String,String,String,String,String):Goal",
                        "executeSync(String,String,String,String,String,String):Goal",
                        "executeStreamReactive(String,String,String,String):Flux",
                        "executeStreamReactive(String,String,String,String,String):Flux",
                        "executeStreamReactive(String,String,String,String,String,String):Flux",
                        "resumeStreamReactive(Goal):Flux",
                        "retry(String):boolean",
                        "agentNames():Set",
                        "agentItems():List",
                        "findAgentNameById(Long):Optional",
                        "getAgentConfig(String):Optional")),
                signatures(AgentService.class),
                "AgentService 公开签名发生变化。" + AFFECTED);
    }

    @Test
    void sessionService_signatureSnapshot() {
        assertEquals(new TreeSet<>(Set.of(
                        "createSession(String,String):String",
                        "loadContext(String):List",
                        "listMessages(String):List",
                        "snapshotItems(String):List",
                        "saveContext(String,Message):void",
                        "saveContext(String,Message,Long):void",
                        "saveContextBatch(String,List):void",
                        "touchSession(String,String):void",
                        "getSession(String):SessionEntity",
                        "switchAgent(String,Long):void",
                        "page(long,long):Page",
                        "deleteSession(String):void",
                        // ChatMemory 端口（Spring AI 继承）
                        "add(String,List):void",
                        "add(String,Message):void",
                        "get(String):List",
                        "clear(String):void")),
                signatures(SessionService.class),
                "SessionService 公开签名（含 ChatMemory 端口）发生变化。" + AFFECTED);
    }

    @Test
    void goal_signatureSnapshot() {
        Set<String> expected = new TreeSet<>(Set.of(
                "<init>(String,String)",
                "<init>(String,String,String)",
                "<init>(String,String,String,String,String)",
                "<init>(String,String,String,String,String,String)",
                "<init>(String,String,String,String,String,String,String)",
                "<init>(String,String,String,GoalStatus,LocalDateTime,LocalDateTime,String,String,String,String,LocalDateTime,String,int,LocalDateTime)",
                "<init>(String,String,String,GoalStatus,LocalDateTime,LocalDateTime,String,String,String,String,LocalDateTime)",
                "markRunning():void",
                "touchProgress():void",
                "succeed(String):void",
                "fail(String):void",
                "id():String",
                "objective():String",
                "sessionId():String",
                "turnId():String",
                "traceId():String",
                "channel():String",
                "status():GoalStatus",
                "createdAt():LocalDateTime",
                "finishedAt():LocalDateTime",
                "summary():String",
                "lastProgressAt():LocalDateTime",
                "agentName():String",
                "retryCount():int",
                "nextRetryAt():LocalDateTime",
                "canRetry(int):boolean",
                "scheduleRetry(LocalDateTime):void",
                "markRetry():void"));

        assertEquals(expected, signatures(Goal.class), "Goal 公开构造器/访问器发生变化。" + AFFECTED);
    }

    /** 公开方法（含接口继承，排除 Object）与公开构造器的擦除签名集合 */
    private static Set<String> signatures(Class<?> type) {
        Set<String> out = new TreeSet<>();
        for (Method m : type.getMethods()) {
            if (m.getDeclaringClass() == Object.class || m.isSynthetic()
                    || !Modifier.isPublic(m.getModifiers())) {
                continue;
            }
            out.add(m.getName() + "(" + params(m.getParameterTypes()) + "):"
                    + m.getReturnType().getSimpleName());
        }
        for (Constructor<?> c : type.getDeclaredConstructors()) {
            if (c.isSynthetic() || !Modifier.isPublic(c.getModifiers())) {
                continue;
            }
            out.add("<init>(" + params(c.getParameterTypes()) + ")");
        }
        return out;
    }

    private static String params(Class<?>[] types) {
        return Arrays.stream(types)
                .map(Class::getSimpleName)
                .filter(Objects::nonNull)
                .collect(Collectors.joining(","));
    }
}
