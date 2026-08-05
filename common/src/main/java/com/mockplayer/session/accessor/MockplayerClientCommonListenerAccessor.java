package com.mockplayer.session.accessor;

import net.minecraft.client.multiplayer.ClientCommonPacketListenerImpl;
import net.minecraft.network.Connection;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * 暴露 ClientCommonPacketListenerImpl 的 connection 字段（protected final，声明在父类）。
 * 供 MixinClientConfigurationPacketListenerImpl 判断当前配置阶段的连接是否为假人连接。
 */
@Mixin(ClientCommonPacketListenerImpl.class)
public interface MockplayerClientCommonListenerAccessor {

    @Accessor("connection")
    Connection connection();
}
