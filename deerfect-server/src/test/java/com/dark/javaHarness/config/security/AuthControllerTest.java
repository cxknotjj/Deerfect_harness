package com.dark.javaHarness.config.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * 登录端点单测：口令换 Cookie（HttpOnly/SameSite=Lax/Max-Age）、错口令 401、
 * 按 IP 连败锁定 429、登录态探测 /me、登出吊销、未启用登录 403。
 */
class AuthControllerTest {

    private static final String PASSWORD = "p@ss-word";

    private MockMvc mvc(String password, int maxFailures, long lockSeconds) {
        LoginStateStore store = new LoginStateStore(Duration.ofHours(1));
        return MockMvcBuilders.standaloneSetup(
                new AuthController(password, 1, maxFailures, lockSeconds, store)).build();
    }

    /** 正确口令：200 + Set-Cookie（HttpOnly + SameSite=Lax + Path=/ + Max-Age=3600） */
    @Test
    void login_correctPassword_setsCookie() throws Exception {
        MvcResult result = mvc(PASSWORD, 5, 60).perform(post("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"password\":\"" + PASSWORD + "\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(0))
            .andReturn();
        String setCookie = result.getResponse().getHeader("Set-Cookie");
        assertThat(setCookie).isNotNull();
        assertThat(setCookie).startsWith(AuthController.COOKIE_NAME + "=");
        assertThat(setCookie).contains("HttpOnly", "SameSite=Lax", "Path=/", "Max-Age=3600");
    }

    /** 错误口令：401，不发 Cookie */
    @Test
    void login_wrongPassword_returns401() throws Exception {
        mvc(PASSWORD, 5, 60).perform(post("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"password\":\"nope\"}"))
            .andExpect(status().isUnauthorized())
            .andReturn();
    }

    /** 未配置口令：登录端点 403（仅机器通道部署形态） */
    @Test
    void login_notConfigured_returns403() throws Exception {
        mvc("", 5, 60).perform(post("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"password\":\"x\"}"))
            .andExpect(status().isForbidden())
            .andReturn();
    }

    /** 按 IP 连败锁定：连续失败达到阈值后 429，期间即使口令正确也拒绝 */
    @Test
    void login_tooManyFailures_locksIp() throws Exception {
        MockMvc mvc = mvc(PASSWORD, 3, 60);
        for (int i = 0; i < 3; i++) {
            mvc.perform(post("/api/auth/login")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"password\":\"wrong-" + i + "\"}"))
                .andExpect(status().isUnauthorized());
        }
        mvc.perform(post("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"password\":\"" + PASSWORD + "\"}"))
            .andExpect(status().isTooManyRequests());
    }

    /** 登录成功重置失败计数：失败未达阈值后成功，再失败一轮不叠加 429 */
    @Test
    void login_success_resetsFailureCount() throws Exception {
        MockMvc mvc = mvc(PASSWORD, 3, 60);
        for (int i = 0; i < 2; i++) {
            mvc.perform(post("/api/auth/login")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"password\":\"wrong\"}"))
                .andExpect(status().isUnauthorized());
        }
        mvc.perform(post("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"password\":\"" + PASSWORD + "\"}"))
            .andExpect(status().isOk());
        for (int i = 0; i < 2; i++) {
            mvc.perform(post("/api/auth/login")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"password\":\"wrong-again\"}"))
                .andExpect(status().isUnauthorized());
        }
        // 重置生效：2 < 3，不应 429
        mvc.perform(post("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"password\":\"wrong-final\"}"))
            .andExpect(status().isUnauthorized());
    }

    /** /me 探测：未启用登录 → enabled=false；启用未登录 → enabled=true, authenticated=false */
    @Test
    void me_reportsEnabledAndAuthenticated() throws Exception {
        mvc("", 5, 60).perform(get("/api/auth/me"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.enabled").value(false))
            .andExpect(jsonPath("$.authenticated").value(false));

        mvc(PASSWORD, 5, 60).perform(get("/api/auth/me"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.enabled").value(true))
            .andExpect(jsonPath("$.authenticated").value(false));
    }

    /** /me 探测：携带有效登录 Cookie → authenticated=true */
    @Test
    void me_withValidCookie_authenticated() throws Exception {
        MockMvc mvc = mvc(PASSWORD, 5, 60);
        MvcResult login = mvc.perform(post("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"password\":\"" + PASSWORD + "\"}"))
            .andReturn();
        String cookie = login.getResponse().getHeader("Set-Cookie").split(";", 2)[0];
        mvc.perform(get("/api/auth/me").cookie(new jakarta.servlet.http.Cookie(AuthController.COOKIE_NAME,
                cookie.substring(cookie.indexOf('=') + 1))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.authenticated").value(true));
    }

    /** 登出：吊销登录态 + 下发清除 Cookie，其后 /me 报未认证 */
    @Test
    void logout_revokesAndClearsCookie() throws Exception {
        MockMvc mvc = mvc(PASSWORD, 5, 60);
        MvcResult login = mvc.perform(post("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"password\":\"" + PASSWORD + "\"}"))
            .andReturn();
        String setCookie = login.getResponse().getHeader("Set-Cookie");
        String value = setCookie.split(";", 2)[0].substring((AuthController.COOKIE_NAME + "=").length());

        MvcResult logout = mvc.perform(post("/api/auth/logout")
                .cookie(new jakarta.servlet.http.Cookie(AuthController.COOKIE_NAME, value)))
            .andExpect(status().isOk())
            .andReturn();
        assertThat(logout.getResponse().getHeader("Set-Cookie")).contains("Max-Age=0");

        mvc.perform(get("/api/auth/me").cookie(new jakarta.servlet.http.Cookie(AuthController.COOKIE_NAME, value)))
            .andExpect(jsonPath("$.authenticated").value(false));
    }
}
