package com.mockplayer.session;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.client.player.KeyboardInput;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.Connection;
import net.minecraft.world.entity.Entity;

/**
 * /control 控制权管理：把控制权路由到假人身上。
 *
 * 核心：控制时把 Minecraft.player/level/gameMode 指向假人那套，Minecraft.tick() 自动完整驱动假人
 * （tickEntities 驱动假人 player 物理 + getConnection 驱动假人 connection + 交互走假人）。
 * 被换下的主玩家那套（player/level/gameMode/connection）由本类每 tick 驱动保活，防止主玩家掉线。
 *
 * 线程安全（关键）：restore()/control() 涉及 mc.setLevel/setCameraEntity/allChanged 等 OpenGL 操作，
 * 只能在渲染线程执行。可能被 Netty IO 线程调用（假人被踢的 cleanupOnKick → removeFakePlayer →
 * onFakePlayerRemoved），因此非渲染线程时必须 mc.execute 投递，否则 JVM abort。
 */
public final class ControlManager {

    private static FakeSession controlled;
    // 主玩家那套备份（换下后驱动保活 + 切回恢复）
    private static LocalPlayer mainPlayer;
    private static ClientLevel mainLevel;
    private static MultiPlayerGameMode mainGameMode;
    private static Connection mainConnection;
    private static net.minecraft.client.player.ClientInput mainPlayerInput;
    private static net.minecraft.world.entity.Entity mainCameraEntity;
    // 主玩家挂机状态（隔离 handler 记录聊天/音效/统计等，不让任何信息丢失）
    private static final FakePlayerState mainState = new FakePlayerState();

    private ControlManager() {
    }

    /** respawn 隔离用：主玩家重生后替换备份引用（不碰 mc.player） */
    static void replaceMainPlayer(net.minecraft.client.player.LocalPlayer player) {
        mainPlayer = player;
        if (mainPlayer != null) {
            mainCameraEntity = player;
        }
    }

    /** respawn 隔离用：主玩家重生后替换备份 level（不碰 mc.level） */
    static void replaceMainLevel(net.minecraft.client.multiplayer.ClientLevel level) {
        mainLevel = level;
    }

    /** 是否正在控制假人 */
    public static boolean isControlling() {
        return controlled != null;
    }

    /** 当前被控制的假人 */
    public static FakeSession getControlled() {
        return controlled;
    }

    /** 被换下、正在保活的主玩家（control 期间） */
    public static LocalPlayer getMainPlayer() {
        return mainPlayer;
    }

    /** 被换下、正在保活的主玩家 level */
    public static ClientLevel getMainLevel() {
        return mainLevel;
    }

    /** 被换下、正在保活的主玩家 gameMode */
    public static MultiPlayerGameMode getMainGameMode() {
        return mainGameMode;
    }

    /** 主玩家挂机状态（隔离 handler 写入） */
    public static FakePlayerState getMainState() {
        return mainState;
    }

    /** 控制指定假人（独占：先切回上一个）。必须在渲染线程调用。 */
    public static boolean control(String name) {
        Minecraft mc = Minecraft.getInstance();
        if (!mc.isSameThread()) {
            final String n = name;
            mc.execute(() -> control(n));
            return true;
        }
        if (controlled != null) {
            restore();
        }
        FakeSession session = SessionManager.getInstance().getSession(name);
        if (session == null) {
            return false;
        }
        LocalPlayer bot = session.getFakePlayer() instanceof LocalPlayer lp ? lp : null;
        if (bot == null || mc.player == null) {
            return false;
        }

        // 备份主玩家那套（换下后保活 + 切回恢复）
        mainPlayer = mc.player;
        mainLevel = mc.level;
        mainGameMode = mc.gameMode;
        mainPlayerInput = mc.player.input;
        mainCameraEntity = mc.getCameraEntity();
        mainConnection = mc.player.connection != null ? mc.player.connection.getConnection() : null;

        controlled = session;
        session.setControlled(true);
        // 假人那套接管全局：先 setLevel 同步引擎（区块/粒子/相机绑假人世界），再切 player/gameMode
        ClientLevel botLevel = session.getPlayListener() != null
                ? ((com.mockplayer.session.accessor.MockplayerClientPacketListenerAccessor) session.getPlayListener()).mockplayer$getLevel()
                : null;
        MultiPlayerGameMode botGameMode = session.getPlayListener() != null
                ? session.getPlayListener().getFakeGameMode()
                : null;
        if (botLevel != null) {
            mc.setLevel(botLevel);
        }
        mc.player = bot;
        if (botGameMode != null) {
            mc.gameMode = botGameMode;
        }
        mc.setCameraEntity(bot);
        // 输入路由：假人读键盘，主玩家零输入挂机
        bot.input = new KeyboardInput(mc.options);
        mainPlayer.input = new net.minecraft.client.player.ClientInput();

        FakeSession.LOG.info("[control] 控制假人 {}", name);
        return true;
    }

