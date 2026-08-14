package com.mockplayer.platform;

import com.mockplayer.platform.services.IPlatformHelper;
import net.fabricmc.loader.api.FabricLoader;

public class FabricPlatformHelper implements IPlatformHelper {

    @Override
    public String getPlatformName() {
        return "Fabric";
    }

    @Override
    public boolean isModLoaded(String modId) {

        return FabricLoader.getInstance().isModLoaded(modId);
    }

    @Override
    public boolean isDevelopmentEnvironment() {

        return FabricLoader.getInstance().isDevelopmentEnvironment();
    }

    @Override
    public boolean isServerboundPayloadRegistered(net.minecraft.resources.Identifier id) {
        // fabric 的 play 阶段 serverbound 注册表（客户端发送侧）；get 返回 null = 未注册
        return net.fabricmc.fabric.impl.networking.PayloadTypeRegistryImpl.SERVERBOUND_PLAY.get(id) != null;
    }

    @Override
    public String getModDisplayName(String namespace) {
        // namespace 绝大多数是 mod id；查加载器元数据得显示名
        return FabricLoader.getInstance().getModContainer(namespace)
                .map(container -> container.getMetadata().getName())
                .orElse(null);
    }
}
