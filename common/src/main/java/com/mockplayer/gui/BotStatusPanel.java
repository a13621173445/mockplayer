package com.mockplayer.gui;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

import java.util.List;

/**
 * 状态面板渲染（P4-6 拆组件）：血量/饥饿/盔甲/经验条 + 原版 HeartType 映射。
 *
 * 纯搬移自 BotControlScreen，行为零变化；渲染状态字段（tickCount/heartRandom 等）
 * 仍由 BotControlScreen 持有，本类只读/写 screen 的包级字段。
 */
public final class BotStatusPanel {

    private BotStatusPanel() {
    }
    public static void render(BotControlScreen screen, GuiGraphicsExtractor graphics) {
        graphics.text(screen.font(), Component.translatable("gui.mockplayer.section.status"),
                screen.sx(BotControlScreen.CONTENT_X), screen.sy(BotControlScreen.CONTENT_Y), 0xFFA8C8FF);
        List<Component> lines = BotControlHud.statusLines(screen.selected);
        net.minecraft.client.player.LocalPlayer player = screen.selected.getLocalPlayer();
        int x = screen.sx(BotControlScreen.CONTENT_X);
        int y = screen.sy(BotControlScreen.CONTENT_Y + 12);
        int step = screen.sh(11);
        int start = 0;
        if (player != null) {
            // 原版血量条 + 饥饿条替代文本 ❤/🍗 行（BotControlHud.statusLines 第 0 行跳过）
            drawVanillaHealthFood(screen, graphics, screen.sx(BotControlScreen.CONTENT_X),
                    screen.sy(BotControlScreen.CONTENT_Y + 14));
            y = screen.sy(BotControlScreen.CONTENT_Y + 34);
            start = 1;
        }
        for (int i = start; i < lines.size(); i++) {
            // 状态文本按面板内容宽度截断，长行不溢出半透明背景
            graphics.text(screen.font(), screen.font().plainSubstrByWidth(
                    lines.get(i).getString(), screen.sw(BotControlScreen.CONTENT_W - 4)), x, y, 0xFFD7D7D7);
            y += step;
        }
        drawXpBar(screen, graphics, y + 4);
    }

    /**
     * 状态 Tab 血量/饥饿：完全复用原版 Hud 渲染逻辑（extractPlayerHealth/extractHearts/
     * extractFood），仅替换作用对象为假人、坐标参数化到面板内。支持盔甲条、吸收、
     * 多行、药水心色、硬核、掉血闪烁与饥饿闪烁。
     */
    private static void drawVanillaHealthFood(BotControlScreen screen, GuiGraphicsExtractor graphics, int xLeft, int yLineBase) {
        net.minecraft.world.entity.player.Player player = screen.selected.getLocalPlayer();
        if (player == null) {
            return;
        }
        int currentHealth = net.minecraft.util.Mth.ceil(player.getHealth());
        boolean blink = screen.healthBlinkTime > screen.tickCount
                && (screen.healthBlinkTime - screen.tickCount) / 3L % 2L == 1L;
        long timeMillis = net.minecraft.util.Util.getMillis();
        if (currentHealth < screen.lastHealth && player.invulnerableTime > 0) {
            screen.lastHealthTime = timeMillis;
            screen.healthBlinkTime = screen.tickCount + 20;
        } else if (currentHealth > screen.lastHealth && player.invulnerableTime > 0) {
            screen.lastHealthTime = timeMillis;
            screen.healthBlinkTime = screen.tickCount + 10;
        }
        if (timeMillis - screen.lastHealthTime > 1000L) {
            screen.displayHealth = currentHealth;
            screen.lastHealthTime = timeMillis;
        }
        screen.lastHealth = currentHealth;
        int oldHealth = screen.displayHealth;
        screen.heartRandom.setSeed(screen.tickCount * 312871);
        int xRight = xLeft + 182;
        float maxHealth = Math.max((float) player.getAttributeValue(
                        net.minecraft.world.entity.ai.attributes.Attributes.MAX_HEALTH),
                Math.max(oldHealth, currentHealth));
        int totalAbsorption = net.minecraft.util.Mth.ceil(player.getAbsorptionAmount());
        int numHealthRows = net.minecraft.util.Mth.ceil((maxHealth + totalAbsorption) / 2.0F / 10.0F);
        int healthRowHeight = Math.max(10 - (numHealthRows - 2), 3);
        int heartOffsetIndex = -1;
        if (player.hasEffect(net.minecraft.world.effect.MobEffects.REGENERATION)) {
            heartOffsetIndex = screen.tickCount % net.minecraft.util.Mth.ceil(maxHealth + 5.0F);
        }
        extractArmorLikeVanilla(screen, graphics, player, yLineBase, numHealthRows, healthRowHeight, xLeft);
        extractHeartsLikeVanilla(screen, graphics, player, xLeft, yLineBase, healthRowHeight,
                heartOffsetIndex, maxHealth, currentHealth, oldHealth, totalAbsorption, blink);
        // 原版：骑乘有生命值的载具时不画食物（显示载具血条）
        if (!(player.getVehicle() instanceof net.minecraft.world.entity.LivingEntity)) {
            extractFoodLikeVanilla(screen, graphics, player, yLineBase, xRight);
        }
        com.mockplayer.gui.BotGui.recordHealthFood(player.getHealth(), player.getFoodData().getFoodLevel());
    }

