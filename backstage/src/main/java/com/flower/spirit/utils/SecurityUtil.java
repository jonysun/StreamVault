package com.flower.spirit.utils;

import java.util.regex.Pattern;
import jakarta.servlet.http.HttpServletRequest;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.time.LocalDateTime;

public class SecurityUtil {
    private static final ConcurrentHashMap<String, AtomicInteger> loginAttempts = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, LocalDateTime> ipBlockTime = new ConcurrentHashMap<>();
    private static final int MAX_LOGIN_ATTEMPT_IPS = 10000;

    // XSS字符转义
    public static String escapeXSS(String input) {
        if (input == null) {
            return null;
        }
        return input.replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&#x27;")
                .replace("&", "&amp;")
                .replace("/", "&#x2F;");
    }

    // SQL注入检查
    public static boolean containsSQLInjection(String input) {
        if (input == null) {
            return false;
        }

        // SQL注入特征pattern
        String sqlPattern = "(?i)(select|update|delete|insert|drop|union|exec|execute|call|xp_|sp_|'|;|--)";
        return Pattern.compile(sqlPattern).matcher(input).find();
    }

    // 登录失败次数检查
    public static boolean isLoginAllowed(String ip) {
        cleanupExpiredBlocks();
        LocalDateTime blockedUntil = ipBlockTime.get(ip);
        if (blockedUntil != null && blockedUntil.isAfter(LocalDateTime.now())) {
            return false;
        }
        if (blockedUntil != null) {
            resetLoginAttempts(ip);
        }

        AtomicInteger attempts = loginAttempts.computeIfAbsent(ip, k -> new AtomicInteger(0));
        return attempts.get() < 5; // 允许5次尝试
    }

    // 记录登录失败
    public static void recordLoginFailure(String ip) {
        cleanupExpiredBlocks();
        if (!loginAttempts.containsKey(ip) && loginAttempts.size() >= MAX_LOGIN_ATTEMPT_IPS) {
            return;
        }
        AtomicInteger attempts = loginAttempts.computeIfAbsent(ip, k -> new AtomicInteger(0));
        if (attempts.incrementAndGet() >= 5) {
            // 超过5次失败，锁定15分钟
            ipBlockTime.put(ip, LocalDateTime.now().plusMinutes(15));
        }
    }

    // 重置登录失败次数
    public static void resetLoginAttempts(String ip) {
        loginAttempts.remove(ip);
        ipBlockTime.remove(ip);
    }

    private static void cleanupExpiredBlocks() {
        LocalDateTime now = LocalDateTime.now();
        ipBlockTime.entrySet().removeIf(entry -> {
            boolean expired = !entry.getValue().isAfter(now);
            if (expired) {
                loginAttempts.remove(entry.getKey());
            }
            return expired;
        });
    }

    // 验证用户名格式
    public static boolean isValidUsername(String username) {
        if (username == null || username.length() < 3 || username.length() > 20) {
            return false;
        }
        // 只允许字母、数字和下划线
        return Pattern.compile("^[a-zA-Z0-9_]+$").matcher(username).matches();
    }
}
