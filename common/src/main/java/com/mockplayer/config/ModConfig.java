package com.mockplayer.config;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Mockplayer 客户端配置（纯数据类，零第三方依赖）。
 *
 * 输入：config/mockplayer.json（玩家可手改，缺字段/非法值由 ModConfigIO 回退默认）
 * 输出：运行时常量（FakePlayerState 日志上限、EventRecorder 事件缓存/采样参数）
 *
 * 默认值与历史硬编码常量一致，保证升级后行为不变。
 */
public class ModConfig {

    /** 聊天历史保留条数。 */
    public static final int DEFAULT_CHAT_HISTORY_LIMIT = 200;
    public static final int MIN_CHAT_HISTORY_LIMIT = 10;
    public static final int MAX_CHAT_HISTORY_LIMIT = 1000;

    /** 音效日志保留条数。 */
    public static final int DEFAULT_SOUND_LOG_LIMIT = 20;
    public static final int MIN_SOUND_LOG_LIMIT = 10;
    public static final int MAX_SOUND_LOG_LIMIT = 500;

    /** 粒子日志保留条数。 */
    public static final int DEFAULT_PARTICLE_LOG_LIMIT = 20;
    public static final int MIN_PARTICLE_LOG_LIMIT = 10;
    public static final int MAX_PARTICLE_LOG_LIMIT = 500;

    /** /query listen on 的事件环形缓存条数。 */
    public static final int DEFAULT_EVENT_CACHE_SIZE = 50;
    public static final int MIN_EVENT_CACHE_SIZE = 10;
    public static final int MAX_EVENT_CACHE_SIZE = 500;

    /** 事件推送摘要最大长度（防刷屏）。 */
    public static final int DEFAULT_EVENT_SUMMARY_MAX_LENGTH = 120;
    public static final int MIN_EVENT_SUMMARY_MAX_LENGTH = 20;
    public static final int MAX_EVENT_SUMMARY_MAX_LENGTH = 500;

    /** onTick 高频事件采样间隔（tick）。 */
    public static final int DEFAULT_EVENT_TICK_SAMPLE_INTERVAL = 20;
    public static final int MIN_EVENT_TICK_SAMPLE_INTERVAL = 1;
    public static final int MAX_EVENT_TICK_SAMPLE_INTERVAL = 200;

    /** onMove 高频事件采样位移阈值（方块）。 */
    public static final double DEFAULT_EVENT_MOVE_SAMPLE_DISTANCE = 0.5;
    public static final double MIN_EVENT_MOVE_SAMPLE_DISTANCE = 0.1;
    public static final double MAX_EVENT_MOVE_SAMPLE_DISTANCE = 10.0;

    private int chatHistoryLimit = DEFAULT_CHAT_HISTORY_LIMIT;
    private int soundLogLimit = DEFAULT_SOUND_LOG_LIMIT;
    private int particleLogLimit = DEFAULT_PARTICLE_LOG_LIMIT;
    private int eventCacheSize = DEFAULT_EVENT_CACHE_SIZE;
    private int eventSummaryMaxLength = DEFAULT_EVENT_SUMMARY_MAX_LENGTH;
    private int eventTickSampleInterval = DEFAULT_EVENT_TICK_SAMPLE_INTERVAL;
    private double eventMoveSampleDistance = DEFAULT_EVENT_MOVE_SAMPLE_DISTANCE;
    /** 根命令名配置（字段固定，值可改/可禁用；默认 = 现在的名字）。 */
    private Map<String, String> commands = new LinkedHashMap<>(ModCommands.defaults());
    /** F3 调试信息打开时，在假人名字下方显示额外信息（血量/饱食度/内存/速度/容器）。 */
    private boolean debugOverlayEnabled = true;

    /** GUI 功能总开关（默认启用；关闭后按键/快捷键不打开 BotControlScreen，命令不受影响）。 */
    public static final boolean DEFAULT_GUI_ENABLED = true;
    /** GUI 打开按键（GLFW key name，如 key.keyboard.g；空串 = 禁用）。 */
    public static final String DEFAULT_GUI_KEY_NAME = "key.keyboard.g";
    public static final int MAX_GUI_KEY_NAME_LENGTH = 64;