    /** 原版 extractArmor 等价：盔甲条 10 格。 */
    private static void extractArmorLikeVanilla(BotControlScreen screen, GuiGraphicsExtractor graphics,
                                         net.minecraft.world.entity.player.Player player,
                                         int yLineBase, int numHealthRows, int healthRowHeight, int xLeft) {
        int armor = player.getArmorValue();
        if (armor <= 0) {
            return;
        }
        int yLineArmor = yLineBase - (numHealthRows - 1) * healthRowHeight - 10;
        for (int i = 0; i < 10; i++) {
            int xo = xLeft + i * 8;
            if (i * 2 + 1 < armor) {
                graphics.blitSprite(RenderPipelines.GUI_TEXTURED, BotControlHud.ARMOR_FULL, xo, yLineArmor, 9, 9);
            } else if (i * 2 + 1 == armor) {
                graphics.blitSprite(RenderPipelines.GUI_TEXTURED, BotControlHud.ARMOR_HALF, xo, yLineArmor, 9, 9);
            } else {
                graphics.blitSprite(RenderPipelines.GUI_TEXTURED, BotControlHud.ARMOR_EMPTY, xo, yLineArmor, 9, 9);
            }
        }
    }

    /** 原版 extractHearts 等价：心行 + 吸收 + 闪烁。 */
    private static void extractHeartsLikeVanilla(BotControlScreen screen, GuiGraphicsExtractor graphics,
                                          net.minecraft.world.entity.player.Player player,
                                          int xLeft, int yLineBase, int healthRowHeight,
                                          int heartOffsetIndex, float maxHealth,
                                          int currentHealth, int oldHealth, int absorption, boolean blink) {
        BotHeartType type = BotHeartType.forPlayer(player);
        boolean isHardcore = player.level().getLevelData().isHardcore();
        int healthContainerCount = net.minecraft.util.Mth.ceil(maxHealth / 2.0F);
        int absorptionContainerCount = net.minecraft.util.Mth.ceil(absorption / 2.0F);
        int maxHealthHalvesCount = healthContainerCount * 2;
        for (int containerIndex = healthContainerCount + absorptionContainerCount - 1; containerIndex >= 0; containerIndex--) {
            int row = containerIndex / 10;
            int column = containerIndex % 10;
            int xo = xLeft + column * 8;
            int yo = yLineBase - row * healthRowHeight;
            if (currentHealth + absorption <= 4) {
                yo += screen.heartRandom.nextInt(2);
            }
            if (containerIndex < healthContainerCount && containerIndex == heartOffsetIndex) {
                yo -= 2;
            }
            extractHeartLikeVanilla(screen, graphics, BotHeartType.CONTAINER, xo, yo, isHardcore, blink, false);
            int halves = containerIndex * 2;
            boolean isAbsorptionHeart = containerIndex >= healthContainerCount;
            if (isAbsorptionHeart) {
                int absorptionHalves = halves - maxHealthHalvesCount;
                if (absorptionHalves < absorption) {
                    boolean halfHeart = absorptionHalves + 1 == absorption;
                    extractHeartLikeVanilla(screen, graphics,
                            type == BotHeartType.WITHERED ? type : BotHeartType.ABSORBING,
                            xo, yo, isHardcore, false, halfHeart);
                }
            }
            if (blink && halves < oldHealth) {
                boolean halfHeart = halves + 1 == oldHealth;
                extractHeartLikeVanilla(screen, graphics, type, xo, yo, isHardcore, true, halfHeart);
            }
            if (halves < currentHealth) {
                boolean halfHeart = halves + 1 == currentHealth;
                extractHeartLikeVanilla(screen, graphics, type, xo, yo, isHardcore, false, halfHeart);
            }
        }
    }

