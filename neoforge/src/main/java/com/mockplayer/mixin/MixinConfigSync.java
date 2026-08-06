package com.mockplayer.mixin;

import java.lang.reflect.Field;
import java.util.LinkedHashMap;
import java.util.Map;

import net.minecraft.network.Connection;
import net.minecraft.network.protocol.configuration.ServerConfigurationPacketListener;
import net.neoforged.neoforge.network.ConfigSync;

import com.mockplayer.session.FakeConnectionRegistry;
import com.mockplayer.session.FakeSession;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 假人连接时 neoforge ConfigSync.syncAllConfigs 的 ModConfigs 读取可能 path 为 null
 * （第一个假人断开后服务端 config 状态残留），导致 "Failed to start configuration task
 * neoforge:sync_config" 被踢。假人不需要服务端配置同步，但 configsToSync 必须注册
 * （否则 ConfigSync.syncPendingConfigs 对假人 ServerPlayer 抛
 * "configsToSync should contain an entry for player"）。所以假人：注册空 configsToSync
 * 但跳过 ModConfigs 文件读取。
 *
 * 用反射访问 ConfigSync 的 private static lock/configsToSync（@Shadow 对 static final
 * 字段注入值为 null，不可用）。
 */
@Mixin(ConfigSync.class)
public abstract class MixinConfigSync {

    @Inject(method = "syncAllConfigs", at = @At("HEAD"), cancellable = true)
    private static void mockplayer$fakeSafeSync(ServerConfigurationPacketListener listener, CallbackInfo ci) {
        Connection connection = listener.getConnection();
        if (connection.isMemoryConnection()) {
            return;
        }
        // 服务端 Connection 与客户端 markFake 的是不同实例，isFake 判不到；用全局 configuringFake
        // 标志（假人配置阶段由 FakeLoginListener 置位、进 play 清除）。主玩家（内存连接）已 return。
        if (FakeConnectionRegistry.isConfiguringFake()) {
            try {
                Field lockField = ConfigSync.class.getDeclaredField("lock");
                lockField.setAccessible(true);
                Object lock = lockField.get(null);
                Field mapField = ConfigSync.class.getDeclaredField("configsToSync");
                mapField.setAccessible(true);
                @SuppressWarnings("unchecked")
                Map<Connection, Map<String, byte[]>> map = (Map<Connection, Map<String, byte[]>>) mapField.get(null);
                synchronized (lock) {
                    map.put(connection, new LinkedHashMap<>());
                }
            } catch (Exception e) {
                FakeSession.LOG.warn("[fake] ConfigSync 注册失败: {}", e.toString());
            }
            ci.cancel();
        }
    }
}
