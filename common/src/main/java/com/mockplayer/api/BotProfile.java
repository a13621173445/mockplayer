package com.mockplayer.api;

import org.jspecify.annotations.Nullable;

import java.util.Objects;

/**
 * 创建 bot 的参数。
 *
 * @param name  bot 名字（唯一，重名拒绝）
 * @param owner 创建者标识（必填）："command" = 主玩家命令；外部 mod 用自己 modId。
 *              owner 决定删除权限：command 是特权（可删任何），mod 只能删自己创建的。
 * @param host  目标服务器地址；null 表示跟随当前客户端所在服务器
 * @param port  目标服务器端口；host 非 null 时生效，-1 表示使用默认 25565
 */
public record BotProfile(String name, String owner, @Nullable String host, int port) {

    /**
     * 构造参数校验：name/owner 非空非空白。
     *
     * @throws NullPointerException     name/owner 为 null
     * @throws IllegalArgumentException name/owner 为空白
     */
    public BotProfile {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(owner, "owner");
        if (name.isBlank()) {
            throw new IllegalArgumentException("name 不能为空白");
        }
        if (owner.isBlank()) {
            throw new IllegalArgumentException("owner 不能为空白");
        }
    }

    /**
     * 跟随当前服务器的 bot（host/port 为空）。
     *
     * @param name  bot 名字
     * @param owner 创建者标识
     * @return BotProfile
     */
    public static BotProfile of(String name, String owner) {
        return new BotProfile(name, owner, null, -1);
    }

    /**
     * 直连指定服务器的 bot。
     *
     * @param name  bot 名字
     * @param owner 创建者标识
     * @param host  目标服务器地址
     * @param port  目标服务器端口
     * @return BotProfile
     */
    public static BotProfile of(String name, String owner, String host, int port) {
        return new BotProfile(name, owner, host, port);
    }
}