    private boolean guiEnabled = DEFAULT_GUI_ENABLED;
    private String guiKeyName = DEFAULT_GUI_KEY_NAME;

    /** 假人默认区块加载半径（节约性能：默认最低 2，范围 1-32）。 */
    public static final int DEFAULT_FAKE_PLAYER_CHUNK_RADIUS = 2;
    public static final int MIN_FAKE_PLAYER_CHUNK_RADIUS = 1;
    public static final int MAX_FAKE_PLAYER_CHUNK_RADIUS = 32;

    private int fakePlayerChunkRadius = DEFAULT_FAKE_PLAYER_CHUNK_RADIUS;

    /** 单次批量创建假人数量上限（性能测试保护）。 */
    public static final int DEFAULT_BATCH_MAX_COUNT = 100;
    public static final int MIN_BATCH_MAX_COUNT = 1;
    public static final int MAX_BATCH_MAX_COUNT = 1000;

    private int batchMaxCount = DEFAULT_BATCH_MAX_COUNT;

    public int getChatHistoryLimit() {
        return this.chatHistoryLimit;
    }

    public void setChatHistoryLimit(int chatHistoryLimit) {
        this.chatHistoryLimit = chatHistoryLimit;
    }

    public int getSoundLogLimit() {
        return this.soundLogLimit;
    }

    public void setSoundLogLimit(int soundLogLimit) {
        this.soundLogLimit = soundLogLimit;
    }

    public int getParticleLogLimit() {
        return this.particleLogLimit;
    }

    public void setParticleLogLimit(int particleLogLimit) {
        this.particleLogLimit = particleLogLimit;
    }

    public int getEventCacheSize() {
        return this.eventCacheSize;
    }

    public void setEventCacheSize(int eventCacheSize) {
        this.eventCacheSize = eventCacheSize;
    }

    public int getEventSummaryMaxLength() {
        return this.eventSummaryMaxLength;
    }

    public void setEventSummaryMaxLength(int eventSummaryMaxLength) {
        this.eventSummaryMaxLength = eventSummaryMaxLength;
    }

    public int getEventTickSampleInterval() {
        return this.eventTickSampleInterval;
    }

    public void setEventTickSampleInterval(int eventTickSampleInterval) {
        this.eventTickSampleInterval = eventTickSampleInterval;
    }

    public double getEventMoveSampleDistance() {
        return this.eventMoveSampleDistance;
    }

    public void setEventMoveSampleDistance(double eventMoveSampleDistance) {
        this.eventMoveSampleDistance = eventMoveSampleDistance;
    }

    /** 根命令名配置（只读视图；修改用 setCommandName / setCommands）。 */
    public Map<String, String> getCommands() {
        return Collections.unmodifiableMap(this.commands);
    }

    /** 取某个根命令名（缺失回默认；空串 = 禁用，见 {@link ModCommands#isDisabled}）。 */
    public String getCommandName(String key) {
        String name = this.commands.get(key);
        return name != null ? name : ModCommands.defaults().get(key);
    }

    public void setCommandName(String key, String name) {
        Map<String, String> copy = new LinkedHashMap<>(this.commands);
        copy.put(key, name);
        this.commands = copy;
    }

    public void setCommands(Map<String, String> commands) {
        this.commands = commands != null
                ? new LinkedHashMap<>(commands)
                : new LinkedHashMap<>(ModCommands.defaults());
    }

    public boolean isDebugOverlayEnabled() {
        return this.debugOverlayEnabled;
    }

    public void setDebugOverlayEnabled(boolean debugOverlayEnabled) {
        this.debugOverlayEnabled = debugOverlayEnabled;
    }

    public boolean isGuiEnabled() {
        return this.guiEnabled;
    }

    public void setGuiEnabled(boolean guiEnabled) {
        this.guiEnabled = guiEnabled;
    }

    public String getGuiKeyName() {
        return this.guiKeyName;
    }

    public void setGuiKeyName(String guiKeyName) {
        this.guiKeyName = guiKeyName;
    }

