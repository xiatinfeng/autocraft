package com.adimn.autocraft.ui;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.adimn.autocraft.craft.CraftExecutor;
import com.adimn.autocraft.plan.MaterialRef;
import com.adimn.autocraft.plan.PlanTree;
import com.adimn.autocraft.plan.PureSearchPlanner.Result;
import com.adimn.autocraft.trigger.OrderTrigger;
import com.adimn.autocraft.util.Log;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.registries.ForgeRegistries;

/**
 * 执行前计划预览（M6c 缩放/平移/悬停版）：
 *   - 层叠树布局：每层横排，T 型连接线（父竖线 → 横线 → 子竖线），底部独立"总耗材"行。
 *   - 缩放：滚轮围绕鼠标位置缩放（0.3x ~ 3.0x），`pose()` 矩阵变换，图标/线/字整体缩放。
 *   - 平移：按住左键拖拽平移（tree 区域空白处）。
 *   - 悬停：鼠标悬停节点显示 tooltip（物品显示名 + 数量 + 状态 + 配方短名）。
 *
 * 树布局 = 简化 Reingold-Tilford（后序遍历算中心 x，父居中于子范围）。
 * 纯前端 MC 渲染（pose 变换 + renderItem + fill + renderTooltip），零 EMI/JEI 运行时依赖。
 */
public final class PlanPreviewScreen extends Screen {
    private static final int TITLE_COLOR = 0xFFFFFF;
    private static final int SUBTLE_COLOR = 0xA0A0A0;
    private static final int DEFAULT_TEXT = 0xE0E0E0;
    private static final int LINE_COLOR = 0xFF606060;   // 连接线灰
    private static final int LINE_WIDTH = 2;           // 连接线宽（>=2 才明显）
    private static final int ICON_SIZE = 18;
    private static final int H_GAP = 16;
    private static final int V_GAP = 36;
    private static final int CONTENT_TOP = 70;
    private static final int TOTAL_SECTION_PAD = 16;
    private static final int BOTTOM_BUTTON_RESERVE = 56;
    private static final double MIN_ZOOM = 0.3;
    private static final double MAX_ZOOM = 3.0;
    private static final double ZOOM_STEP = 1.1;

    private static final int[] QUANTITIES = {1, 4, 16, 64};

    private final Screen previous;
    private final MaterialRef target;
    private int quantity;
    private Result result;
    private PlanTree.TreeNode root;
    private Map<MaterialRef, Long> totals;
    private String error;

    // 视图状态（缩放 + 平移）
    private double zoom = 1.0;
    private double panX;
    private double panY;
    private boolean viewInitialized;
    private PlanTree.TreeNode hoveredNode;   // 当前悬停节点（tooltip）
    private Map.Entry<MaterialRef, Long> hoveredTotal;   // 当前悬停的总耗材（tooltip）

    public PlanPreviewScreen(Screen previous, MaterialRef target, int quantity,
                             Result result, PlanTree.TreeNode root) {
        super(Component.literal("AutoCraft 计划预览"));
        this.previous = previous;
        this.target = target;
        this.quantity = Math.max(1, quantity);
        this.result = result;
        this.root = root;
        this.totals = root == null ? Map.of() : PlanTree.totalLeafDemand(root);
    }

