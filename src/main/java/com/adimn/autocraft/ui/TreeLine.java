package com.adimn.autocraft.ui;

/**
 * 配方树展示行（EMI-free 值对象）：缩进深度 + 文本 + 颜色。
 * 由 OrderTrigger 在 EMI 存在时把 MaterialTree 压平成这些行，
 * PlanPreviewScreen 只认这个结构，不直接依赖 EMI 类型（无 EMI 也不崩）。
 */
public record TreeLine(int depth, String text, int color) {
}
