package com.adimn.autocraft.ui;

import com.adimn.autocraft.craft.TreeDriver;
import com.adimn.autocraft.plan.MaterialRef;
import com.adimn.autocraft.trigger.OrderTrigger;
import com.adimn.autocraft.util.Log;

import dev.emi.emi.api.recipe.EmiRecipe;
import dev.emi.emi.api.stack.EmiStack;
import dev.emi.emi.runtime.EmiDrawContext;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.registries.ForgeRegistries;

/**
 * EMI 配方页右侧按钮列的「自动合成」按钮（Screen 层 addDrawableChild 版本）。
 *
 * **继承链**：net.minecraft.client.gui.components.Button → 自带 GuiEventListener
 * （mouseClicked/isMouseOver/mouseReleased 都有），可直接 event.addListener
 * 注册到 RecipeScreen.renderables，**不受 RecipeScreen.setPage 重建 currentPage 影响**。
 *
 * **渲染**（override renderWidget）：用 dev.emi.emi.runtime.EmiDrawContext 画 12x12 图标 +
 * hover 高亮（isHovered() 切换 normal/hover 纹理偏移），与 EMI 原版 RecipeButtonWidget 同款。
 *
 * **点击**（构造时 OnPress lambda）：触发 triggerAutoCraft → OrderTrigger.order。
 */
public final class AutoCraftRecipeButton extends Button {
    private static final ResourceLocation BUTTONS_TEXTURE =
            new ResourceLocation("autocraft", "textures/gui/buttons.png");
    /** 按钮渲染尺寸（12x12，与 EMI 原版图标按钮一致）。 */
    public static final int RENDER_SIZE = 12;
    private static final int TEX_SIZE = 16;

    private final EmiRecipe recipe;

    public AutoCraftRecipeButton(int x, int y, EmiRecipe recipe) {
        super(x, y, RENDER_SIZE, RENDER_SIZE, Component.empty(),
                b -> triggerAutoCraft(((AutoCraftRecipeButton) b).recipe),
                DEFAULT_NARRATION);
        this.recipe = recipe;
        setTooltip(Tooltip.create(
                Component.translatable("autocraft.tooltip.autocraft")));
    }

    /** hover 高亮偏移：返回 16（normal=0, hover=16），与 EMI 原版同步。 */
    private int getTextureOffset() {
        return isHovered() ? TEX_SIZE : 0;
    }

    @Override
    public void renderWidget(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        EmiDrawContext context = EmiDrawContext.wrap(guiGraphics);
        context.resetColor();
        int u = getTextureOffset();
        // 源图是 32x16（两个 16x16 图标），绘制时缩放到 12x12。
        context.drawTexture(BUTTONS_TEXTURE, getX(), getY(), RENDER_SIZE, RENDER_SIZE,
                u, 0, TEX_SIZE, TEX_SIZE, TEX_SIZE * 2, TEX_SIZE);
    }

    /** 自动合成：取配方首个产出 → 关闭配方屏 → 新管线下单。 */
    private static void triggerAutoCraft(EmiRecipe recipe) {
        ItemStack output = null;
        for (EmiStack stack : recipe.getOutputs()) {
            ItemStack candidate = stack.getItemStack();
            if (!candidate.isEmpty()) {
                output = candidate;
                break;
            }
        }
        if (output == null) {
            TreeDriver.chat("该配方没有可识别的产出，无法下单。");
            Log.warn("自动合成按钮失败：配方无产出 " + recipe);
            return;
        }
        ResourceLocation outputKey = ForgeRegistries.ITEMS.getKey(output.getItem());
        if (outputKey == null) {
            TreeDriver.chat("无法识别产出物品，无法下单。");
            return;
        }
        if (RecipeScreenHolder.back()) {
            OrderTrigger.order(MaterialRef.of(outputKey.toString()), 1);
            Log.info("点击自动合成按钮，目标=" + outputKey);
        }
    }
}