    /** GUI 按键名规范化：null → 默认；trim 空 → 禁用；非法字符/超长 → 默认。 */
    static String normalizeGuiKeyName(String value) {
        if (value == null) {
            return DEFAULT_GUI_KEY_NAME;
        }
        String trimmed = value.trim();
        if (trimmed.isEmpty()) {
            return "";
        }
        if (trimmed.length() > MAX_GUI_KEY_NAME_LENGTH) {
            return DEFAULT_GUI_KEY_NAME;
        }
        for (int i = 0; i < trimmed.length(); i++) {
            char c = trimmed.charAt(i);
            boolean valid = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')
                    || (c >= '0' && c <= '9') || c == '_' || c == '-' || c == '.';
            if (!valid) {
                return DEFAULT_GUI_KEY_NAME;
            }
        }
        return trimmed;
    }

    public int getFakePlayerChunkRadius() {
        return this.fakePlayerChunkRadius;
    }

    public void setFakePlayerChunkRadius(int fakePlayerChunkRadius) {
        this.fakePlayerChunkRadius = fakePlayerChunkRadius;
    }

    public int getBatchMaxCount() {
        return this.batchMaxCount;
    }

    public void setBatchMaxCount(int batchMaxCount) {
        this.batchMaxCount = batchMaxCount;
    }

    /** 越界字段回退默认（非法值不保留，保证配置文件永远可手改且不崩）。 */
    public void normalize() {
        this.chatHistoryLimit = clampInt(this.chatHistoryLimit,
                MIN_CHAT_HISTORY_LIMIT, MAX_CHAT_HISTORY_LIMIT, DEFAULT_CHAT_HISTORY_LIMIT);
        this.soundLogLimit = clampInt(this.soundLogLimit,
                MIN_SOUND_LOG_LIMIT, MAX_SOUND_LOG_LIMIT, DEFAULT_SOUND_LOG_LIMIT);
        this.particleLogLimit = clampInt(this.particleLogLimit,
                MIN_PARTICLE_LOG_LIMIT, MAX_PARTICLE_LOG_LIMIT, DEFAULT_PARTICLE_LOG_LIMIT);
        this.eventCacheSize = clampInt(this.eventCacheSize,
                MIN_EVENT_CACHE_SIZE, MAX_EVENT_CACHE_SIZE, DEFAULT_EVENT_CACHE_SIZE);
        this.eventSummaryMaxLength = clampInt(this.eventSummaryMaxLength,
                MIN_EVENT_SUMMARY_MAX_LENGTH, MAX_EVENT_SUMMARY_MAX_LENGTH, DEFAULT_EVENT_SUMMARY_MAX_LENGTH);
        this.eventTickSampleInterval = clampInt(this.eventTickSampleInterval,
                MIN_EVENT_TICK_SAMPLE_INTERVAL, MAX_EVENT_TICK_SAMPLE_INTERVAL, DEFAULT_EVENT_TICK_SAMPLE_INTERVAL);
        this.eventMoveSampleDistance = clampDouble(this.eventMoveSampleDistance,
                MIN_EVENT_MOVE_SAMPLE_DISTANCE, MAX_EVENT_MOVE_SAMPLE_DISTANCE, DEFAULT_EVENT_MOVE_SAMPLE_DISTANCE);
        this.fakePlayerChunkRadius = clampInt(this.fakePlayerChunkRadius,
                MIN_FAKE_PLAYER_CHUNK_RADIUS, MAX_FAKE_PLAYER_CHUNK_RADIUS, DEFAULT_FAKE_PLAYER_CHUNK_RADIUS);
        this.batchMaxCount = clampInt(this.batchMaxCount,
                MIN_BATCH_MAX_COUNT, MAX_BATCH_MAX_COUNT, DEFAULT_BATCH_MAX_COUNT);
        this.commands = ModCommands.normalize(this.commands);
        this.guiKeyName = normalizeGuiKeyName(this.guiKeyName);
    }

    private static int clampInt(int value, int min, int max, int fallback) {
        return value >= min && value <= max ? value : fallback;
    }

    private static double clampDouble(double value, double min, double max, double fallback) {
        return value >= min && value <= max ? value : fallback;
    }
}