    @Override
    protected void init() {
        int qx = 40;
        for (int q : QUANTITIES) {
            int finalQ = q;
            addRenderableWidget(Button.builder(Component.literal("×" + q), button -> setQuantity(finalQ))
                    .bounds(qx, 40, 34, 18).build());
            qx += 40;
        }
        int buttonWidth = 90;
        int buttonHeight = 20;
        int gap = 8;
        int startX = width / 2 - buttonWidth - gap / 2;
        int cancelX = width / 2 + gap / 2;
        int buttonY = height - 40;
        addRenderableWidget(Button.builder(Component.literal("§a开始合成"), button -> confirm())
                .bounds(startX, buttonY, buttonWidth, buttonHeight).build());
        addRenderableWidget(Button.builder(Component.literal("取消"), button -> back())
                .bounds(cancelX, buttonY, buttonWidth, buttonHeight).build());
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        renderBackground(g);
        g.drawCenteredString(font, "自动合成计划：" + target.itemId() + "  ×" + quantity,
                width / 2, 18, TITLE_COLOR);
        g.drawCenteredString(font, "滚轮缩放 · 左键拖拽平移 · 悬停查看物品", width / 2, 34, SUBTLE_COLOR);
        if (error != null) {
            g.drawCenteredString(font, error, width / 2, 58, 0xFF5555);
            super.render(g, mouseX, mouseY, partialTick);
            return;
        }
        int maxY = height - BOTTOM_BUTTON_RESERVE;
        hoveredNode = null;
        hoveredTotal = null;
        if (root != null) {
            renderLayeredTree(g, root, maxY, mouseX, mouseY);
            // tooltip 必须在 pose.popPose() 之后（屏幕坐标）
            if (hoveredNode != null) {
                renderNodeTooltip(g, hoveredNode, mouseX, mouseY);
            } else if (hoveredTotal != null) {
                renderTotalTooltip(g, hoveredTotal, mouseX, mouseY);
            }
        } else if (result != null && result.feasible()) {
            renderFlatFallback(g, maxY);
        }
        super.render(g, mouseX, mouseY, partialTick);
    }

