package com.mockplayer.test;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

import java.util.List;

/**
 * mocktest 专用测试 payload（双端 testmod 注册，验证假人连接的 mod payload 拦截/记录）。
 *
 * namespace 用主 mod id "mockplayer"（fabric/neoforge 双端都存在，modName 解析用例
 * 动态取平台显示名断言）。
 *
 * A：含嵌套 record + 列表（反射 dump 用例的字段覆盖）；
 * B：简单两字段（按 typeId 过滤用例）。
 */
public final class TestPayloads {

    private TestPayloads() {
    }

    /** 嵌套结构（dump 递归验证）。 */
    public record Nested(int id, String name) {
        public static final StreamCodec<ByteBuf, Nested> STREAM_CODEC = StreamCodec.composite(
                ByteBufCodecs.INT, Nested::id,
                ByteBufCodecs.STRING_UTF8, Nested::name,
                Nested::new);
    }

    /** A：完整字段 payload（拦截/记录/dump/modName 用例）。 */
    public record PayloadA(int number, String text, Nested nested, List<String> tags) implements CustomPacketPayload {
        public static final Type<PayloadA> TYPE = new Type<>(
                Identifier.fromNamespaceAndPath("mockplayer", "payload_a"));
        public static final StreamCodec<RegistryFriendlyByteBuf, PayloadA> STREAM_CODEC = StreamCodec.composite(
                ByteBufCodecs.INT, PayloadA::number,
                ByteBufCodecs.STRING_UTF8, PayloadA::text,
                Nested.STREAM_CODEC, PayloadA::nested,
                ByteBufCodecs.STRING_UTF8.apply(ByteBufCodecs.list()), PayloadA::tags,
                PayloadA::new);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** B：简单 payload（过滤/上限截断用例，发送频繁）。 */
    public record PayloadB(boolean flag, int count) implements CustomPacketPayload {
        public static final Type<PayloadB> TYPE = new Type<>(
                Identifier.fromNamespaceAndPath("mockplayer", "payload_b"));
        public static final StreamCodec<RegistryFriendlyByteBuf, PayloadB> STREAM_CODEC = StreamCodec.composite(
                ByteBufCodecs.BOOL, PayloadB::flag,
                ByteBufCodecs.INT, PayloadB::count,
                PayloadB::new);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** 未注册 payload（sendModPayload 注册检查用例：必须返回 false，且不实际发送）。 */
    public record UnregisteredPayload() implements CustomPacketPayload {
        public static final Type<UnregisteredPayload> TYPE = new Type<>(
                Identifier.fromNamespaceAndPath("unregistered_test", "never_registered"));

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }
}
