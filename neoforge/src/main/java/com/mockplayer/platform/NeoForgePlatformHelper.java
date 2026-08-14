package com.mockplayer.platform;

import com.mockplayer.platform.services.IPlatformHelper;
import net.neoforged.fml.ModList;
import net.neoforged.fml.loading.FMLLoader;

public class NeoForgePlatformHelper implements IPlatformHelper {

    @Override
    public String getPlatformName() {

        return "NeoForge";
    }

    @Override
    public boolean isModLoaded(String modId) {

        return ModList.get().isLoaded(modId);
    }

    @Override
    public boolean isDevelopmentEnvironment() {

        return !FMLLoader.getCurrent().isProduction();
    }

    @Override
    public boolean isServerboundPayloadRegistered(net.minecraft.resources.Identifier id) {
        // neoforge 注册表查询：PLAY 协议下已注册（任意 flow）即视为可编码；
        // 未注册返回 null（并打一条 warn，sendModPayload 失败路径低频可接受）
        try {
            return net.neoforged.neoforge.network.registration.NetworkRegistry.getCodec(
                    id, net.minecraft.network.ConnectionProtocol.PLAY,
                    net.minecraft.network.protocol.PacketFlow.SERVERBOUND) != null;
        } catch (Exception e) {
            return false;
        }
    }

    @Override
    public String getModDisplayName(String namespace) {
        // namespace 绝大多数是 mod id；查 mod 列表得显示名
        return ModList.get().getModContainerById(namespace)
                .map(container -> container.getModInfo().getDisplayName())
                .orElse(null);
    }

    @Override
    public boolean handlePlatformContainerPayload(net.minecraft.network.protocol.common.custom.CustomPacketPayload payload,
                                                  net.minecraft.world.inventory.AbstractContainerMenu fakeMenu) {
        // neoforge 平台扩展：advanced_container_set_data（替代原版 ContainerSetData 传容器数据，
        // 如附魔成本）——转原版包应用到假人菜单（等价原版 handleContainerSetData），
        // 不调 super 分发（neoforge handler 的 context.player() 是主玩家，会污染）
        if (payload instanceof net.neoforged.neoforge.network.payload.AdvancedContainerSetDataPayload adv) {
            net.minecraft.network.protocol.game.ClientboundContainerSetDataPacket vanilla = adv.toVanillaPacket();
            if (fakeMenu != null && fakeMenu.containerId == vanilla.getContainerId()) {
                fakeMenu.setData(vanilla.getId(), vanilla.getValue());
            }
            return true;
        }
        return false;
    }
}