    /** 原版 extractHeart 等价：单颗心 sprite。 */
    private static void extractHeartLikeVanilla(BotControlScreen screen, GuiGraphicsExtractor graphics, BotHeartType type,
                                         int xo, int yo, boolean isHardcore, boolean blinks, boolean half) {
        graphics.blitSprite(RenderPipelines.GUI_TEXTURED,
                type.getSprite(isHardcore, half, blinks), xo, yo, 9, 9);
    }

    /** 原版 extractFood 等价：10 个鸡腿（含饥饿效果变体与闪烁）。 */
    private static void extractFoodLikeVanilla(BotControlScreen screen, GuiGraphicsExtractor graphics,
                                        net.minecraft.world.entity.player.Player player,
                                        int yLineBase, int xRight) {
        int food = player.getFoodData().getFoodLevel();
        for (int i = 0; i < 10; i++) {
            int yo = yLineBase;
            Identifier empty;
            Identifier half;
            Identifier full;
            if (player.hasEffect(net.minecraft.world.effect.MobEffects.HUNGER)) {
                empty = BotControlHud.FOOD_EMPTY_HUNGER;
                half = BotControlHud.FOOD_HALF_HUNGER;
                full = BotControlHud.FOOD_FULL_HUNGER;
            } else {
                empty = BotControlHud.FOOD_EMPTY;
                half = BotControlHud.FOOD_HALF;
                full = BotControlHud.FOOD_FULL;
            }
            if (player.getFoodData().getSaturationLevel() <= 0.0F && screen.tickCount % (food * 3 + 1) == 0) {
                yo = yLineBase + (screen.heartRandom.nextInt(3) - 1);
            }
            int xo = xRight - i * 8 - 9;
            graphics.blitSprite(RenderPipelines.GUI_TEXTURED, empty, xo, yo, 9, 9);
            if (i * 2 + 1 < food) {
                graphics.blitSprite(RenderPipelines.GUI_TEXTURED, full, xo, yo, 9, 9);
            }
            if (i * 2 + 1 == food) {
                graphics.blitSprite(RenderPipelines.GUI_TEXTURED, half, xo, yo, 9, 9);
            }
        }
    }

