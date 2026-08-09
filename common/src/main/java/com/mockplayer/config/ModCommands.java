package com.mockplayer.config;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 本 mod 根命令名配置（字段名固定，值可改/可禁用）。
 *
 * 输入：config/mockplayer.json 的 commands 对象
 * 语义：
 * - 字段缺失 / null → 默认名
 * - trim 后为空 → 禁用（""，不注册）
 * - 含空白 / 超长 / 非法字符 → 回退默认名（不是禁用）
 * - 启用项之间重名 → 整组回退默认（禁用项不参与重名检查）
 */
public final class ModCommands {

    public static final String CONTROL = "control";
    public static final String QUERY = "query";
    public static final String NEWPLAYER = "newplayer";
    public static final String DELPLAYER = "delplayer";
    public static final String CONNECT = "connect";

    /** 固定字段顺序（写文件也按此顺序，方便手改）。 */
    public static final List<String> ALL = List.of(CONTROL, QUERY, NEWPLAYER, DELPLAYER, CONNECT);

    public static final int MAX_LENGTH = 32;

    private ModCommands() {
    }

    /** 默认命令名（= 现在的名字，升级零影响）。 */
    public static Map<String, String> defaults() {
        Map<String, String> map = new LinkedHashMap<>();
        for (String key : ALL) {
            map.put(key, key);
        }
        return map;
    }

    /** 规范化整组命令名；启用项之间重名时整组回退默认。 */
    public static Map<String, String> normalize(Map<String, String> raw) {
        if (raw == null) {
            return defaults();
        }
        Map<String, String> normalized = new LinkedHashMap<>();
        for (String key : ALL) {
            normalized.put(key, normalizeOne(key, raw.get(key)));
        }
        Set<String> enabled = new HashSet<>();
        for (String key : ALL) {
            String name = normalized.get(key);
            if (!isDisabled(name) && !enabled.add(name)) {
                return defaults();
            }
        }
        return normalized;
    }

    /** 单条命令名规范化：null → 默认；trim 空 → 禁用；非法 → 默认。 */
    private static String normalizeOne(String key, String value) {
        if (value == null) {
            return key;
        }
        String trimmed = value.trim();
        if (trimmed.isEmpty()) {
            return "";
        }
        if (trimmed.length() > MAX_LENGTH) {
            return key;
        }
        for (int i = 0; i < trimmed.length(); i++) {
            char c = trimmed.charAt(i);
            boolean valid = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')
                    || (c >= '0' && c <= '9') || c == '_' || c == '-' || c == '.';
            if (!valid) {
                return key;
            }
        }
        return trimmed;
    }

    /** 是否禁用（null / 空白 = 禁用）。 */
    public static boolean isDisabled(String name) {
        return name == null || name.isBlank();
    }
}
