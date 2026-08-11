package com.mockplayer.session;

import com.mockplayer.api.Bot;
import com.mockplayer.api.MockplayerApi;
import com.mockplayer.config.MockplayerConfig;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * EventRecorder 注册表（/query listen 与 memory 估算共用）。
 *
 * 配置保存/热重载时重建所有已挂 recorder 并重新挂到 bot 事件总线，
 * 保证缓存大小/采样参数即时生效（旧实现只在监听开始时快照配置）。
 */
public final class EventRecorderRegistry {

    private static final Map<String, EventRecorder> RECORDERS = new ConcurrentHashMap<>();

    static {
        MockplayerConfig.onReload(EventRecorderRegistry::recreateAll);
    }

    private EventRecorderRegistry() {
    }

    /** 获取或创建 recorder（listen on 路径）。 */
    public static EventRecorder computeIfAbsent(String name, Function<String, EventRecorder> factory) {
        return RECORDERS.computeIfAbsent(name, factory);
    }

    /** 移除 recorder（listen off 路径；返回被移除的实例，未监听返回 null）。 */
    public static EventRecorder remove(String name) {
        return RECORDERS.remove(name);
    }

    /** 当前监听的 recorder（查询/测试/memory 估算用；未监听返回 null）。 */
    public static EventRecorder get(String name) {
        return RECORDERS.get(name);
    }

    /** 配置变更后重建全部已挂 recorder（旧实例先摘除，新实例按新配置重建并挂回）。 */
    private static void recreateAll() {
        for (Map.Entry<String, EventRecorder> entry : RECORDERS.entrySet()) {
            String name = entry.getKey();
            EventRecorder old = entry.getValue();
            Bot bot = MockplayerApi.bots().getBot(name).orElse(null);
            if (!(bot instanceof BotImpl impl)) {
                continue; // bot 已删：条目由 listen off / 删除清理路径处理
            }
            EventRecorder fresh = new EventRecorder(name);
            impl.events().removeListener(old);
            impl.events().addListener(fresh);
            RECORDERS.put(name, fresh);
        }
    }
}