    // ------------------------------------------------------------------
    // 鼠标交互：滚轮缩放（围绕鼠标）+ 拖拽平移
    // ------------------------------------------------------------------

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double amount) {
        if (root == null) {
            return super.mouseScrolled(mouseX, mouseY, amount);
        }
        ensureView();
        double factor = amount > 0 ? ZOOM_STEP : 1.0 / ZOOM_STEP;
        double newZoom = clamp(zoom * factor, MIN_ZOOM, MAX_ZOOM);
        if (newZoom == zoom) {
            return true;
        }
        // 保持鼠标下的布局点不动：layout = (screen - pan) / zoom
        double layoutX = (mouseX - panX) / zoom;
        double layoutY = (mouseY - panY) / zoom;
        zoom = newZoom;
        panX = mouseX - layoutX * zoom;
        panY = mouseY - layoutY * zoom;
        return true;
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dx, double dy) {
        if (button == 0 && root != null && !isOverButton(mouseX, mouseY)) {
            panX += dx;
            panY += dy;
            return true;
        }
        return super.mouseDragged(mouseX, mouseY, button, dx, dy);
    }

    /** 判断鼠标是否在控件（数量/按钮）上——拖拽平移只对树区域生效。 */
    private boolean isOverButton(double mouseX, double mouseY) {
        for (var child : children()) {
            if (child instanceof AbstractWidget widget && widget.isMouseOver(mouseX, mouseY)) {
                return true;
            }
        }
        return false;
    }

    // ------------------------------------------------------------------
    // 层叠树渲染：布局 → 连接线 → 节点 → 总耗材行
    // ------------------------------------------------------------------

    private void renderLayeredTree(GuiGraphics g, PlanTree.TreeNode root, int maxY,
                                   int mouseX, int mouseY) {
        // 缓存鼠标坐标（renderTotalsRow 也要用）
        this.mouseXCache = mouseX;
        this.mouseYCache = mouseY;
        ensureView();
        List<List<PlanTree.TreeNode>> levels = collectLevels(root);
        Map<PlanTree.TreeNode, Double> centers = new HashMap<>();
        layoutPositions(root, 0.0, centers);

        // y 坐标：每层一行
        Map<PlanTree.TreeNode, Integer> ys = new HashMap<>();
        for (int i = 0; i < levels.size(); i++) {
            int y = CONTENT_TOP + i * V_GAP;
            for (PlanTree.TreeNode n : levels.get(i)) {
                ys.put(n, y);
            }
        }

        // 视图变换：先平移再缩放（布局坐标 → 屏幕坐标）
        var pose = g.pose();
        pose.pushPose();
        pose.translate((float) panX, (float) panY, 0);
        pose.scale((float) zoom, (float) zoom, 1.0f);

        // 画连接线（在节点之前，避免覆盖图标）
        for (PlanTree.TreeNode n : centers.keySet()) {
            drawConnections(g, n, centers, ys);
        }

        // 画节点 + 收集悬停命中（布局坐标转屏幕坐标判断）
        for (PlanTree.TreeNode n : centers.keySet()) {
            int cx = (int) Math.round(centers.get(n));
            int y = ys.get(n);
            if (y + ICON_SIZE <= maxY) {
                drawNode(g, n, cx, y);
                if (hoveredNode == null && hitTest(cx, y, mouseX, mouseY)) {
                    hoveredNode = n;
                }
            }
        }

        // 总耗材行
        int treeBottom = CONTENT_TOP + levels.size() * V_GAP;
        int totalY = treeBottom + TOTAL_SECTION_PAD;
        if (totalY + ICON_SIZE <= maxY && !totals.isEmpty()) {
            renderTotalsRow(g, totalY, maxY);
        }

        pose.popPose();
    }

    /** 布局坐标 (cx, y) 的节点区域是否命中屏幕鼠标。 */
    private boolean hitTest(int cx, int y, double mouseX, double mouseY) {
        double sx = cx * zoom + panX;
        double sy = y * zoom + panY;
        double half = ICON_SIZE * zoom / 2.0 + 2.0;   // 命中区比图标略大
        return mouseX >= sx - half && mouseX <= sx + half
                && mouseY >= sy - half && mouseY <= sy + half;
    }

    /** 首次渲染时把树居中于屏幕（视口初始化一次）。 */
    private void ensureView() {
        if (viewInitialized || root == null) {
            return;
        }
        Map<PlanTree.TreeNode, Double> centers = new HashMap<>();
        double treeWidth = layoutPositions(root, 0.0, centers);
        panX = (width - treeWidth) / 2.0;
        panY = 40.0;
        viewInitialized = true;
    }

    /** BFS 分层：levels[0]=根，levels[1]=根的子层，...。 */
    private List<List<PlanTree.TreeNode>> collectLevels(PlanTree.TreeNode root) {
        List<List<PlanTree.TreeNode>> levels = new ArrayList<>();
        Deque<PlanTree.TreeNode> queue = new ArrayDeque<>();
        queue.add(root);
        while (!queue.isEmpty()) {
            int size = queue.size();
            List<PlanTree.TreeNode> level = new ArrayList<>(size);
            for (int i = 0; i < size; i++) {
                PlanTree.TreeNode n = queue.poll();
                level.add(n);
                if (n.children() != null) {
                    queue.addAll(n.children());
                }
            }
            levels.add(level);
        }
        return levels;
    }

    /** Reingold-Tilford 简化：后序遍历算每个节点中心 x，子树宽度 = max(节点宽, 子树宽之和 + gap)。 */
    private double layoutPositions(PlanTree.TreeNode node, double leftBoundary,
                                   Map<PlanTree.TreeNode, Double> centers) {
        if (node.children() == null || node.children().isEmpty()) {
            double center = leftBoundary + ICON_SIZE / 2.0;
            centers.put(node, center);
            return ICON_SIZE;
        }
        double cur = leftBoundary;
        double firstCenter = -1;
        double lastCenter = -1;
        for (PlanTree.TreeNode child : node.children()) {
            double childWidth = layoutPositions(child, cur, centers);
            double childCenter = centers.get(child);
            if (firstCenter < 0) {
                firstCenter = childCenter;
            }
            lastCenter = childCenter;
            cur += childWidth + H_GAP;
        }
        double totalWidth = cur - leftBoundary - H_GAP;
        double center = (firstCenter + lastCenter) / 2.0;
        centers.put(node, center);
        return Math.max(ICON_SIZE, totalWidth);
    }

    /** 画一个节点的 T 型连接线：父底部 → 子顶部的三段（竖线 + 横线 + 竖线）。 */
    private void drawConnections(GuiGraphics g, PlanTree.TreeNode node,
                                  Map<PlanTree.TreeNode, Double> centers,
                                  Map<PlanTree.TreeNode, Integer> ys) {
        if (node.children() == null || node.children().isEmpty()) {
            return;
        }
        int parentCx = (int) Math.round(centers.get(node));
        int parentBottom = ys.get(node) + ICON_SIZE;
        PlanTree.TreeNode firstChild = node.children().get(0);
        int childTop = ys.get(firstChild);
        int midY = (parentBottom + childTop) / 2;
        int half = LINE_WIDTH / 2;

        // 父竖线（LINE_WIDTH 居中在 parentCx）
        g.fill(parentCx - half, parentBottom, parentCx + half + 1, midY + 1, LINE_COLOR);
        // 子顶部竖线（每个子一条）
        for (PlanTree.TreeNode child : node.children()) {
            int cx = (int) Math.round(centers.get(child));
            g.fill(cx - half, midY, cx + half + 1, childTop, LINE_COLOR);
        }
        // 横线（连接第一个子到最后一个子，LINE_WIDTH 厚）
        int firstCx = (int) Math.round(centers.get(firstChild));
        int lastCx = (int) Math.round(centers.get(node.children().get(node.children().size() - 1)));
        g.fill(firstCx - half, midY - half, lastCx + half + 2, midY + half + 1, LINE_COLOR);
    }

    /** 画一个节点：物品图标 + 数量标注。 */
    private void drawNode(GuiGraphics g, PlanTree.TreeNode node, int centerX, int y) {
        int itemX = centerX - ICON_SIZE / 2;
        drawItemIcon(g, node.material().itemId(), itemX, y);
        g.drawString(font, "×" + node.amount(), itemX + ICON_SIZE - 2, y + ICON_SIZE - 8,
                stateTextColor(node.state()));
    }

    private void renderTotalsRow(GuiGraphics g, int y, int maxY) {
        g.drawString(font, "总耗材", 0, y, TITLE_COLOR);
        g.drawString(font, "(聚合)", font.width("总耗材") + 4, y, SUBTLE_COLOR);
        int ty = y + ICON_SIZE + 4;
        if (ty + ICON_SIZE > maxY) {
            return;
        }
        int tx = 0;
        for (Map.Entry<MaterialRef, Long> e : totals.entrySet()) {
            drawItemIcon(g, e.getKey().itemId(), tx, ty);
            g.drawString(font, "×" + e.getValue(), tx + ICON_SIZE - 2, ty + ICON_SIZE - 8,
                    SUBTLE_COLOR);
            // hover 检测（布局坐标 → 屏幕坐标）
            if (hoveredTotal == null && hitRect(tx, ty, ICON_SIZE, ICON_SIZE, mouseXCache, mouseYCache)) {
                hoveredTotal = e;
            }
            tx += ICON_SIZE + H_GAP;
        }
    }

    private void renderTotalTooltip(GuiGraphics g, Map.Entry<MaterialRef, Long> entry,
                                     int mouseX, int mouseY) {
        List<Component> lines = new ArrayList<>();
        String displayName = displayNameOf(entry.getKey());
        lines.add(Component.literal(displayName + " ×" + entry.getValue() + "（总耗材）")
                .withStyle(ChatFormatting.WHITE));
        lines.add(Component.literal("注册名: " + entry.getKey().itemId())
                .withStyle(ChatFormatting.DARK_GRAY));
        g.renderTooltip(font, lines, Optional.empty(), mouseX, mouseY);
    }

    /** 缓存鼠标坐标，避免 renderTotalsRow 调用 hitTest 时再传参数。 */
    private int mouseXCache, mouseYCache;

    /** 矩形命中检测（布局坐标 → 屏幕坐标 + 命中区）。 */
    private boolean hitRect(int lx, int ly, int w, int h, double mouseX, double mouseY) {
        double sx = lx * zoom + panX;
        double sy = ly * zoom + panY;
        double sw = w * zoom;
        double sh = h * zoom;
        return mouseX >= sx - 2 && mouseX <= sx + sw + 2
                && mouseY >= sy - 2 && mouseY <= sy + sh + 2;
    }

    /** 悬停 tooltip：物品显示名 + 数量 + 状态 + 配方短名。 */
    private void renderNodeTooltip(GuiGraphics g, PlanTree.TreeNode node, int mouseX, int mouseY) {
        List<Component> lines = new ArrayList<>();
        String displayName = displayNameOf(node.material());
        String suffix = node.state() == PlanTree.State.HAS ? "（库存满足）"
                : node.state() == PlanTree.State.PARTIAL ? "（部分满足）"
                : node.state() == PlanTree.State.MISSING ? "（缺失）" : "（需生产）";
        lines.add(Component.literal(displayName + " ×" + node.amount() + " " + suffix)
                .withStyle(ChatFormatting.WHITE));
        if (node.recipeId() != null) {
            lines.add(Component.literal("配方: " + shortRecipe(node.recipeId()))
                    .withStyle(ChatFormatting.GRAY));
        }
        lines.add(Component.literal("注册名: " + node.material().itemId())
                .withStyle(ChatFormatting.DARK_GRAY));
        g.renderTooltip(font, lines, Optional.empty(), mouseX, mouseY);
    }

    private void renderFlatFallback(GuiGraphics g, int maxY) {
        int y = CONTENT_TOP;
        g.drawString(font, "（无法构建配方树，平面步骤）", 0, y, SUBTLE_COLOR);
        y += 16;
        for (int i = 0; i < result.steps().size() && y < maxY; i++) {
            g.drawString(font, (i + 1) + ".  " + result.steps().get(i).recipeId()
                    + "  ×" + result.steps().get(i).batches(), 0, y, DEFAULT_TEXT);
            y += 14;
        }
    }

    // ------------------------------------------------------------------
    // 工具
    // ------------------------------------------------------------------

    private void drawItemIcon(GuiGraphics g, String itemId, int x, int y) {
        try {
            Item item = ForgeRegistries.ITEMS.getValue(ResourceLocation.tryParse(itemId));
            if (item == null || item == net.minecraft.world.item.Items.AIR) {
                return;
            }
            g.renderItem(new ItemStack(item), x, y);
        } catch (Exception ignored) {
            // 单个物品渲染失败不应阻塞整棵树
        }
    }

    /** 物品显示名（本地化）。失败时回退注册名短名。 */
    private static String displayNameOf(MaterialRef material) {
        try {
            Item item = ForgeRegistries.ITEMS.getValue(ResourceLocation.tryParse(material.itemId()));
            if (item == null) {
                return material.itemId();
            }
            return item.getName(new ItemStack(item)).getString();
        } catch (Exception e) {
            return material.itemId();
        }
    }

    private static String shortRecipe(String recipeId) {
        int colon = recipeId.indexOf(':');
        return colon >= 0 ? recipeId.substring(colon + 1) : recipeId;
    }

    private static int stateTextColor(PlanTree.State state) {
        return switch (state) {
            case HAS -> 0x55FF55;
            case PARTIAL -> 0xFFFF55;
            case MISSING -> 0xFF5555;
            case CRAFT -> 0xE0E0E0;
        };
    }

    private static double clamp(double v, double min, double max) {
        return Math.max(min, Math.min(max, v));
    }

    private void setQuantity(int q) {
        quantity = q;
        Log.debug("预览数量切换为 " + q);
        result = OrderTrigger.plan(target, quantity);
        if (result == null) {
            error = "规划失败";
            root = null;
            totals = Map.of();
            viewInitialized = false;
            return;
        }
        root = OrderTrigger.buildTree(target, quantity, result);
        totals = root == null ? Map.of() : PlanTree.totalLeafDemand(root);
        viewInitialized = false;   // 树变了，重新居中
        if (!result.feasible()) {
            error = "数量 " + quantity + " 时不可行：" + OrderTrigger.failureSummary(result);
        } else {
            error = null;
        }
    }

    private void confirm() {
        if (result == null || !result.feasible()) {
            CraftExecutor.chat("当前数量下不可行，未开始执行。");
            return;
        }
        Log.info("计划预览确认开始：" + target + " x" + quantity + "，" + result.steps().size() + " 步");
        back();
        CraftExecutor.start(result.steps());
    }

    private void back() {
        Minecraft mc = Minecraft.getInstance();
        mc.setScreen(previous);
    }

    @Override
    public void onClose() {
        back();
    }
}