package com.dark.javaHarness.channel.email;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 邮件渠道配置（{@code channel.email.*}）。
 *
 * <p>为什么不复用 {@code spring.mail.*}：那是 Spring Boot 的全局命名空间，会让
 * {@code MailSenderAutoConfiguration} 隐式装配一个 Bean——「这个 JavaMailSender 从哪来」
 * 变得需要翻文档才知道。渠道配置统一放在 {@code channel.*} 下，与 {@code channel.feishu.*}
 * 对称，装配也由本模块自己的 {@link EmailChannelConfig} 显式完成。
 *
 * <p>凭据走环境变量（{@code MAIL_USERNAME} / {@code MAIL_PASSWORD}），不落 git。
 * {@code enabled=false} 时整条链路不装配，{@code MessageSenderRegistry} 的渠道列表里
 * 也不会出现 {@code email}——「没配就别喊」比「配了但发不出去」好排查。
 */
@Component
@ConfigurationProperties(prefix = "channel.email")
public class EmailProperties {

    /** 渠道总开关（false 时无 Bean、无渠道注册） */
    private boolean enabled;

    /** SMTP 服务器（如 smtp.qq.com / smtpdm.aliyun.com） */
    private String host;

    /** SMTP 端口（465 = SSL，587 = STARTTLS） */
    private int port = 465;

    /** 发件人地址（多数服务商要求与 username 一致） */
    private String from;

    /** SMTP 用户名（通常就是邮箱地址） */
    private String username;

    /** SMTP 密码 / 授权码（走环境变量，不落 git） */
    private String password;

    /** 是否使用 SSL（465 端口为 true） */
    private boolean ssl = true;

    /** 连接与读超时（毫秒）：SMTP 挂起时不能把投递线程按住太久 */
    private int timeoutMs = 10_000;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getHost() {
        return host;
    }

    public void setHost(String host) {
        this.host = host;
    }

    public int getPort() {
        return port;
    }

    public void setPort(int port) {
        this.port = port;
    }

    public String getFrom() {
        return from;
    }

    public void setFrom(String from) {
        this.from = from;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = password;
    }

    public boolean isSsl() {
        return ssl;
    }

    public void setSsl(boolean ssl) {
        this.ssl = ssl;
    }

    public int getTimeoutMs() {
        return timeoutMs;
    }

    public void setTimeoutMs(int timeoutMs) {
        this.timeoutMs = timeoutMs;
    }
}
