package com.mockplayer.session;

import com.mockplayer.Constants;
import com.mockplayer.api.Bot;
import com.mockplayer.api.BotManager;
import com.mockplayer.api.BotProfile;
import com.mockplayer.api.RemoveResult;
import com.mockplayer.api.event.BotListener;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * BotManager 实现：持有假人注册表（名字 → FakeSession），提供创建/查询/删除/监听。
 *
 * 权限模型：owner="command"（主玩家命令）是特权，可删除任何 bot；
 * 外部 mod（owner=modId）只能删除自己创建的；跨 owner 删除返回 NOT_OWNER。
 */
public class BotManagerImpl implements BotManager {

    /** 主玩家命令的 owner 标识（特权） */
    public static final String COMMAND_OWNER = "command";

    private final Map<String, FakeSession> sessions = new ConcurrentHashMap<>();
    private final List<BotListener> globalListeners = new CopyOnWriteArrayList<>();

    @Override
    public Bot createBot(BotProfile profile) {
        // 公共 API：固定标记 API（外部/附属 mod 创建，不受本 mod 命令/配置管理，
        // 即使调用方伪造 owner="command" 也无法变成 CORE）
        return this.createBotInternal(profile, com.mockplayer.api.BotSource.API);
    }

    /** 本 mod 命令创建路径（/newplayer、批量命令）：标记 CORE，受命令/配置管理。 */
    Bot createCoreBot(BotProfile profile) {
        return this.createBotInternal(profile, com.mockplayer.api.BotSource.CORE);
    }

    private Bot createBotInternal(BotProfile profile, com.mockplayer.api.BotSource source) {
        if (profile == null) {
            return null;
        }
        String name = profile.name();
        if (name == null || name.isBlank()) {
            return null;
        }
        FakeSession session = new FakeSession(name);
        session.setOwner(profile.owner());
        session.setSource(source);
        BotImpl bot = new BotImpl(session, profile.owner());
        session.setBot(bot);
        for (BotListener listener : this.globalListeners) {
            bot.events().addListener(listener);
        }
        // 连接失败：回收会话 + 通知主玩家（失败提示走语言文件 key）
        session.setOnConnectFail(key -> {
            this.sessions.remove(name);
            Minecraft.getInstance().execute(() -> {
                if (Minecraft.getInstance().player != null) {
                    Minecraft.getInstance().player.sendSystemMessage(
                            Component.translatable(key, Component.literal(name).withStyle(ChatFormatting.AQUA))
                                    .withStyle(ChatFormatting.RED));
                }
            });
        });
        if (this.sessions.putIfAbsent(name, session) != null) {
            Constants.LOG.warn("假人 {} 已存在，拒绝创建", name);
            return null;
        }
        // 指定目标服务器 or 跟随当前客户端所在服务器
        if (profile.host() != null && !profile.host().isBlank()) {
            int port = profile.port() > 0 ? profile.port() : 25565;
            session.connectTo(profile.host(), port, null);
        } else {
            session.connect();
        }
        bot.fireOnSpawned();
        return bot;
    }

    @Override
    public Optional<Bot> getBot(String name) {
        FakeSession session = this.sessions.get(name);
        return Optional.ofNullable(session != null ? session.getBot() : null);
    }

    @Override
    public List<Bot> getBots() {
        List<Bot> result = new ArrayList<>();
        for (FakeSession session : this.sessions.values()) {
            if (session.getBot() != null) {
                result.add(session.getBot());
            }
        }
        return result;
    }

    @Override
    public List<Bot> getBots(String ownerId) {
        List<Bot> result = new ArrayList<>();
        for (FakeSession session : this.sessions.values()) {
            Bot bot = session.getBot();
            if (bot != null && ownerId.equals(bot.getOwner())) {
                result.add(bot);
            }
        }
        return result;
    }

    @Override
    public RemoveResult removeBot(String name, String ownerId) {
        FakeSession session = this.sessions.get(name);
        if (session == null) {
            return RemoveResult.NOT_FOUND;
        }
        // 命令特权 or 本人 owner 才能删
        if (!COMMAND_OWNER.equals(ownerId) && !ownerId.equals(session.getOwner())) {
            return RemoveResult.NOT_OWNER;
        }
        this.sessions.remove(name);
        session.disconnect();
        BotImpl bot = session.getBot();
        if (bot != null) {
            bot.fireOnDisconnected(new net.minecraft.network.DisconnectionDetails(
                    Component.translatable("disconnect.mockplayer.fake_player_removed")));
        }
        return RemoveResult.REMOVED;
    }

    @Override
    public void registerListener(BotListener listener) {
        if (listener == null) {
            return;
        }
        this.globalListeners.add(listener);
        // 对已存在的所有 bot 也生效
        for (FakeSession session : this.sessions.values()) {
            BotImpl bot = session.getBot();
            if (bot != null) {
                bot.events().addListener(listener);
            }
        }
    }

    /** 内部：驱动所有会话 tick（渲染线程每 tick 调用） */
    public void tick() {
        for (FakeSession session : this.sessions.values()) {
            session.tick();
        }
    }

    /** 内部：全部假人下线（主玩家退出服务器时调用） */
    public void clearAll() {
        for (FakeSession session : this.sessions.values()) {
            session.disconnect();
            BotImpl bot = session.getBot();
            if (bot != null) {
                bot.fireOnDisconnected(new net.minecraft.network.DisconnectionDetails(
                        Component.translatable("disconnect.mockplayer.fake_player_removed")));
            }
        }
        this.sessions.clear();
        Constants.LOG.info("主玩家退出，全部假人已下线");
    }

    /** 内部：当前在线假人名字列表 */
    public Collection<String> getFakePlayerNames() {
        return this.sessions.keySet();
    }

    /** 内部：按名字取会话 */
    public FakeSession getSession(String name) {
        return this.sessions.get(name);
    }
}