    /** 原版 Hud.HeartType 等价：完整 sprite 映射 + 药水/冰冻类型判定。 */
    enum BotHeartType {
        CONTAINER(
                Identifier.withDefaultNamespace("hud/heart/container"),
                Identifier.withDefaultNamespace("hud/heart/container_blinking"),
                Identifier.withDefaultNamespace("hud/heart/container"),
                Identifier.withDefaultNamespace("hud/heart/container_blinking"),
                Identifier.withDefaultNamespace("hud/heart/container_hardcore"),
                Identifier.withDefaultNamespace("hud/heart/container_hardcore_blinking"),
                Identifier.withDefaultNamespace("hud/heart/container_hardcore"),
                Identifier.withDefaultNamespace("hud/heart/container_hardcore_blinking")),
        NORMAL(
                Identifier.withDefaultNamespace("hud/heart/full"),
                Identifier.withDefaultNamespace("hud/heart/full_blinking"),
                Identifier.withDefaultNamespace("hud/heart/half"),
                Identifier.withDefaultNamespace("hud/heart/half_blinking"),
                Identifier.withDefaultNamespace("hud/heart/hardcore_full"),
                Identifier.withDefaultNamespace("hud/heart/hardcore_full_blinking"),
                Identifier.withDefaultNamespace("hud/heart/hardcore_half"),
                Identifier.withDefaultNamespace("hud/heart/hardcore_half_blinking")),
        POISONED(
                Identifier.withDefaultNamespace("hud/heart/poisoned_full"),
                Identifier.withDefaultNamespace("hud/heart/poisoned_full_blinking"),
                Identifier.withDefaultNamespace("hud/heart/poisoned_half"),
                Identifier.withDefaultNamespace("hud/heart/poisoned_half_blinking"),
                Identifier.withDefaultNamespace("hud/heart/poisoned_hardcore_full"),
                Identifier.withDefaultNamespace("hud/heart/poisoned_hardcore_full_blinking"),
                Identifier.withDefaultNamespace("hud/heart/poisoned_hardcore_half"),
                Identifier.withDefaultNamespace("hud/heart/poisoned_hardcore_half_blinking")),
        WITHERED(
                Identifier.withDefaultNamespace("hud/heart/withered_full"),
                Identifier.withDefaultNamespace("hud/heart/withered_full_blinking"),
                Identifier.withDefaultNamespace("hud/heart/withered_half"),
                Identifier.withDefaultNamespace("hud/heart/withered_half_blinking"),
                Identifier.withDefaultNamespace("hud/heart/withered_hardcore_full"),
                Identifier.withDefaultNamespace("hud/heart/withered_hardcore_full_blinking"),
                Identifier.withDefaultNamespace("hud/heart/withered_hardcore_half"),
                Identifier.withDefaultNamespace("hud/heart/withered_hardcore_half_blinking")),
        FROZEN(
                Identifier.withDefaultNamespace("hud/heart/frozen_full"),
                Identifier.withDefaultNamespace("hud/heart/frozen_full_blinking"),
                Identifier.withDefaultNamespace("hud/heart/frozen_half"),
                Identifier.withDefaultNamespace("hud/heart/frozen_half_blinking"),
                Identifier.withDefaultNamespace("hud/heart/frozen_hardcore_full"),
                Identifier.withDefaultNamespace("hud/heart/frozen_hardcore_full_blinking"),
                Identifier.withDefaultNamespace("hud/heart/frozen_hardcore_half"),
                Identifier.withDefaultNamespace("hud/heart/frozen_hardcore_half_blinking")),
        ABSORBING(
                Identifier.withDefaultNamespace("hud/heart/absorbing_full"),
                Identifier.withDefaultNamespace("hud/heart/absorbing_full_blinking"),
                Identifier.withDefaultNamespace("hud/heart/absorbing_half"),
                Identifier.withDefaultNamespace("hud/heart/absorbing_half_blinking"),
                Identifier.withDefaultNamespace("hud/heart/absorbing_hardcore_full"),
                Identifier.withDefaultNamespace("hud/heart/absorbing_hardcore_full_blinking"),
                Identifier.withDefaultNamespace("hud/heart/absorbing_hardcore_half"),
                Identifier.withDefaultNamespace("hud/heart/absorbing_hardcore_half_blinking"));

        private final Identifier full;
        private final Identifier fullBlinking;
        private final Identifier half;
        private final Identifier halfBlinking;
        private final Identifier hardcoreFull;
        private final Identifier hardcoreFullBlinking;
        private final Identifier hardcoreHalf;
        private final Identifier hardcoreHalfBlinking;

