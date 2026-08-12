package com.mockplayer.api;

/**
 * 假人世界内存记账接缝（可插拔）。
 *
 * 核心会话只依赖本接口读取估算值，不依赖任何具体实现；
 * 实现方（如 com.mockplayer.memory 模块）通过 {@link BotWorldMemoryRegistry}
 * 挂载，并监听 {@link com.mockplayer.api.event.BotListener} 的世界事件维护计数。
 */
public interface BotWorldMemory {

    /** 当前世界内存估算字节（查询 O(1)，各假人独立；无记账时返回 0）。 */
    long estimatedBytes();

    /** 清空全部记账（重生/换维/断开等世界重建时由实现内部调用）。 */
    void reset();
}
