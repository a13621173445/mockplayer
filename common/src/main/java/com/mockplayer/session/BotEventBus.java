package com.mockplayer.session;

import com.mockplayer.api.Bot;
import com.mockplayer.api.event.BotListener;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * Bot 事件总线（惰性分发）。
 *
 * 无监听者时 fire 直接返回（零开销）；监听者异常被捕获记录，不中断其余监听者。
 * 所有事件在主线程派发，监听者必须快速返回。
 */
public class BotEventBus {

    private final List<BotListener> listeners = new CopyOnWriteArrayList<>();

    /**
     * 注册监听器。
     *
     * @param listener BotListener
     */
    public void addListener(BotListener listener) {
        if (listener != null) {
            this.listeners.add(listener);
        }
    }

    /**
     * 若未挂载过则挂载（重复挂载防护，/query listen on 幂等）。
     *
     * @return true 本次挂载成功；false 已挂载（或 listener 为 null）
     */
    public boolean addListenerIfAbsent(BotListener listener) {
        if (listener != null && !this.listeners.contains(listener)) {
            this.listeners.add(listener);
            return true;
        }
        return false;
    }

    /**
     * 移除监听器。
     *
     * @param listener BotListener
     */
    public void removeListener(BotListener listener) {
        this.listeners.remove(listener);
    }

    /**
     * 是否有监听者（供外部惰性判断）。
     *
     * @return true 有监听者
     */
    public boolean hasListeners() {
        return !this.listeners.isEmpty();
    }

    /**
     * 派发事件：无监听者时零开销；有监听者时逐个调用 action。
     *
     * @param bot    事件归属的 bot
     * @param action 对监听器的调用（如 l -> l.onChat(this, msg)）
     */
    public void fire(Bot bot, Consumer<BotListener> action) {
        if (this.listeners.isEmpty()) {
            return;
        }
        for (BotListener listener : this.listeners) {
            try {
                action.accept(listener);
            } catch (Exception e) {
                FakeSession.LOG.error("[{}] Bot 事件监听器异常", bot.getName(), e);
            }
        }
    }
}
