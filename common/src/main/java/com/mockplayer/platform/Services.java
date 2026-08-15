package com.mockplayer.platform;

import com.mockplayer.platform.services.IPlatformHelper;

/**
 * 平台服务加载器：common 代码获取各平台（Fabric / NeoForge）的实现。
 *
 * 不用 Java ServiceLoader：merged jar（Forgix 合体）会把两个平台的
 * META-INF/services 合并成同一文件（两个实现条目都在），ServiceLoader.findFirst()
 * 顺序不定会加载错平台实现——neoforge 环境加载到 FabricPlatformHelper →
 * 方法体引用 FabricLoader → NoClassDefFoundError 崩溃（2026-08-15 实测）。
 * 改为按加载器环境反射选择实现类：FabricLoader 类在 classpath = Fabric 环境。
 */
public class Services {

    /** 当前平台的辅助接口：平台名 / mod 加载判断 / 开发环境判断。 */
    public static final IPlatformHelper PLATFORM = load(IPlatformHelper.class);

    /**
     * 加载指定服务接口的当前平台实现；找不到时抛异常，避免静默降级。
     *
     * @param clazz 服务接口类型
     * @return 平台实现实例
     */
    public static <T> T load(Class<T> clazz) {
        String impl;
        try {
            // Fabric 环境：fabric-loader 必然在 classpath；neoforge 环境该类不存在
            Class.forName("net.fabricmc.loader.api.FabricLoader");
            impl = "com.mockplayer.platform.FabricPlatformHelper";
        } catch (ClassNotFoundException e) {
            impl = "com.mockplayer.platform.NeoForgePlatformHelper";
        }
        try {
            return clazz.cast(Class.forName(impl).getDeclaredConstructor().newInstance());
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Failed to load platform helper " + impl, e);
        }
    }
}
