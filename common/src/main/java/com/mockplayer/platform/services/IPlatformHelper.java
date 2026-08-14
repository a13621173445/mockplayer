package com.mockplayer.platform.services;

import net.minecraft.resources.Identifier;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.world.inventory.AbstractContainerMenu;

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

    /**
     * @param id payload 类型 id
     * @return 该 serverbound payload 是否已注册（可编码发送）。未注册类型在编码时会被
     *         fabric/neoforge 的注册表 codec 静默降级为 DiscardedPayload（内容丢失），
     *         因此发送前必须检查。
     */
    boolean isServerboundPayloadRegistered(Identifier id);

    /**
     * @param namespace payload typeId 的 namespace（绝大多数是 mod id）
     * @return 该 namespace 对应已加载 mod 的显示名；非已加载 mod 的 namespace
     *         （如 {@code minecraft:}、通用 {@code c:}）返回 null
     */
    String getModDisplayName(String namespace);

    /**
     * 平台容器扩展数据包处理（neoforge 用扩展包替代原版 ContainerSetData 传容器数据，
     * 如附魔成本；fabric 无此机制返回 false）。
     *
     * 输入：被拦截的 mod payload + 假人当前菜单
     * 输出：true = 平台扩展包已处理（应用到假人菜单，等价原版语义，主玩家零污染）；
     *       false = 非平台扩展包（调用方只记录）
     */
    default boolean handlePlatformContainerPayload(CustomPacketPayload payload, AbstractContainerMenu fakeMenu) {
        return false;
    }
}
