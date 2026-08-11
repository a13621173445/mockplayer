package com.mockplayer.session;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 假人 level 注册表（粒子/音效隔离用）。
 *
 * 假人 level 的 {@code addParticle/playLocalSound/addDestroyBlockEffect} 会被
 * MixinClientLevel 拦截：只有注册在此的 level 才走「记录到假人 state」分支，
 * 绝不播到主玩家屏幕/音箱。
 */
public final class FakeLevelRegistry {

    private static final Set<net.minecraft.client.multiplayer.ClientLevel> FAKE_LEVELS =
            ConcurrentHashMap.newKeySet();

    private FakeLevelRegistry() {
    }

    /** 注册假人 level（FakePlayListener 创建假人 level 后调用）。 */
    public static void registerFakeLevel(net.minecraft.client.multiplayer.ClientLevel level) {
        if (level != null) {
            FAKE_LEVELS.add(level);
        }
    }

    /** 注销假人 level（FakeSession.disconnect 时调用）。 */
    public static void unregisterFakeLevel(net.minecraft.client.multiplayer.ClientLevel level) {
        if (level != null) {
            FAKE_LEVELS.remove(level);
        }
    }

    /** 是否为假人 level（MixinClientLevel 拦截 addParticle 用）。 */
    public static boolean isFakeLevel(net.minecraft.client.multiplayer.ClientLevel level) {
        return FAKE_LEVELS.contains(level);
    }
}
