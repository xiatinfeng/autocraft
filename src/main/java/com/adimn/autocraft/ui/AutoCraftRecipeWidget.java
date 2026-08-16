package com.adimn.autocraft.ui;

import java.util.List;

import com.adimn.autocraft.craft.CraftExecutor;
import com.adimn.autocraft.plan.MaterialRef;
import com.adimn.autocraft.trigger.OrderTrigger;
import com.adimn.autocraft.util.Log;

import dev.emi.emi.api.recipe.EmiRecipe;
import dev.emi.emi.api.stack.EmiStack;
import dev.emi.emi.api.widget.Bounds;
import dev.emi.emi.api.widget.DrawableWidget;
import dev.emi.emi.runtime.EmiDrawContext;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.tooltip.ClientTooltipComponent;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.registries.ForgeRegistries;

/**
 * EMI 配方屏右侧按钮列中的「自动合成」齿轮按钮（EMI Widget 版）。
 *
 * 继承 EMI 公共 API {@link DrawableWidget}（其渲染已实现，不触碰 MC 接口方法），
 * 由 {@code RecipeDisplayMixin} 注入到 RecipeDisplay.addButtons 的右侧按钮列，
 * 作为 WidgetGroup 的一部分被 EMI 渲染/点击/悬停，和 FILL/TREE/DEFAULT 完全同构。
 */
public final class AutoCraftRecipeWidget extends DrawableWidget {
    private static final ResourceLocation BUTTONS_TEXTURE =
            new ResourceLocation("autocraft", "textures/gui/buttons.png");
    private static final int SIZE = 12;
    private static final int TEX_SIZE = 16;

    private final EmiRecipe recipe;

    public AutoCraftRecipeWidget(EmiRecipe recipe, int x, int y) {
        super(x, y, SIZE, SIZE, AutoCraftRecipeWidget::drawButton);
        this.recipe = recipe;
        tooltip((mouseX, mouseY) -> getBounds().contains(mouseX, mouseY)
                ? List.of(ClientTooltipComponent.create(
                        Component.translatable("autocraft.tooltip.autocraft").getVisualOrderText()))
                : List.of());
    }

    /**
     * 渲染回调：DrawableWidget 已平移到本按钮局部原点，直接画 0,0 即可。
     * hover 判断：鼠标是 WidgetGroup 局部坐标，从 PoseStack 顶部矩阵读取
     * DrawableWidget 施加的 (x,y) 平移，换算出本按钮的局部命中区。
     */
    private static void drawButton(GuiGraphics guiGraphics, int mouseX, int mouseY, float delta) {
        EmiDrawContext context = EmiDrawContext.wrap(guiGraphics);
        context.resetColor();
        int u = 0;
        try {
            var matrix = context.matrices().last().pose();
            float tx = matrix.m03();
            float ty = matrix.m13();
            if (mouseX >= tx && mouseX < tx + SIZE && mouseY >= ty && mouseY < ty + SIZE) {
                u = TEX_SIZE;
            }
        } catch (Throwable ignored) {
            // 无法读取变换时保持普通图标
        }
        // 源图是 32x16（两个 16x16 图标），绘制时缩放到 12x12。
        context.drawTexture(BUTTONS_TEXTURE, 0, 0, SIZE, SIZE,
                u, 0, TEX_SIZE, TEX_SIZE, TEX_SIZE * 2, TEX_SIZE);
    }

    @Override
    public Bounds getBounds() {
        return bounds;
    }

    @Override
    public boolean mouseClicked(int mouseX, int mouseY, int button) {
        if (button != 0 || !getBounds().contains(mouseX, mouseY)) {
            return false;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.getSoundManager() != null) {
            mc.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK, 1.0F));
        }
        triggerAutoCraft(recipe);
        return true;
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
            CraftExecutor.chat("该配方没有可识别的产出，无法下单。");
            Log.warn("自动合成按钮失败：配方无产出 " + recipe);
            return;
        }
        ResourceLocation outputKey = ForgeRegistries.ITEMS.getKey(output.getItem());
        if (outputKey == null) {
            CraftExecutor.chat("无法识别产出物品，无法下单。");
            return;
        }
        if (RecipeScreenHolder.back()) {
            OrderTrigger.order(MaterialRef.of(outputKey.toString()), 1);
            Log.info("点击自动合成按钮，目标=" + outputKey);
        }
    }
}
