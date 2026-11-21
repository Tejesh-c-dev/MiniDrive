package com.minidrive.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

@ConfigurationProperties(prefix = "minidrive.rate-limit")
@Component
public class RateLimitProperties {

    private Bucket loginIp = new Bucket(10, Duration.ofMinutes(15));
    private Bucket loginAccount = new Bucket(5, Duration.ofMinutes(15));
    private Bucket registerIp = new Bucket(5, Duration.ofHours(1));
    private Bucket registerAccount = new Bucket(3, Duration.ofDays(1));
    private Bucket shareIp = new Bucket(60, Duration.ofMinutes(1));
    private Bucket shareToken = new Bucket(30, Duration.ofMinutes(1));
    private List<String> trustedProxies = new ArrayList<>();

    public Bucket getLoginIp() { return loginIp; }
    public void setLoginIp(Bucket value) { loginIp = value; }
    public Bucket getLoginAccount() { return loginAccount; }
    public void setLoginAccount(Bucket value) { loginAccount = value; }
    public Bucket getRegisterIp() { return registerIp; }
    public void setRegisterIp(Bucket value) { registerIp = value; }
    public Bucket getRegisterAccount() { return registerAccount; }
    public void setRegisterAccount(Bucket value) { registerAccount = value; }
    public Bucket getShareIp() { return shareIp; }
    public void setShareIp(Bucket value) { shareIp = value; }
    public Bucket getShareToken() { return shareToken; }
    public void setShareToken(Bucket value) { shareToken = value; }
    public List<String> getTrustedProxies() { return trustedProxies; }
    public void setTrustedProxies(List<String> value) { trustedProxies = value; }

    public static class Bucket {
        private int limit;
        private Duration window;

        public Bucket() { }
        public Bucket(int limit, Duration window) {
            this.limit = limit;
            this.window = window;
        }
        public int getLimit() { return limit; }
        public void setLimit(int limit) { this.limit = limit; }
        public Duration getWindow() { return window; }
        public void setWindow(Duration window) { this.window = window; }
    }
}
