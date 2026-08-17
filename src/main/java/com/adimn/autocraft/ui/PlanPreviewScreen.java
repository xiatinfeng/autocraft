package com.adimn.autocraft.ui;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import com.adimn.autocraft.config.Config;
import com.adimn.autocraft.craft.CraftExecutor;
import com.adimn.autocraft.plan.ImmutableRecipeGraph;
import com.adimn.autocraft.plan.MaterialRef;
import com.adimn.autocraft.plan.PlanTree;
import com.adimn.autocraft.plan.PureSearchPlanner;
import com.adimn.autocraft.plan.PureSearchPlanner.Result;
import com.adimn.autocraft.plan.RecipeNode;
import com.adimn.autocraft.trigger.OrderTrigger;
import com.adimn.autocraft.util.FavoriteAdder;
import com.adimn.autocraft.util.Log;
import com.adimn.autocraft.util.ManualProcessing;
import com.adimn.autocraft.util.NbtDisplay;
import com.adimn.autocraft.util.ProcessingIconProvider;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;

import org.lwjgl.glfw.GLFW;
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
    private static final int TOTALS_REGION_WIDTH = 80;
    private static final int TOTALS_MARGIN = 8;
    private static final int TOTALS_ITEM_GAP = 6;
    private static final double MIN_ZOOM = 0.15;
    private static final double MAX_ZOOM = 3.0;
    private static final double ZOOM_STEP = 1.1;

    private static final int[] QUANTITIES = {1, 4, 16, 64};

    private final Screen previous;
    private final MaterialRef target;
    private final ImmutableRecipeGraph graph;
    private final Map<MaterialRef, Integer> stock;
    private final Set<String> chosenRecipes;
    private final Set<String> expandedMaterials = new HashSet<>();
    private int quantity;
    private Result result;
    private PlanTree.TreeNode root;
    private Map<MaterialRef, Long> totals;
    private String error;
    private EditBox quantityInput;
    private double totalsScrollY;

    // 视图状态（缩放 + 平移）
    private double zoom = 1.0;
    private double panX;
    private double panY;
    private boolean viewInitialized;
    private PlanTree.TreeNode hoveredNode;   // 当前悬停节点（tooltip）
    private Map.Entry<MaterialRef, Long> hoveredTotal;   // 当前悬停的总耗材（tooltip）
    private boolean danglingProbeLogged;     // 深探针：每个预览界面只记录第一次异常
    private int renderSkipLogged;            // 渲染跳过探针计数
    private int unknownItemLogged;           // 未知物品探针计数
    private int connectorSkipLogged;         // 连线跳过探针计数

    /** 树布局缓存：只在树结构变化（首次打开 / 数量切换）时重建，渲染期只读。 */
    private TreeLayout layout;
    /** 物品注册表缓存：避免每帧每节点 ForgeRegistries 查找。 */
    private final Map<String, Item> itemCache = new HashMap<>();

    public PlanPreviewScreen(Screen previous, MaterialRef target, int quantity,
                             Result result, PlanTree.TreeNode root,
                             Map<MaterialRef, Integer> stock, ImmutableRecipeGraph graph) {
        super(Component.literal("AutoCraft 计划预览"));
        this.previous = previous;
        this.target = target;
        this.quantity = Math.max(1, quantity);
        this.result = result;
        this.root = root;
        this.stock = stock == null ? Map.of() : stock;
        this.graph = graph;
        this.chosenRecipes = new HashSet<>();
        if (result != null && result.feasible()) {
            for (PureSearchPlanner.PlannedStep step : result.steps()) {
                chosenRecipes.add(step.recipeId());
            }
        }
        this.totals = root == null ? Map.of() : PlanTree.totalLeafDemand(root);
        rebuildLayout();
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
        // 自定义数量输入框：输入后按回车或点击空白处更新计划/缺失材料。
        quantityInput = new EditBox(font, qx + 4, 40, 60, 18, Component.literal("数量"));
        quantityInput.setMaxLength(6);
        quantityInput.setValue(String.valueOf(quantity));
        quantityInput.setFocused(false);
        addRenderableWidget(quantityInput);
        // 清空输入框：方便重新输入自定义数量。
        addRenderableWidget(Button.builder(Component.literal("清空"), button -> {
            quantityInput.setValue("");
            quantityInput.setFocused(true);
        }).bounds(qx + 4 + 60 + 4, 40, 40, 18).build());
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
        if (result != null && !result.feasible()) {
            // 材料不足也打开预览：提示行下方继续渲染配方树，红色节点/总耗材即缺失项。
            g.drawCenteredString(font, "当前数量 " + quantity + " 不可行，红色 = 缺失材料", width / 2, 58, 0xFF5555);
        }
        if (root != null) {
            // 树使用屏幕实际高度作为裁剪边界，避免在按钮预留线处出现“虚空引用”。
            renderLayeredTree(g, root, height, mouseX, mouseY);
        } else if (result != null) {
            renderFlatFallback(g, maxY);
        }
        renderTotalsPanel(g, mouseX, mouseY);
        // tooltip 必须在 pose.popPose() 之后（屏幕坐标）
        if (hoveredNode != null) {
            renderNodeTooltip(g, hoveredNode, mouseX, mouseY);
        } else if (hoveredTotal != null) {
            renderTotalTooltip(g, hoveredTotal, mouseX, mouseY);
        }
        super.render(g, mouseX, mouseY, partialTick);
    }

    // ------------------------------------------------------------------
    // 鼠标交互：滚轮缩放（围绕鼠标）+ 拖拽平移
    // ------------------------------------------------------------------

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double amount) {
        // 总耗材区域内滚轮 → 上下滚动总耗材列表
        if (inTotalsRegion(mouseX, mouseY)) {
            totalsScrollY -= amount * 16.0;
            double maxScroll = Math.max(0.0, totalsScrollMax());
            totalsScrollY = clamp(totalsScrollY, 0.0, maxScroll);
            return true;
        }
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
        if (button == 0 && root != null && !isOverButton(mouseX, mouseY)
                && !inTotalsRegion(mouseX, mouseY)) {
            panX += dx;
            panY += dy;
            return true;
        }
        return super.mouseDragged(mouseX, mouseY, button, dx, dy);
    }

    /** 左键点击节点：展开/收起该材料的其他配方；点空白：提交数量；中键/右键：视角归正。 */
    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if ((button == 1 || button == 2) && root != null
                && !isOverButton(mouseX, mouseY) && !inTotalsRegion(mouseX, mouseY)) {
            resetViewToRoot();
            return true;
        }
        if (button == 0 && root != null && !isOverButton(mouseX, mouseY)
                && !inTotalsRegion(mouseX, mouseY)) {
            PlanTree.TreeNode clicked = findNodeAt(mouseX, mouseY);
            if (clicked != null) {
                toggleExpandedFor(clicked.material());
                return true;
            }
            // 只有输入框聚焦时才提交数量，避免拖拽起点/普通空白点击触发重建+回中。
            if (quantityInput != null && quantityInput.isFocused()) {
                commitQuantityInput();
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    /** 点击节点：若该材料有多个配方则切换“展开备选配方”；否则无操作。 */
    private void toggleExpandedFor(MaterialRef material) {
        if (graph.recipesFor(material).size() <= 1) {
            return;
        }
        String key = material.itemId();
        if (!expandedMaterials.remove(key)) {
            expandedMaterials.add(key);
        }
        rebuildTree(false);   // 展开/收起备选配方时保留当前视角，不强制回中
    }

    /** 在树布局中查找鼠标命中的节点（用于点击展开备选配方）。 */
    private PlanTree.TreeNode findNodeAt(double mouseX, double mouseY) {
        if (layout == null) {
            return null;
        }
        for (PlanTree.TreeNode n : layout.centers.keySet()) {
            int cx = (int) Math.round(layout.centers.get(n));
            int y = layout.ys.get(n);
            if (hitTest(cx, y, mouseX, mouseY)) {
                return n;
            }
        }
        return null;
    }

    /** 中键/右键单击：缩放复位到 1.0，并把树根居中到左侧视图区。 */
    private void resetViewToRoot() {
        ensureView();
        if (layout == null) {
            return;
        }
        zoom = 1.0;
        panX = (treeViewWidth() - layout.treeWidth) / 2.0;
        panY = 40.0;
    }

    /** 回车：提交输入框中的自定义数量。A：把悬停的缺失材料加入 EMI/JEI 收藏。 */
    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if ((keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER)
                && quantityInput != null && quantityInput.isFocused()) {
            commitQuantityInput();
            return true;
        }
        if (keyCode == GLFW.GLFW_KEY_A && (quantityInput == null || !quantityInput.isFocused())) {
            MaterialRef target = hoveredMaterial();
            Log.info("预览界面 A 键：hoveredNode=" + (hoveredNode == null ? "null" : hoveredNode.material())
                    + " hoveredTotal=" + (hoveredTotal == null ? "null" : hoveredTotal.getKey()));
            if (target != null) {
                boolean ok = FavoriteAdder.addFavorite(target.itemId());
                CraftExecutor.chat(ok
                        ? "已将 " + target.itemId() + " 加入收藏（EMI/JEI）"
                        : "无法加入收藏：" + target.itemId());
                return true;
            } else {
                CraftExecutor.chat("请先悬停一个材料再按 A 加入收藏。");
                return true;
            }
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    /** 当前悬停的是树节点还是总耗材条目；都没有返回 null。 */
    private MaterialRef hoveredMaterial() {
        if (hoveredNode != null) {
            return hoveredNode.material();
        }
        if (hoveredTotal != null) {
            return hoveredTotal.getKey();
        }
        return null;
    }

    /** 读取输入框数字并刷新计划/缺失材料；空或非法不改变当前数量。 */
    private void commitQuantityInput() {
        if (quantityInput == null) {
            return;
        }
        String text = quantityInput.getValue().trim();
        quantityInput.setFocused(false);
        if (text.isEmpty()) {
            return;
        }
        try {
            int q = Integer.parseInt(text);
            if (q > 0) {
                // 数字没变就不重建/不居中，避免空白点击或拖拽起点拉视角。
                if (q != quantity) {
                    setQuantity(q);
                }
            } else {
                CraftExecutor.chat("数量必须是正整数。");
            }
        } catch (NumberFormatException e) {
            CraftExecutor.chat("请输入正整数数量。");
        }
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
        ensureView();
        if (layout == null) {
            return;
        }
        Map<PlanTree.TreeNode, Double> centers = layout.centers;
        Map<PlanTree.TreeNode, Integer> ys = layout.ys;

        // 视图变换：先平移再缩放（布局坐标 → 屏幕坐标）
        var pose = g.pose();
        pose.pushPose();
        pose.translate((float) panX, (float) panY, 0);
        pose.scale((float) zoom, (float) zoom, 1.0f);

        // 画连接线（在节点之前，避免覆盖图标）；连接线随缩放走
        for (PlanTree.TreeNode n : centers.keySet()) {
            drawConnections(g, n, centers, ys, maxY);
        }

        boolean fixedNodes = Config.fixedNodeSize();
        for (PlanTree.TreeNode n : centers.keySet()) {
            int cx = (int) Math.round(centers.get(n));
            int y = ys.get(n);
            int sx = (int) Math.round(cx * zoom + panX);
            int sy = (int) Math.round(y * zoom + panY);
            // 不再按 maxY 过滤：所有节点都画，超出屏幕由 GuiGraphics 自行裁剪，
            // 避免人为跳过节点导致“虚空引用/树枝间断”。
            if (!fixedNodes) {
                // 默认：节点跟随缩放一起变换（原版手感）
                drawNode(g, n, cx, y);
            } else {
                // 可选：节点图标/文字固定屏幕大小，不随缩放变化
                drawNode(g, n, sx, sy);
            }
            if (hoveredNode == null && hitTest(cx, y, mouseX, mouseY)) {
                hoveredNode = n;
            }
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

    /** 树视图可用宽度（左侧区域，右侧留给总耗材面板）。 */
    private int treeViewWidth() {
        return Math.max(80, totalsX() - TOTALS_MARGIN);
    }

    /** 总耗材面板右边界起点。 */
    private int totalsX() {
        return width - TOTALS_REGION_WIDTH - TOTALS_MARGIN;
    }

    private int totalsY() {
        return CONTENT_TOP;
    }

    private int totalsHeight() {
        return Math.max(40, height - BOTTOM_BUTTON_RESERVE - totalsY() - TOTALS_MARGIN);
    }

    private boolean inTotalsRegion(double mouseX, double mouseY) {
        return mouseX >= totalsX() && mouseX <= totalsX() + TOTALS_REGION_WIDTH
                && mouseY >= totalsY() && mouseY <= totalsY() + totalsHeight();
    }

    /** 总耗材列表可滚动高度上限。 */
    private double totalsScrollMax() {
        int headerH = 20;
        double total = 0.0;
        for (Map.Entry<MaterialRef, Long> e : totals.entrySet()) {
            total += totalsRowHeight(e);
        }
        return Math.max(0.0, total - (totalsHeight() - headerH));
    }

    /** 有“或”等价物的条目占两行，普通条目占一行。 */
    private int totalsRowHeight(Map.Entry<MaterialRef, Long> e) {
        boolean hasEq = !ManualProcessing.equivalences(e.getKey().itemId()).isEmpty();
        return ICON_SIZE + TOTALS_ITEM_GAP + (hasEq ? 8 : 0);
    }

    /** 首次渲染时把树居中于左侧视图区；大树/超宽树自动缩放适配，小树保持默认。 */
    private void ensureView() {
        if (viewInitialized || layout == null) {
            return;
        }
        int viewW = treeViewWidth();
        int maxY = height - BOTTOM_BUTTON_RESERVE;
        int availH = Math.max(40, maxY - CONTENT_TOP);
        boolean needsFit = Config.autoFitTree()
                || layout.treeWidth > viewW
                || layout.treeBottom > maxY;
        if (needsFit) {
            double fitZoom = Math.min(
                    viewW / Math.max(1.0, layout.treeWidth),
                    availH / Math.max(1.0, layout.treeBottom)
            );
            zoom = clamp(fitZoom, MIN_ZOOM, 1.0);
            panX = (viewW - layout.treeWidth * zoom) / 2.0;
            panY = CONTENT_TOP + (availH - layout.treeBottom * zoom) / 2.0;
        } else {
            zoom = 1.0;
            panX = (viewW - layout.treeWidth) / 2.0;
            panY = 40.0;
        }
        viewInitialized = true;
    }

    /** 重建树布局缓存（构造 / 数量切换后调用，默认重置视角居中）。 */
    private void rebuildLayout() {
        rebuildLayout(true);
    }

    /** 重建树布局缓存；resetView=false 时保留当前 pan/zoom（用于展开/收起备选配方）。 */
    private void rebuildLayout(boolean resetView) {
        layout = root == null ? null : new TreeLayout(root);
        if (resetView) {
            viewInitialized = false;
        }
    }

    /** 按当前数量/展开集合重建整棵预览树（保留配方图与库存）。 */
    private void rebuildTree() {
        rebuildTree(true);
    }

    /** recenter=true 时重建后重新居中到树根；false 时保留当前视角。 */
    private void rebuildTree(boolean recenter) {
        root = PlanTree.build(graph, target, quantity, stock, chosenRecipes, expandedMaterials);
        totals = root == null ? Map.of() : PlanTree.totalLeafDemand(root);
        totalsScrollY = 0;
        rebuildLayout(recenter);
    }

    /** BFS 分层：levels[0]=根，levels[1]=根的子层，...；共享节点（DAG 催化剂）按对象身份只入队一次。 */
    private static List<List<PlanTree.TreeNode>> collectLevels(PlanTree.TreeNode root) {
        List<List<PlanTree.TreeNode>> levels = new ArrayList<>();
        Deque<PlanTree.TreeNode> queue = new ArrayDeque<>();
        Set<PlanTree.TreeNode> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        queue.add(root);
        seen.add(root);
        while (!queue.isEmpty()) {
            int size = queue.size();
            List<PlanTree.TreeNode> level = new ArrayList<>(size);
            for (int i = 0; i < size; i++) {
                PlanTree.TreeNode n = queue.poll();
                level.add(n);
                if (n.children() != null) {
                    for (PlanTree.TreeNode child : n.children()) {
                        if (seen.add(child)) {
                            queue.add(child);
                        }
                    }
                }
            }
            levels.add(level);
        }
        return levels;
    }

    /** Reingold-Tilford 简化：后序遍历算每个节点中心 x，子树宽度 = max(节点宽, 子树宽之和 + gap)。 */
    private static double layoutPositions(PlanTree.TreeNode node, double leftBoundary,
                                          Map<PlanTree.TreeNode, Double> centers,
                                          Set<PlanTree.TreeNode> placed) {
        // 共享节点（如催化剂 DAG）只布局一次：后续引用直接沿用已有中心，避免覆盖导致 dangling edge。
        if (!placed.add(node)) {
            return ICON_SIZE;
        }
        if (node.children() == null || node.children().isEmpty()) {
            double center = leftBoundary + ICON_SIZE / 2.0;
            centers.put(node, center);
            return ICON_SIZE;
        }
        double cur = leftBoundary;
        double firstCenter = -1;
        double lastCenter = -1;
        for (PlanTree.TreeNode child : node.children()) {
            double childWidth = layoutPositions(child, cur, centers, placed);
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

    /** 树布局结果缓存：RSI 风格 Box 列表 + centers/ys 兼容现有渲染。 */
    private static final class TreeLayout {
        /** 一个已布局节点：逻辑坐标，屏幕变换在渲染时应用。 */
        record Box(PlanTree.TreeNode node, int x, int y, int w, int h, int itemCenterX) {
            int centerX() { return x + w / 2; }
            int bottom() { return y + h; }
        }

        final List<Box> boxes = new ArrayList<>();
        final Map<PlanTree.TreeNode, Double> centers = new HashMap<>();
        final Map<PlanTree.TreeNode, Integer> ys = new HashMap<>();
        final double treeWidth;
        final int treeBottom;

        TreeLayout(PlanTree.TreeNode root) {
            IdentityHashMap<PlanTree.TreeNode, Integer> measureCache = new IdentityHashMap<>();
            int rootWidth = measure(root, measureCache);
            layout(root, 0, CONTENT_TOP, rootWidth, measureCache);
            treeWidth = rootWidth;
            int maxBottom = 0;
            for (Box box : boxes) {
                centers.put(box.node(), (double) box.itemCenterX());
                ys.put(box.node(), box.y());
                maxBottom = Math.max(maxBottom, box.bottom());
            }
            treeBottom = maxBottom;
            Log.info("TREE LAYOUT PROBE: boxes=" + boxes.size()
                    + " centers=" + centers.size()
                    + " ys=" + ys.size()
                    + " width=" + treeWidth
                    + " bottom=" + treeBottom);
        }

        private static int nodeWidth() {
            return ICON_SIZE;
        }

        private int measure(PlanTree.TreeNode node,
                            IdentityHashMap<PlanTree.TreeNode, Integer> cache) {
            Integer cached = cache.get(node);
            if (cached != null) return cached;
            int result;
            if (node.children() == null || node.children().isEmpty()) {
                result = nodeWidth();
            } else {
                result = Math.max(nodeWidth(), childrenWidth(node.children(), cache));
            }
            cache.put(node, result);
            return result;
        }

        private int childrenWidth(List<PlanTree.TreeNode> children,
                                  IdentityHashMap<PlanTree.TreeNode, Integer> cache) {
            int width = 0;
            for (int i = 0; i < children.size(); i++) {
                if (i > 0) width += H_GAP;
                width += measure(children.get(i), cache);
            }
            return width;
        }

        private void layout(PlanTree.TreeNode node, int left, int y, int subtreeWidth,
                            IdentityHashMap<PlanTree.TreeNode, Integer> cache) {
            int nodeW = nodeWidth();
            int x = left + (subtreeWidth - nodeW) / 2;
            boxes.add(new Box(node, x, y, nodeW, ICON_SIZE, x + nodeW / 2));
            if (node.children() == null || node.children().isEmpty()) return;

            int childLeft = left + (subtreeWidth - childrenWidth(node.children(), cache)) / 2;
            int childY = y + V_GAP;
            for (PlanTree.TreeNode child : node.children()) {
                int childWidth = measure(child, cache);
                layout(child, childLeft, childY, childWidth, cache);
                childLeft += childWidth + H_GAP;
            }
        }

        Box boxFor(PlanTree.TreeNode node) {
            for (Box box : boxes) {
                if (box.node() == node) return box;
            }
            return null;
        }
    }

    /** 画一个节点的 T 型连接线：父底部 → 子顶部的三段（竖线 + 横线 + 竖线）。 */
    private void drawConnections(GuiGraphics g, PlanTree.TreeNode node,
                                  Map<PlanTree.TreeNode, Double> centers,
                                  Map<PlanTree.TreeNode, Integer> ys, int maxY) {
        if (node.children() == null || node.children().isEmpty()) {
            return;
        }
        Double parentCxObj = centers.get(node);
        Integer parentBottomObj = ys.get(node);
        if (parentCxObj == null || parentBottomObj == null) {
            return;
        }
        int parentCx = (int) Math.round(parentCxObj);
        int parentBottom = parentBottomObj + ICON_SIZE;

        // 连接所有“确实有布局位置”的子节点；不再按屏幕可见性过滤，交给屏幕裁剪。
        List<PlanTree.TreeNode> placedChildren = new ArrayList<>();
        for (PlanTree.TreeNode child : node.children()) {
            if (!centers.containsKey(child) || !ys.containsKey(child)) {
                // 缺失布局位置是真正的结构异常，每次都记录
                Log.info("DANGLING PROBE: parent=" + node.material()
                        + " child=" + child.material()
                        + " missing centers=" + centers.containsKey(child)
                        + " ys=" + ys.containsKey(child)
                        + " parentChildren=" + node.children().size());
                continue;
            }
            placedChildren.add(child);
        }
        if (placedChildren.isEmpty()) {
            return;
        }

        PlanTree.TreeNode firstChild = placedChildren.get(0);
        int childTop = ys.get(firstChild);
        int midY = (parentBottom + childTop) / 2;
        int half = LINE_WIDTH / 2;

        // 父竖线（LINE_WIDTH 居中在 parentCx）
        g.fill(parentCx - half, parentBottom, parentCx + half + 1, midY + 1, LINE_COLOR);
        // 子顶部竖线（每个可见子节点一条）
        for (PlanTree.TreeNode child : placedChildren) {
            int cx = (int) Math.round(centers.get(child));
            g.fill(cx - half, midY, cx + half + 1, ys.get(child), LINE_COLOR);
        }
        // 横线（连接第一个可见子到最后一个可见子，LINE_WIDTH 厚）
        int firstCx = (int) Math.round(centers.get(firstChild));
        int lastCx = (int) Math.round(centers.get(placedChildren.get(placedChildren.size() - 1)));
        g.fill(firstCx - half, midY - half, lastCx + half + 2, midY + half + 1, LINE_COLOR);
    }

    /** 画一个节点：物品图标 + 数量标注；多配方节点额外画 +/- 提示可点击展开。 */
    private void drawNode(GuiGraphics g, PlanTree.TreeNode node, int centerX, int y) {
        int itemX = centerX - ICON_SIZE / 2;
        String methodId = processingMethodId(node);
        boolean manual = methodId != null && !ManualProcessing.isAutoCraftable(methodId);
        if (manual) {
            // 红色圆角边框：提示此物品需要手动加工
            int c = 0xFFFF5555;
            g.fill(itemX - 2, y - 2, itemX + ICON_SIZE + 2, y - 1, c);
            g.fill(itemX - 2, y + ICON_SIZE + 1, itemX + ICON_SIZE + 2, y + ICON_SIZE + 2, c);
            g.fill(itemX - 2, y - 2, itemX - 1, y + ICON_SIZE + 2, c);
            g.fill(itemX + ICON_SIZE + 1, y - 2, itemX + ICON_SIZE + 2, y + ICON_SIZE + 2, c);
            String iconItemId = ProcessingIconProvider.getIconItemId(methodId, node.material().itemId(), node.recipeId());
            if (iconItemId != null) {
                drawItemIcon(g, iconItemId, itemX - 12, y - 2);
            }
        }
        drawItemIcon(g, node.material().itemId(), itemX, y);
        g.drawString(font, "×" + node.amount(), itemX + ICON_SIZE - 2, y + ICON_SIZE - 8,
                stateTextColor(node.state()));
        if (graph.recipesFor(node.material()).size() > 1) {
            String mark = expandedMaterials.contains(node.material().itemId()) ? "-" : "+";
            g.drawString(font, mark, itemX + ICON_SIZE - 6, y, 0xFFFFAA);
        }
    }

    /** 获取节点加工方式：优先配方自带方式；无配方的叶子查手动等价表。 */
    private String processingMethodId(PlanTree.TreeNode node) {
        if (node.recipeId() != null) {
            RecipeNode rn = graph.recipeById(node.recipeId());
            if (rn != null) {
                return rn.method();
            }
        }
        ManualProcessing.ManualMethod mm = ManualProcessing.methodForItem(node.material().itemId());
        return mm != null ? mm.id() : null;
    }

    /** 固定右侧总耗材面板：标题固定，列表可滚轮滚动，缺失红/满足绿。 */
    private void renderTotalsPanel(GuiGraphics g, int mouseX, int mouseY) {
        if (totals.isEmpty()) {
            return;
        }
        int x = totalsX();
        int y = totalsY();
        int panelH = totalsHeight();
        g.fill(x - 4, y - 4, x + TOTALS_REGION_WIDTH + 4, y + panelH + 4, 0xAA000000);
        g.drawString(font, "总耗材(聚合)", x, y, TITLE_COLOR);
        int headerH = 20;
        double itemYAcc = y + headerH - totalsScrollY;
        for (Map.Entry<MaterialRef, Long> e : totals.entrySet()) {
            int rowH = totalsRowHeight(e);
            int iy = (int) Math.round(itemYAcc);
            itemYAcc += rowH;
            if (iy + ICON_SIZE < y || iy > y + panelH) {
                continue;
            }
            int ix = x + 4;
            drawItemIcon(g, e.getKey().itemId(), ix, iy);
            long have = stock.getOrDefault(e.getKey(), 0);
            int color = have >= e.getValue() ? 0x55FF55 : 0xFF5555;
            g.drawString(font, "×" + e.getValue(), ix + ICON_SIZE - 2, iy + ICON_SIZE - 8, color);
            List<ManualProcessing.DropEquivalent> eqs = ManualProcessing.equivalences(e.getKey().itemId());
            if (!eqs.isEmpty()) {
                ManualProcessing.DropEquivalent eq = eqs.get(0);
                long altAmount = e.getValue() * eq.perUnit();
                int altY = iy + ICON_SIZE + 1;
                g.drawString(font, "或", ix + 2, altY + 4, 0xAAAAAA);
                drawItemIcon(g, eq.alternativeItemId(), ix + 16, altY);
                g.drawString(font, "×" + altAmount, ix + 34, altY + 8, 0xAAAAAA);
            }
            if (hoveredTotal == null && mouseX >= ix - 2 && mouseX <= ix + ICON_SIZE + 2
                    && mouseY >= iy - 2 && mouseY <= iy + ICON_SIZE + 2) {
                hoveredTotal = e;
            }
        }
    }

    private void renderTotalTooltip(GuiGraphics g, Map.Entry<MaterialRef, Long> entry,
                                     int mouseX, int mouseY) {
        List<Component> lines = new ArrayList<>();
        String displayName = displayNameOf(entry.getKey());
        lines.add(Component.literal(displayName + " ×" + entry.getValue() + "（总耗材）")
                .withStyle(ChatFormatting.WHITE));
        if (!entry.getKey().nbt().isEmpty()) {
            lines.add(Component.literal("要求: " + NbtDisplay.localize(entry.getKey().nbt()))
                    .withStyle(ChatFormatting.GRAY));
        }
        for (ManualProcessing.DropEquivalent eq : ManualProcessing.equivalences(entry.getKey().itemId())) {
            ManualProcessing.ManualMethod mm = ManualProcessing.method(eq.methodId());
            String methodName = mm != null ? mm.displayName() : eq.methodId();
            long altAmount = entry.getValue() * eq.perUnit();
            lines.add(Component.literal("或 " + altAmount + "× " + displayNameOf(MaterialRef.of(eq.alternativeItemId()))
                    + "（" + methodName + "，需手动获取，AutoCraft 不自动执行）")
                    .withStyle(ChatFormatting.GRAY));
        }
        lines.add(Component.literal("注册名: " + entry.getKey().itemId())
                .withStyle(ChatFormatting.DARK_GRAY));
        g.renderTooltip(font, lines, Optional.empty(), mouseX, mouseY);
    }

    /** 悬停 tooltip：物品显示名 + 数量 + 状态 + 配方短名。 */
    private void renderNodeTooltip(GuiGraphics g, PlanTree.TreeNode node, int mouseX, int mouseY) {
        List<Component> lines = new ArrayList<>();
        String displayName = displayNameOf(node.material());
        String suffix = node.state() == PlanTree.State.HAS ? "（库存满足）"
                : node.state() == PlanTree.State.PARTIAL ? "（部分满足）"
                : node.state() == PlanTree.State.MISSING ? "（缺失）" : "（需生产）";
        String amountText = node.catalyst()
                ? displayName + " ×" + node.amount() + "（催化剂，持有不消耗）"
                : displayName + " ×" + node.amount() + " " + suffix;
        lines.add(Component.literal(amountText).withStyle(ChatFormatting.WHITE));
        if (!node.requirementText().isEmpty()) {
            lines.add(Component.literal("要求: " + NbtDisplay.localize(node.requirementText()))
                    .withStyle(ChatFormatting.GRAY));
        }
        if (node.recipeId() != null) {
            lines.add(Component.literal("配方: " + shortRecipe(node.recipeId()))
                    .withStyle(ChatFormatting.GRAY));
        }
        String methodId = processingMethodId(node);
        if (methodId != null && !ManualProcessing.isAutoCraftable(methodId)) {
            ManualProcessing.ManualMethod mm = ManualProcessing.method(methodId);
            String methodName = mm != null ? mm.displayName() : methodId;
            lines.add(Component.literal("此物品需要通过 " + methodName + " 手动加工，AutoCraft 不会自动执行")
                    .withStyle(ChatFormatting.RED));
        }
        if (graph.recipesFor(node.material()).size() > 1) {
            boolean expanded = expandedMaterials.contains(node.material().itemId());
            lines.add(Component.literal(expanded ? "左键点击收起其他配方" : "左键点击展开其他配方")
                    .withStyle(ChatFormatting.YELLOW));
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

    /** 带缓存的物品解析：树渲染每帧多次调用，避免每节点每帧查注册表。 */
    private Item resolveItem(String itemId) {
        if (itemCache.containsKey(itemId)) {
            return itemCache.get(itemId);
        }
        Item item = ForgeRegistries.ITEMS.getValue(ResourceLocation.tryParse(itemId));
        itemCache.put(itemId, item);
        return item;
    }

    private void drawItemIcon(GuiGraphics g, String itemId, int x, int y) {
        try {
            Item item = resolveItem(itemId);
            if (item == null || item == net.minecraft.world.item.Items.AIR) {
                if (unknownItemLogged < 20) {
                    Log.info("UNKNOWN ITEM PROBE: " + itemId);
                    unknownItemLogged++;
                }
                return;
            }
            g.renderItem(new ItemStack(item), x, y);
        } catch (Exception ignored) {
            // 单个物品渲染失败不应阻塞整棵树
        }
    }

    /** 物品显示名（本地化）。失败时回退注册名。 */
    private String displayNameOf(MaterialRef material) {
        try {
            Item item = resolveItem(material.itemId());
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
        quantity = Math.max(1, q);
        if (quantityInput != null) {
            quantityInput.setValue(String.valueOf(quantity));
        }
        Log.debug("预览数量切换为 " + quantity);
        result = OrderTrigger.plan(target, quantity);
        if (result == null) {
            error = "规划失败";
            root = null;
            totals = Map.of();
            layout = null;
            viewInitialized = false;
            totalsScrollY = 0;
            return;
        }
        // 材料不足也保留预览：不设置 error，树/总耗材以红色展示缺失。
        error = null;
        chosenRecipes.clear();
        if (result.feasible()) {
            for (PureSearchPlanner.PlannedStep step : result.steps()) {
                chosenRecipes.add(step.recipeId());
            }
        }
        rebuildTree();   // 用当前展开集合重建树并重新居中
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