        BotHeartType(Identifier full, Identifier fullBlinking, Identifier half, Identifier halfBlinking,
                     Identifier hardcoreFull, Identifier hardcoreFullBlinking,
                     Identifier hardcoreHalf, Identifier hardcoreHalfBlinking) {
            this.full = full;
            this.fullBlinking = fullBlinking;
            this.half = half;
            this.halfBlinking = halfBlinking;
            this.hardcoreFull = hardcoreFull;
            this.hardcoreFullBlinking = hardcoreFullBlinking;
            this.hardcoreHalf = hardcoreHalf;
            this.hardcoreHalfBlinking = hardcoreHalfBlinking;
        }

        Identifier getSprite(boolean isHardcore, boolean isHalf, boolean isBlink) {
            if (!isHardcore) {
                if (isHalf) {
                    return isBlink ? this.halfBlinking : this.half;
                }
                return isBlink ? this.fullBlinking : this.full;
            }
            if (isHalf) {
                return isBlink ? this.hardcoreHalfBlinking : this.hardcoreHalf;
            }
            return isBlink ? this.hardcoreFullBlinking : this.hardcoreFull;
        }

        static BotHeartType forPlayer(net.minecraft.world.entity.player.Player player) {
            if (player.hasEffect(net.minecraft.world.effect.MobEffects.POISON)) {
                return POISONED;
            }
            if (player.hasEffect(net.minecraft.world.effect.MobEffects.WITHER)) {
                return WITHERED;
            }
            if (player.isFullyFrozen()) {
                return FROZEN;
            }
            return NORMAL;
        }
    }

    /**
     * 状态 Tab 经验条：与主玩家 HUD 同款（原版 sprite 背景 182x5 + 进度裁剪 +
     * ContextualBar 同款绿色等级文字）。
     */
    private static void drawXpBar(BotControlScreen screen, GuiGraphicsExtractor graphics, int y) {
        net.minecraft.client.player.LocalPlayer player = screen.selected.getLocalPlayer();
        if (player == null) {
            return;
        }
        // 面板内居中放原版 182x5 经验条
        // 经验条宽度自适应面板（最大原版 182，小面板自动缩窄，不溢出背景）
        int barW = Math.min(182, BotControlScreen.CONTENT_W - 4);
        int x = screen.sx(BotControlScreen.CONTENT_X + (BotControlScreen.CONTENT_W - barW) / 2);
        if (player.getXpNeededForNextLevel() > 0) {
            graphics.blitSprite(RenderPipelines.GUI_TEXTURED, BotControlHud.XP_BAR_BACKGROUND, x, y, barW, 5);
            int progress = (int) (player.experienceProgress * (barW + 1));
            if (progress > 0) {
                // 原版裁剪：从进度 sprite 裁 0..progress 宽
                graphics.blitSprite(RenderPipelines.GUI_TEXTURED, BotControlHud.XP_BAR_PROGRESS,
                        barW, 5, 0, 0, x, y, progress, 5);
            }
        }
        // 等级文字：ContextualBar.extractExperienceLevel 同款（绿色 + 四向黑阴影）
        if (player.experienceLevel > 0) {
            Component level = Component.translatable("gui.experience.level", player.experienceLevel);
            int tx = x + (barW - screen.font().width(level)) / 2;
            int ty = y - 11;
            graphics.text(screen.font(), level, tx + 1, ty, -16777216, false);
            graphics.text(screen.font(), level, tx - 1, ty, -16777216, false);
            graphics.text(screen.font(), level, tx, ty + 1, -16777216, false);
            graphics.text(screen.font(), level, tx, ty - 1, -16777216, false);
            graphics.text(screen.font(), level, tx, ty, -8323296, false);
        }
        com.mockplayer.gui.BotGui.recordXpBar(player.experienceLevel, player.experienceProgress);
    }

}
