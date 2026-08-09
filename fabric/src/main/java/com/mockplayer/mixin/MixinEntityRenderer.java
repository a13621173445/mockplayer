package com.mockplayer.mixin;

import com.mockplayer.api.Bot;
import com.mockplayer.api.MockplayerApi;
import com.mockplayer.session.DebugNameTagInfo;

import com.mojang.blaze3d.vertex.PoseStack;

import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 假人 F3 调试信息标签：把信息行写入实体渲染状态的 scoreText。
 *
 * 26.2 渲染架构里 scoreText 原版就画在名字标签正下方（第二行），
 * 假人无队伍时原值为空，覆盖安全；主玩家/其它实体不受影响。
 */
@Mixin(EntityRenderer.class)
public abstract class MixinEntityRenderer {

    @Inject(
            method = "extractNameTags(Lnet/minecraft/world/entity/Entity;Lnet/minecraft/client/renderer/entity/state/EntityRenderState;FDD)V",
            at = @At("RETURN")
    )
    private void mockplayer$appendFakePlayerDebug(
            Entity entity,
            EntityRenderState state,
            float partialTicks,
            double nameTagDistance,
            double belowNameDistance,
            CallbackInfo ci) {
        // 主玩家世界里的假人实体是 RemotePlayer（服务端玩家的客户端表示），
        // 不是 FakeLocalPlayer（那只存在于假人自己的无头会话）——必须按玩家名匹配 Bot
        if (entity instanceof net.minecraft.world.entity.player.Player player
                && DebugNameTagInfo.shouldShow()) {
            Bot bot = MockplayerApi.bots().getBot(player.getName().getString()).orElse(null);
            if (bot == null) {
                return;
            }
            Component info = DebugNameTagInfo.format(bot);
            if (info != null) {
                state.scoreText = info;
                DebugNameTagInfo.recordRender(info);
            }
        }
    }

    /**
     * 多行名字标签：scoreText 里含 \n 时逐行绘制（所有信息行在名字上方）。
     * 原版 submitNameDisplay 只画单行 scoreText，多行信息必须手动逐行 submitNameTag。
     */
    @Inject(
            method = "submitNameDisplay(Lnet/minecraft/client/renderer/entity/state/EntityRenderState;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;Lnet/minecraft/client/renderer/state/level/CameraRenderState;I)V",
            at = @At("HEAD"),
            cancellable = true
    )
    private void mockplayer$submitMultiLineNameDisplay(
            EntityRenderState state,
            PoseStack poseStack,
            SubmitNodeCollector submitNodeCollector,
            CameraRenderState camera,
            int offset,
            CallbackInfo ci) {
        java.util.List<net.minecraft.network.chat.Component> rows = state.scoreText == null
                ? java.util.List.of()
                : state.scoreText.getSiblings();
        // 假人注入的 scoreText 第一行必是 ❤；只要 ≥2 行就按多行布局（信息在名字上方）
        if (rows.size() < 2 || !rows.get(0).getString().startsWith("❤")) {
            return;
        }
        float lineHeight = 9.0F * 1.15F * 0.025F;
        poseStack.pushPose();
        // 所有信息行画在名字上方：先上移 rows 行高度，逐行向下画，名字最后落在标准位置
        float infoOffset = lineHeight * rows.size();
        poseStack.translate(0.0F, infoOffset, 0.0F);
        for (net.minecraft.network.chat.Component row : rows) {
            submitNodeCollector.submitNameTag(poseStack, state.nameTagAttachment, offset,
                    row, !state.isDiscrete, state.lightCoords, camera);
            poseStack.translate(0.0F, -lineHeight, 0.0F);
        }
        if (state.nameTag != null) {
            submitNodeCollector.submitNameTag(poseStack, state.nameTagAttachment, offset, state.nameTag,
                    !state.isDiscrete, state.lightCoords, camera);
        }
        DebugNameTagInfo.recordRenderLayout(infoOffset, 0.0F);
        poseStack.popPose();
        ci.cancel();
    }
}
