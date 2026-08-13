package com.mockplayer.platform;

import com.mockplayer.platform.services.IPlatformHelper;

import java.util.ServiceLoader;

/**
 * 平台服务加载器：common 代码通过 Java ServiceLoader 获取各平台（Fabric / NeoForge）的实现，
 * 实现类由各平台子项目在 META-INF/services 中声明，运行时替换，common 不直接依赖加载器 API。
 */
public class Services {

    /** 当前平台的辅助接口：平台名 / mod 加载判断 / 开发环境判断。 */
    public static final IPlatformHelper PLATFORM = load(IPlatformHelper.class);

    /**
     * 加载指定服务接口的唯一平台实现；找不到时抛异常，避免静默降级。
     *
     * @param clazz 服务接口类型
     * @return 平台实现实例
     */
    public static <T> T load(Class<T> clazz) {
        return ServiceLoader.load(clazz, Services.class.getClassLoader())
                .findFirst()
                .orElseThrow(() -> new NullPointerException("Failed to load service for " + clazz.getName()));
    }
}
