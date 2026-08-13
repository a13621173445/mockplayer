package com.mockplayer.platform.services;

/** 平台抽象：隔离 Fabric / NeoForge 的加载器 API，common 代码只依赖此接口。 */
public interface IPlatformHelper {

    /**
     * @return 当前平台名（"Fabric" / "NeoForge"）。
     */
    String getPlatformName();

    /**
     * @param modId 要查询的 mod id
     * @return 该 mod 是否已加载。
     */
    boolean isModLoaded(String modId);

    /**
     * @return 是否处于开发环境（dev 启动）。
     */
    boolean isDevelopmentEnvironment();

    /**
     * @return 环境名：development / production。
     */
    default String getEnvironmentName() {
        return isDevelopmentEnvironment() ? "development" : "production";
    }
}
