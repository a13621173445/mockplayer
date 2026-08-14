package com.mockplayer.api;

import net.minecraft.resources.Identifier;

/**
 * 假人连接上一个 mod payload 的元信息快照（只读，不含原始对象引用）。
 *
 * 输入：FakePlayListener 入站拦截 / MixinConnection 出站记录写入 FakePlayerState
 * 输出：程序化 AI / /query payload 命令查询
 *
 * 字段语义：
 * - namespace/typeId：payload 注册 id（{@code namespace:path}，namespace 绝大多数是 mod id）
 * - modName：由平台 mod 元数据查得的显示名（非已加载 mod 的 namespace 为 null）
 * - tick：到达时的游戏刻（Minecraft.level.getGameTime()）
 * - sizeBytes：原始包估算字节（记录时的保守估算，非精确）
 */
public record ModPayloadInfo(
        Identifier typeId,
        String namespace,
        String modName,
        long tick,
        int sizeBytes) {

    /** 完整 typeId 的字符串形式（namespace:path）。 */
    public String typeIdString() {
        return this.typeId.toString();
    }
}
