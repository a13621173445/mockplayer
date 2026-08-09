package com.mockplayer.config;

import net.minecraft.client.Minecraft;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 运行期配置持有者（懒加载单例）。
 *
 * 输入：Minecraft 游戏目录下的 config/mockplayer.json
 * 输出：FakePlayerState / EventRecorder / ModConfigScreen 共享的当前配置实例
 *
 * 纯客户端使用（Minecraft 实例在客户端启动后必然存在）。
 */
public final class MockplayerConfig {

    private static final Logger LOGGER = LoggerFactory.getLogger(MockplayerConfig.class);

    private static volatile Path path;
    private static volatile ModConfig current = new ModConfig();
    private static volatile boolean loaded;
    /** 配置变更订阅（GUI 保存/热重载时平台注册命令重建回调）。 */
    private static final List<Runnable> reloadListeners = new CopyOnWriteArrayList<>();

    private MockplayerConfig() {
    }

    /** 订阅配置变更（保存/重载后立即触发；回调里判空，未就绪时安全跳过）。 */
    public static void onReload(Runnable listener) {
        MockplayerConfig.reloadListeners.add(listener);
    }

    /** 配置文件路径（懒初始化：游戏目录/config/mockplayer.json）。 */
    public static Path path() {
        Path p = MockplayerConfig.path;
        if (p == null) {
            synchronized (MockplayerConfig.class) {
                p = MockplayerConfig.path;
                if (p == null) {
                    p = Minecraft.getInstance().gameDirectory.toPath().resolve("config/mockplayer.json");
                    MockplayerConfig.path = p;
                }
            }
        }
        return p;
    }

    /** 当前生效配置（首次访问懒加载配置文件，保证手改 JSON 自动生效）。 */
    public static ModConfig get() {
        if (!MockplayerConfig.loaded) {
            synchronized (MockplayerConfig.class) {
                if (!MockplayerConfig.loaded) {
                    MockplayerConfig.current = ModConfigIO.load(path());
                    MockplayerConfig.loaded = true;
                }
            }
        }
        return MockplayerConfig.current;
    }

    /** 从配置文件重新加载（手改 JSON 后调用，立即生效）。 */
    public static void reload() {
        MockplayerConfig.current = ModConfigIO.load(path());
        MockplayerConfig.loaded = true;
        fireReload();
    }

    /** 把当前配置保存到配置文件（YACL 保存按钮调用）。 */
    public static void save() {
        ModConfigIO.save(path(), MockplayerConfig.current);
        fireReload();
    }

    /** 替换当前配置并保存（界面绑定/测试共用）。 */
    public static void save(ModConfig config) {
        MockplayerConfig.current = config;
        MockplayerConfig.loaded = true;
        ModConfigIO.save(path(), config);
        fireReload();
    }

    /** 通知订阅者：配置已变更，命令树需要重建（异常只记日志，不崩客户端）。 */
    private static void fireReload() {
        for (Runnable listener : MockplayerConfig.reloadListeners) {
            try {
                listener.run();
            } catch (Exception e) {
                LOGGER.warn("Config reload listener failed", e);
            }
        }
    }
}
