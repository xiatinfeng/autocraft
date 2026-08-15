package com.adimn.autocraft.compat;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

/**
 * 12x12 齿轮图标按钮（EMI/JEI 配方屏通用）。
 *
 * 渲染与 EMI 原版 12x12 图标按钮一致：无边框纯图标（透明底白齿轮），
 * hover 时切换纯白态（纹理图 buttons.png 两行：normal 灰白 / hover 纯白）。
 * 源图 16x16，渲染缩放 12x12。
 */
public class GearButton extends Button {
    private static final ResourceLocation BUTTONS_TEXTURE =
            new ResourceLocation("autocraft", "textures/gui/buttons.png");
    /** 按钮渲染尺寸（12x12，与 EMI 原版图标按钮一致）。 */
    public static final int BUTTON_SIZE = 12;
    private static final int TEX_SIZE = 16;

    public GearButton(int x, int y, OnPress onPress) {
        super(x, y, BUTTON_SIZE, BUTTON_SIZE, Component.empty(), onPress, DEFAULT_NARRATION);
    }

    @Override
    public void renderWidget(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        boolean hovered = mouseX >= getX() && mouseY >= getY()
                && mouseX < getX() + width && mouseY < getY() + height;
        int u = hovered ? TEX_SIZE : 0;   // hover 用纯白态
        // blit(tex, x, y, width, height, uOffset, vOffset, uWidth, vHeight, texWidth, texHeight)
        guiGraphics.blit(BUTTONS_TEXTURE, getX(), getY(), BUTTON_SIZE, BUTTON_SIZE,
                u, 0, TEX_SIZE, TEX_SIZE, TEX_SIZE * 2, TEX_SIZE);
    }
}