    /**
     * 每 tick 驱动被换下的主玩家那套（connection 保活 + player 物理），防止主玩家掉线。
     * 假人那套由 Minecraft.tick 正常驱动（因为全局引用已指向假人）。
     */
    public static void tick() {
        if (controlled == null) {
            return;
        }
        // 主玩家 connection 保活（收发包 + playListener）
        if (mainConnection != null && mainConnection.isConnected()) {
            try {
                mainConnection.tick();
            } catch (Exception e) {
                FakeSession.LOG.error("[control] 主玩家连接 tick 出错", e);
            }
        }
        // 主玩家 player 物理（挂机仍要发包防反作弊）
        if (mainPlayer != null) {
            try {
                mainPlayer.tick();
            } catch (Exception e) {
                FakeSession.LOG.error("[control] 主玩家物理 tick 出错", e);
            }
        }
    }

    /**
     * 切回主玩家（先清 controlled 防双驱动，再恢复引用 + 立刻刷新）。
     * 可能被 Netty IO 线程调用（假人被踢）——OpenGL 操作必须切渲染线程。
     */
    public static void restore() {
        Minecraft mc = Minecraft.getInstance();
        if (!mc.isSameThread()) {
            mc.execute(ControlManager::restore);
            return;
        }
        FakeSession old = controlled;
        if (old == null && mainPlayer == null) {
            return;
        }
        try {
            // 先清 controlled，避免主玩家引用恢复后 Minecraft.tick 与 ControlManager.tick 双驱动
            controlled = null;
            if (old != null) {
                old.setControlled(false);
            }
            // 恢复主玩家那套全局引用。mainLevel 是 control 时换下的原对象（数据完好），
            // 不重建 level/player——原版 setLevel 会触发 allChanged + extract 自动重建渲染。
            if (mainLevel != null) {
                mc.setLevel(mainLevel);
            }
            if (mainPlayer != null) {
                mc.player = mainPlayer;
                if (mainGameMode != null) {
                    mc.gameMode = mainGameMode;
                }
                if (mainCameraEntity != null) {
                    mc.setCameraEntity(mainCameraEntity);
                } else {
                    mc.setCameraEntity(mainPlayer);
                }
                mainPlayer.input = mainPlayerInput != null
                        ? mainPlayerInput
                        : new KeyboardInput(mc.options);
            }
            // 假人恢复挂机（零输入）
            if (old != null && old.getFakePlayer() instanceof LocalPlayer bot) {
                bot.input = new net.minecraft.client.player.ClientInput();
            }
            FakeSession.LOG.info("[control] 已切回主玩家（假人 {} 保留在线）", old != null ? old.getName() : "?");
        } finally {
            mainPlayer = null;
            mainLevel = null;
            mainGameMode = null;
            mainConnection = null;
            mainPlayerInput = null;
            mainCameraEntity = null;
        }
    }

    /** 假人没了（被踢/delplayer）时自动切回 */
    public static void onFakePlayerRemoved(String name) {
        if (controlled != null && controlled.getName().equals(name)) {
            // 先清逻辑状态（立即生效，防止继续双驱动），引擎切换由 restore 内部切渲染线程处理
            controlled = null;
            restore();
        }
    }
}
