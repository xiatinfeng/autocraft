# autocraft v2 整体设计（完整框架）

> 2026-08-13 · 项目 autocraft（原 emi-autocraft，已改名）· Forge 1.20.1 · 纯客户端
> 前置阅读：`auto-chain-crafting-design.md`（v1 历史 + §8/§9 复盘）。本文件为**现行完整设计**，v1 文档仅作历史参考。
> 参考对象：rs_integration（Elten-huanghuang）——**专有许可（All rights reserved）**，仅借鉴算法思想（回溯搜索/图结构/护栏），零代码复制（§8.5 合规声明）；其源码快照留 `_rsref/` 学习用。

---

## 0. 定位与边界（用户拍板）

- **目标**：玩家背包有全部原料时，对目标物品触发"自动合成整条配方树"（嵌套合成）——省略中间步骤（分解木板、合成木棍），直接从原木做出木稿。
- **约束**：
  - 依赖：**仅 JEI/EMI**（客户端，可选检测，二选一或并存）；不依赖 RS / 任何服务端 mod。
  - 形态：**纯客户端**（bi-optional），公网服免装服务端（同 Crafting Tweaks 定位）。
  - 平台：Forge 1.20.1。
- **范围 v1**：仅 `RecipeType.CRAFTING`（合成桌 2×2/3×3）。熔炉/机器/多方块 → v2.1+（通过 JEI/EMI 配方数据或逐 mod 适配）。
- **非目标**：RS 网络集成、服务端机器操作、远程 GUI、多方块执行、可缩放依赖图（留 v2.1）。

---

## 1. 问题定义（先归类，再设计）

**输入**：目标物品 + 需求数量、玩家背包快照（物品→数量）、配方图。
**输出**：有序执行清单 `List<PlannedStep(recipeId, batches)>`；或"缺失材料"报告（不可行时）。

**问题类型 = 带库存约束的搜索问题（约束求解），不是图遍历。**
（§9 复盘结论：v1 把"配方树"当图遍历做传播，踩了多配方/库存交互/环/爆炸四个坑。本版按搜索范式设计。）

| 特征 | 出现场景 | 对设计的影响 |
|---|---|---|
| 多配方 | 箱子有多种木板组合 | 搜索分支 + 失败回溯，不能启发式一次定死 |
| 库存交互 | tag 变体凑数、中间产物被别处消耗 | stock 必须进搜索状态 |
| 环 | 羊毛染色互转、木头家族等价互转 | resolving + 自耗净增益剪枝 |
| 组合爆炸 | 深树 + 多配方 | 全套护栏先行 |

---

## 2. 整体架构（数据流）

```
[触发层]  JEI/EMI 物品上按键 / JEI 界面按钮 / 合成桌内按键
   │        目标物品 + 需求数量（默认 1）
   ▼
[索引层]  RecipeIndex —— 客户端 getRecipeManager() 全量配方 → crafting 过滤 → 版本化缓存
   │
   ▼
[图结构]  ImmutableRecipeGraph —— 纯值 record（MaterialRef/IngredientRef/RecipeNode + recipesByOutput 索引）
   │
   ▼
[规划层]  PureSearchPlanner —— 有界回溯搜索（stock + 任务队列 + 环保护 + 护栏 + 失败记忆）
   │        异步线程（主线程超预算降级）
   │
   ▼
[执行层]  CraftExecutor —— 按 PlannedStep 清单驱动 CraftingMenu（fillGrid + shift-click + 可配置延迟）
   │
   ▼
[反馈层]  GUI：规划预览（步骤列表 + 缺失高亮）+ 执行进度 + 取消
[配置层]  Config（config/autocraft.toml）：延迟/深度/预算/黑名单/跨层开关
[测试层]  离线单测（纯 Java，不依赖 MC 运行时）
```

**分层原则**：规划层与执行层解耦（规划只产出清单，不碰合成）；索引/图/规划 = 纯逻辑（可测、可缓存、可异步）；只有执行/触发/GUI 碰 MC 运行时。

---

## 3. 模块详细设计

### 3.1 索引层 RecipeIndex

- **数据源**：`Minecraft.getInstance().level.getRecipeManager()`——客户端持有完整配方表副本（供显示，天然可用）。
- **过滤**：`recipeManager.getAllRecipesFor(RecipeType.CRAFTING)`（v1 范围）。
  > ⚠️ 已核实（2026-08-14）：1.20.1 该方法是 `getAllRecipesFor`，直接返回 `List<Recipe<CraftingContainer>>`，
  > **没有 RecipeHolder 包装**（1.21 才引入 RecipeHolder/recipeMap）。勿按 1.21 写法。
- **产出**：`Map<Item, List<Recipe<?>>>`（物品 → 所有产出它的 crafting 配方）。
- **Ingredient 展开**：`ing.getItems()` → `ItemStack[]`（tag 全展开进 alternatives 列表；v1 忽略 NBT 匹配）。
- **版本化缓存**：记录 `recipeManager` 实例引用 + 世界/维度切换时失效重建（参考 rs_integration `sourceRevision` 思想，自研实现）；缓存对象 = ImmutableRecipeGraph（不可变，可跨线程安全共享）。

### 3.2 图结构 ImmutableRecipeGraph（纯值，自研）

```java
record MaterialRef(ResourceLocation itemId, String nbt)              // 物品 + NBT（v1 nbt 恒 ""）
record IngredientRef(List<MaterialRef> alternatives, int count)      // 槽位；tag 多解 = alternatives 全展开
record RecipeNode(ResourceLocation recipeId, MaterialRef output, int outputCount,
                  List<IngredientRef> inputs)                        // 配方节点（1 配方 → 1 节点）
record ImmutableRecipeGraph(
    Map<MaterialRef, List<RecipeNode>> recipesByOutput,              // 物品 → 产出它的所有配方
    Map<ResourceLocation, RecipeNode> recipesById)                   // id → 节点（步骤去重/回查）
```

- 构建：单次遍历 RecipeIndex 的配方 → 转 RecipeNode（输出取 `getResultItem`，输入逐槽展开）。
- **为什么纯值/不可变**：①线程安全（规划可异步）②缓存可复用 ③离线单测不需要 MC 运行时。

### 3.3 规划层 PureSearchPlanner（核心）

**状态**（Search 内部）：
```java
Map<MaterialRef, Integer> stock        // 库存快照（实时扣减/回填）
List<Task> pending                     // 待办任务队列（DemandTask / CompleteRecipeTask）
Set<MaterialRef> resolving             // 正在求解的物品（环保护）
List<PlannedStep> steps                // 已确定的执行步骤
Set<FailureKey> failedStates           // 失败状态记忆（去重防重复探索）
int expandedStates / backtracks / memoHits   // 统计（供调试/报告）
```

**任务类型**：
```java
record DemandTask(IngredientRef ingredient)                         // 需要某物 N 个
record CompleteRecipeTask(RecipeNode node, int outputCount,
                          int consumeCount, int batches)            // 做某配方 batches 次（产出/消耗记账）
record PlannedStep(ResourceLocation recipeId, int batches)          // 最终执行清单项
```

**求解（solveDemand，三条路顺序尝试、失败回溯）**：
```
需要 N 个 X：
  路① 库存直接扣     —— stock[X] >= N → 扣减，solve(剩余任务)
  路② tag 替代品聚合  —— alternatives 中多个有货变体，按"预留未来单件需求"排序凑够 N
  路③ 生产 X         —— 枚举 recipesByOutput[X] 每个配方：
                          selfConsumed = 该配方输入里消耗自身 X 的数量（如染色）
                          netGain = outputCount - selfConsumed
                          netGain <= 0 → 跳过（no-gain 剪枝，防环/防无收益）
                          batches = ceil(需要量 / (有自耗 ? netGain : outputCount))
                          分支 = 每个输入 → 新 DemandTask(count×batches) + CompleteRecipeTask → 递归
                          失败 → 回溯，试下一个配方 / 下一条路
```

**环保护**：`resolving` 集合（正在求解的物品回边跳过）+ 自耗净增益剪枝（§上）。

**护栏（防组合爆炸，先设计再写主体）**：
```java
maxSteps          = 256      // 执行步骤上限（PlannedStep 数）
maxSearchStates   = 65536    // 搜索状态展开上限
maxCallDepth      = 512      // 递归深度上限
deadlineNanos     = 500ms    // 时间预算（超时 → TIME_LIMIT，提示"规划过深"）
maxMemoizedFailures = 8192   // 失败记忆条数上限
```

**结果**：
```java
record Result(Feasibility feasibility,            // FEASIBLE / INFEASIBLE / UNKNOWN(超限)
              List<PlannedStep> steps,            // 有序执行清单（feasible 时非空）
              List<MaterialRef> missing,          // 缺失材料（infeasible 时报告）
              Map<MaterialRef, Integer> remaining,// 执行后剩余库存
              Status status, int expandedStates, int backtracks, int memoHits)
```

**线程**：规划为纯计算 → 放异步线程（CompletableFuture / ForkJoinPool）。主线程同步等待 ≤ 250ms；超时返回"规划中"提示 + 异步完成后消息栏通知（避免卡 UI）。

### 3.4 执行层 CraftExecutor

- **输入**：`List<PlannedStep>` + 玩家背包 + 当前打开的 CraftingMenu。
- **驱动原语**（等同 JEI transfer 语义，自实现）：
  - `fillGrid(recipe, menu, playerInv)`：把材料从背包槽移入矩阵槽（槽 1..9；2×2 配方自动子区匹配，无需单独处理 2×2 背包合成）。
  - `triggerCraft(menu)`：shift-click 结果槽（槽 0）→ 合成一组（默认做 batches 次）。
  - `collectOutput()`：产物取回背包。
- **校验**：每次 fillGrid 后校验 `menu` 确有合法结果再 trigger（防网格不合法/服务端不认）。
- **延迟**：`interCraftDelayTicks`（默认 0 单机；公网服可配 2+ ticks 防反作弊 macro 判定）。
- **批次循环**：每 PlannedStep 执行 batches 次；大批次跨 tick 拆分（每 tick 最多 N 次合成，防服务端限速/卡顿）。
- **失败处理**：执行中材料不足/合成失败 → 停止，保留已合成产物，聊天栏提示失败步骤；不自动回滚（v1 决策，避免复杂度）。
- **取消/进度**：复用 KeyHandler（P 键查看进度、取消）；执行状态存 `CraftState`（已有类改造）。

### 3.5 反馈层 GUI

- **规划预览（v1 简版）**：执行前弹步骤列表界面——每行 = 配方名 + 次数；缺失材料红色高亮（可点击 JEI 书签）；"开始/取消"按钮。
- **执行进度**：合成期间小 HUD/聊天栏显示"第 i/N 步：配方名"。
- **v1 不做**：可缩放依赖图（PlanGraphView 类引擎）——架构预留"规划产出清单 → 渲染层消费"解耦，v2.1 加图视图时不动规划层。

### 3.6 触发层（JEI/EMI 双入口）

- **按键**：物品上按绑定键（KeyHandler 已有骨架）→ 对悬停物品下单（JEI/EMI 都监听物品悬停；EMI 存在时走 EMI 的悬停 API，否则走 JEI）。
- **按钮**：JEI 配方界面加"自动合成"按钮（RecipeScreenButtonHandler 已有骨架）→ 对当前配方产物下单；EMI 存在时同位置加按钮。
- **合成桌内**：对当前合成结果下单（3×3 网格当前配方）。
- **数量**：默认 1；Shift+触发 = 合成到材料用尽或一组（v1 简版只做默认 1 + 可配置）。

### 3.7 配置层 Config（config/autocraft.toml）

```toml
[general]
interCraftDelayTicks = 0        # 防反作弊延迟（公网服建议 2+）
maxCraftsPerTick = 8            # 跨 tick 拆分上限
[planning]
maxSteps = 256                  # 执行步骤上限
maxSearchStates = 65536         # 搜索状态上限
planningTimeoutMs = 500         # 规划时间预算
allowCrossLayer = true          # 允许跨层合成（关闭=仅背包原料直合）
[blacklist]
items = []                      # 禁用的目标物品/原料（mod:id）
```

### 3.8 测试层（离线单测，纯 Java）

- **图构建**：手工构造小配方集 → 验证 recipesByOutput 索引正确。
- **规划正确性**：
  - 原木→木稿多层：1 原木 → 4 木板 → 8 木棍 → 1 木稿（验证 batches/数量传播 ceil）。
  - 多配方：箱子多种木板组合 → 搜索选可行分支。
  - 环：羊毛染色互转 → 不无限递归（resolving + 净增益剪枝）。
  - 缺失：背包缺中间料 → INFEASIBLE + 正确 missing 报告。
  - 护栏：深树/多分支 → 不超预算（SEARCH_LIMIT/TIME_LIMIT 优雅返回）。
- **真机清单（用户）**：ATM9 实测——原木→木稿、箱子（多木板）、染色羊毛环、公网服延迟行为。

---

## 4. 关键设计决策与理由（写明白）

| # | 决策 | 理由 |
|---|---|---|
| D1 | **搜索范式（回溯）而非传播** | §9 复盘：多配方/库存交互/环 → 搜索天然处理；传播只能处理确定性单解 |
| D2 | **纯值不可变图** | 线程安全（异步规划）+ 缓存复用 + 离线可测 |
| D3 | **库存进搜索状态** | tag 替代品凑数、中间产物被其他分支消耗——静态假设必错 |
| D4 | **护栏先行** | 组合爆炸是搜索的必然风险；先定预算再写主体（§9 根因五） |
| D5 | **纯客户端 bi-optional** | 用户拍板；公网服免装服务端（同 CT 定位）；反作弊风险用延迟缓解 |
| D6 | **v1 仅 crafting** | 先验证"搜索范式 + 执行闭环"成立，再扩机器配方 |
| D7 | **执行失败不自动回滚** | 规划已保证可行；执行中异常=环境变化（材料被抢等），保留产物 + 提示即可，复杂度可控 |
| D8 | **规划异步 + 主线程降级** | 纯计算移出渲染线程；超预算不卡 UI |
| D9 | **规划/执行/渲染解耦** | 各层可独立测试/替换；v2.1 加依赖图不动规划层 |

---

## 5. 数据流示例（原木 → 木稿）

```
目标：minecraft:wooden_pickaxe ×1，背包：oak_log ×16

RecipeIndex → ImmutableRecipeGraph（含 3 个配方节点：log→4 plank / 2 plank→4 stick / 2 stick+1 plank→1 pickaxe）

PureSearchPlanner 回溯：
  Demand(wooden_pickaxe ×1)
  → 路③：配方 pickaxe（输入 2 stick + 1 plank）
    → Demand(plank ×1)   → 库存有 log，但 plank 无 → 路③：配方 log→4 plank，batches=ceil(1/4)=1
      → Demand(log ×1)   → 库存 log=16 ✓ 扣 1
      → CompleteRecipe(plank ×4)
    → Demand(stick ×2)   → 路③：配方 2plank→4 stick，batches=ceil(2/4)=1
      → Demand(plank ×2) → 库存剩 3 ✓ 扣 2
      → CompleteRecipe(stick ×4)
    → CompleteRecipe(pickaxe ×1)
  → SUCCESS

steps = [log→plank ×1, plank→stick ×1, pickaxe ×1]   // 有序执行清单
remaining = {log: 15, plank: 2, stick: 3}

CraftExecutor：
  1) fillGrid(log→plank) → shift-click → 4 plank 回背包
  2) fillGrid(plank→stick) → shift-click → 4 stick 回背包
  3) fillGrid(pickaxe) → shift-click → 1 wooden_pickaxe 回背包 ✅
```

---

## 6. 实施顺序（里程碑）

| 里程碑 | 内容 | 验收 | 状态 |
|---|---|---|---|
| **M1** | 数据结构（MaterialRef/IngredientRef/RecipeNode/Graph）+ PureSearchPlanner + **离线单测**（纯 Java，不碰 MC） | 单测全绿：多层/多配方/环/缺失/护栏 | ✅ 完成（`19bece8`）40/0 |
| **M2** | RecipeIndex 接入客户端 RecipeManager（crafting 过滤 + 版本化缓存） | 游戏内 `/autocraft plan <item>` 命令出清单（临时调试命令） | ✅ 完成（`7d74bd0`） |
| **M3** | CraftExecutor（驱动 CraftingMenu + 延迟 + 跨 tick 拆分） | 游戏内实测原木→木稿成功 | ✅ 完成（`7b531ce`），真机回归中 |
| **M4** | 触发层（JEI/EMI 按键 + 按钮 + 合成桌内） | 三种入口都能下单 | ✅ 完成（`73c1c16`） |
| **M5** | 反馈层（步骤列表预览 + 进度 + 取消） | 预览/进度/取消可用 | ✅ 完成（`07e1278`），二轮升级为 EMI 树+数量 |
| **M6** | Config 落地 + 打磨 + 公网服延迟验证 | ATM9 全流程验收 | 🟡 代码完成（`550cda1` + `268dd7f`），**公网服验证待测** |

**依赖关系**：M1 是地基（纯算法，无 MC 依赖，可先行）；M2→M3→M4 串行；M5/M6 收尾。

> 二轮修复 commit：`268dd7f`（执行器可靠性 + EMI 树预览 + 数量 + 局内指令）。
> 详细实施记录见 §8，后续计划见 §9。

---

## 7. 风险登记

| 风险 | 等级 | 缓解 |
|---|---|---|
| 纯客户端驱动合成桌被反作弊判定 | 中 | interCraftDelayTicks + 跨 tick 拆分（M6 公网服实测） |
| EMI/JEI 共存时触发冲突 | 低 | 触发层按存在性分派（EmiGuard 已有） |
| 规划超预算（深树/超多配方） | 低 | 护栏 + 异步降级（D4/D8） |
| 合成桌时序（fillGrid 后结果未同步） | 中 | 每步校验 menu 合法结果再 trigger（3.4） |
| 执行中材料被环境改变（掉线/他人） | 低 | 失败即停 + 保留产物（D7） |
| tag 多解导致选错变体 | 低 | alternatives 全展开 + 聚合消耗（3.3 路②） |
| 背包满取不出产物 | 低 | 执行前检测背包剩余空间，不足提示 |
| rs_integration 许可合规 | — | 零代码复制；思想借鉴已声明（§0/§8.5） |
| ~~合成桌时序（fillGrid 后结果未同步）~~ | 低 | **已修复（二轮）**：扫描式填充 + SYNC 轮询 ≤20 tick + 步骤间 SETTLE 3 tick（§8.2） |

---

## 8. 实施记录（2026-08-14，M1-M6 + 二轮修复）

> 由 Rein 实施。本节记录**实际建成的东西**与设计/验收的差异，替代"蓝图"作为现状权威。

### 8.1 已交付模块与提交

| 提交 | 内容 |
|------|------|
| `19bece8` | M1：`plan/` 包（MaterialRef/IngredientRef/RecipeNode/ImmutableRecipeGraph/PureSearchPlanner/PlanSelfTest）纯 Java，零 MC import |
| `7d74bd0` | M2：`index/RecipeIndex`（RecipeManager→图，版本化缓存 + 背包快照）+ `command/PlanCommand`（/autocraft plan） |
| `7b531ce` | M3：`craft/CraftExecutor`（容器点击包驱动）+ Config 执行项 + PlanCommand→**AutoCraftCommand** |
| `73c1c16` | M4：`trigger/OrderTrigger` 统一下单 + B 键/配方屏按钮接入新管线 |
| `07e1278` | M5：`ui/PlanPreviewScreen`（初版平面列表）+ 步骤进度聊天 + 取消 |
| `550cda1` | M6：Config 规划参数接线 + 黑名单 + 改名/版本 v1.1.0 + USAGE.md v2 |
| `268dd7f` | **二轮修复**：执行器可靠性 + 预览=EMI 配方树 + 数量选择 + 局内指令 |
| `M6b（本次，未提交）` | **预览树自写化**：`plan/PlanTree`（EMI-free 值对象树）+ `OrderTrigger.buildTree`（删 BoM/MaterialTree 依赖）+ `PlanPreviewScreen` 自绘树形（└─/├─ 连接线 + 颜色状态 + 底部总耗材）+ PlanSelfTest T6（53 PASS/0 FAIL） |

### 8.2 与设计的差异 / 修正（重要）

| 设计 § | 设计原文 | 实际实现（差异） |
|--------|----------|------------------|
| §3.1 | `getRecipesFor` → `List<RecipeHolder<?>>` | 1.20.1 实为 `getAllRecipesFor` → `List<Recipe<CraftingContainer>>`，**无 RecipeHolder**；RecipeIndex 已按真实 API 实现 |
| §3.4 | 驱动原语 fillGrid / triggerCraft / collectOutput | 全部走 `handleInventoryMouseClick` 标准点击包；**扫描式填充**：步骤开始时一次性定死来源槽位（本地模型扣减），之后盲发点击，执行中不读客户端状态（消除同步滞后）；**无条件 RETURN_EXCESS**（cursor 空时无害 no-op，杜绝残留→SWAP）；**SYNC 轮询** ≤20 tick；**步骤间 SETTLE 3 tick**。精确 1 个/槽 → 每批后网格自动清空，无清网格逻辑 |
| §3.5 | v1 简版步骤列表 | 升级为 **EMI 配方树预览**（BoM.setGoal→MaterialTree→压平 TreeLine 渲染，进度着色）+ **数量选择**（1/4/16/64 预设，切换重规划+重建树）；无 EMI 回退平面列表（TreeLine 无 EMI 依赖，类加载不崩） |
| §3.5（M6b） | EMI 配方树预览 | **自写 PlanTree 替代**（决策演进见 §8.5）：BoMScreen 依赖 46 个 `dev.emi.emi.*` 内部类 + Fabric 映射名，抄源码 = 必须装 EMI，与"只装 JEI 也能看树"冲突 → 自写 `plan/PlanTree`（TreeNode 递归展开 + State 着色 + totalLeafDemand 总耗材，chosenRecipes 优先用规划器实际选中的配方）。**预览路径零 EMI 运行时依赖**（执行层 TreeDriver 仍用 EMI BoM，属 v1 设计） |
| §3.6 | 按键/按钮/合成桌内 | B 键语义 = **执行中取消 > 合成桌结果槽 > EMI 悬停 > 提示**；v1 的 EMI BoM TreeDriver 被取代成**死代码**（保留未删，仅 CraftExecutor.stop 引用） |
| §3.7 | `[planning]` maxSteps/maxSearchStates/planningTimeoutMs/allowCrossLayer + `[blacklist]` | 全部落地；新增 `showPlanPreview`、`interCraftDelayTicks`、`maxCraftsPerTick`；**局内指令** `/autocraft delay <ticks>`、`/autocraft preview <on|off>`、`/autocraft cross <on|off>`（会话级运行时覆盖） |
| — | — | 命令集：`/autocraft plan|craft|delay|preview|stop`；plan 成功后自动弹预览 |
| — | — | 版本 v1.1.0；rootProject 更名 autocraft；mods.toml displayName "AutoCraft" |

### 8.3 首轮终测（adimn，2026-08-14）与修复

- **结果**：T9（EMI 配方屏 A 按钮）石稿成功；**木稿/箱子报"合成结果无效（服务端不认网格）"**；预览只显示注册名；T3/T4 测试设计不合理（已删）；plan 应开预览；T10/T11 要局内指令；T15（下界合金稿）属锻造台、超出合成范围（换深层链书架）。
- **根因**（复盘）：执行中读客户端容器状态存在 SP 同步滞后，可能选错源槽 / cursor 残留引发 SWAP → 网格错位 → 结果无效。多中间步骤场景（木稿/箱子）暴露，单步场景（石稿）侥幸通过。
- **修复**：见 §8.2（扫描式填充 + 无条件 RETURN + SYNC 轮询 + SETTLE）。开 `logDebug=true` 可在 `logs/autocraft.log` 看到每个 `click`/SCAN/SYNC 明细。

### 8.4 验证状态

- 离线编译（`gradlew compileJava --offline`）✅；M1 自测 40 PASS / 0 FAIL ✅；jar `build/libs/autocraft-1.1.0.jar` ✅
- **真机**：仅部分验证（T9 石稿成功、T16/T17 停止/缺料成功）；木稿/箱子回归 + 预览树 + 数量 + 局内指令待第二轮终测（TEST-PLAN.md）

### 8.5 预览树自写化（M6b，2026-08-14，决策演进）

**背景**：用户想把 EMI 配方树 UI 内置进预览界面。初始直觉"抄 EMI 源码"——但源码实证否定：
- `BoMScreen` 46 个非 API import 全是 `dev.emi.emi.*` 内部类（EmiPort/EmiRenderHelper/EmiUtil/EmiConfig/EmiInput/EmiFavorites/EmiHistory/EmiDrawContext/StackBatcher/RecipeTooltipComponent/EmiPersistentData…），只在 EMI jar 存在 → 抄 = 必须装 EMI，与"只装 JEI 也能用"目标直接冲突。
- 且 EMI 源码是 Fabric 映射名（MinecraftClient/DrawContext），需翻译 Forge srg 名，工程量大。
- **结论**：展示层自写（方案 B），数据层仍用 RecipeManager（JEI/EMI 任一提供配方）。

**落地**（`plan/PlanTree.java`，EMI-free 纯值对象）：
- `TreeNode(material, amount, recipeId, children, state)`；`build(graph, target, count, stock, chosenRecipes)` 递归展开。
- `State`：HAS(库存满足)/PARTIAL(无配方且部分满足)/MISSING(无配方且不足)/CRAFT(需生产)。有配方节点一律 CRAFT；PARTIAL 仅限无配方原料。
- tag 多解 → 取"有库存替代物"（贴近玩家实际）；`chosenRecipes` = 规划器 Result.steps 的 recipeId 集合，优先展开规划器实际选中的配方。
- 环保护：ancestry 路径去重 + `MAX_DEPTH_LIMIT=64`。
- `totalLeafDemand()` 总耗材：叶子需求量汇总（按批次向上取整语义，1 log→4 plank 需求 2 plank 仅 1 批）。
- 数量传播：`need → batches=ceil((need-have)/outputCount) → 输入×batches`，saturatingMul clamp 2^31。

**测试**：PlanSelfTest 新增 T6（5 场景），**53 PASS / 0 FAIL**。离线编译 + jar 构建通过。

### 8.6 预览树渲染迭代（M6b→M6d，2026-08-14，三轮用户实测驱动）

| 版本 | 用户反馈 | 改动 |
|------|----------|------|
| M6b | 深缩进树"中间很空"，要 EMI 那种层叠树 | **重写 render**：BFS 分层 + 简化 Reingold-Tilford（后序遍历算中心 x，父居中于子范围）+ T 型连接线（父竖线→横线→子竖线）+ 底部独立"总耗材"行 |
| M6c | "不能像 EMI 缩放，树一长看不了；悬停不能看物品名" | **滚轮缩放**（0.3x-3.0x，围绕鼠标位置：`layout=(screen-pan)/zoom → pan'=mouse-layout×zoom'`）+ **左键拖拽平移**（`isOverButton` 排除控件区）+ **悬停 tooltip**（显示名×数量+状态+配方短名+注册名，`renderTooltip` 在 `pose.popPose` 后） |
| M6d | "渲染出错：书架 4 蜜脾+4 紫水晶块但显示 ×1；线没连上；总耗材无悬停" | **①槽位合并**（`RecipeIndex.toNode` 原来每槽 count=1，4 同物品槽=4 个 count=1 → 改 `Map<List<MaterialRef>,Integer> merged` 按 alternatives 合并）；**②线宽 1px→2px**（`LINE_WIDTH`，fill 区域 `±half` 居中）；**③总耗材 hover**（`hoveredTotal` + `hitRect` 矩形命中 + `renderTotalTooltip`） |

**技术要点（M6c/M6d，已实证）**：
- 缩放用 MC 原生 `pose().pushPose()/translate(pan)/scale(zoom)/popPose()` 矩阵变换，图标/线/字整体缩放——**不抄 EMI**（其缩放 = joml 矩阵栈 + StackBatcher 批量渲染 + 手势系统，搬 = 半个渲染引擎 + 46 内部类）。
- `isMouseOver` 只能用于 `AbstractWidget`（遍历 `children()` 而非 `renderables`，踩坑实证）。
- `hitRect(lx, ly, w, h, mx, my)`：布局坐标→屏幕坐标的矩形命中检测（+2px 容差），后续任何 hover 复用。
- **槽位合并语义**：合成桌 3x3 网格多份同物品 = 1 个 `IngredientRef(count=N)`；tag 槽位（alternatives 多元素）天然不合并。

**验证**：compileJava + jar（98812B）+ PlanSelfTest 53 PASS/0 FAIL（树构建/规划器逻辑回归）。

**真机验证状态**：M6c 缩放/平移/悬停手感已确认 OK；M6d 三项修复（×4 显示/线宽/总耗材 tooltip）待用户实测确认。

**遗留**：①执行层 `craft/TreeDriver.java` 仍用 EMI BoM（执行路径，非展示，v1 设计如此，暂不动）②git 归属未决（autocraft 无独立 .git，改动落在 Claw 大仓库，用户暂缓）

### 8.7 齿轮按钮双平台化（2026-08-15，用户反馈驱动）

**背景**：用户截图指出 EMI 配方屏上 mod 按钮（18×18 MC 原版 Button "A" 字）与原版 12×12 图标按钮风格差异过大；要求改用齿轮图标 + "注册进"按钮体系，并兼容 JEI。

**改动**：
| 文件 | 内容 |
|------|------|
| `compat/GearButton.java` | 公共 12×12 齿轮按钮（extends Button，渲染齿轮纹理，EMI/JEI 共用） |
| `ui/RecipeScreenButtonHandler.java` | EMI 配方屏按钮改造：18×18 "A" 按钮 → 12×12 齿轮（ScreenEvent + 反射取当前配方，复用旧定位逻辑）；**类加载安全修正**：原直接 `instanceof RecipeScreen` 在纯 JEI 包会 NoClassDefFoundError，改为 EmiGuard 短路 + 类名字符串判断后 cast |
| `compat/JeiGuard.java` | JEI 存在性守卫（同 EmiGuard 模式，`Class.forName("mezz.jei.gui.recipes.RecipesGui")`） |
| `compat/JeiButtonAdder.java` | JEI 配方屏按钮：`RecipesGui.getArea()`（public）定位配方区右外侧，`event.addListener` 挂齿轮按钮；点击用 `RecipesGui.getIngredientUnderMouse(VanillaTypes.ITEM_STACK)`（public）拿悬停物品下单 |
| `compat/JeiRecipeScreenHandler.java` | `ScreenEvent.Init.Post` 检测 JEI 屏（**类名反射**，不直接 import RecipesGui，保证 JEI 缺失时 handler 类安全加载） |
| `assets/autocraft/textures/gui/buttons.png` | 自绘 16×16 齿轮（8 齿 + 4 辐条 + 中心孔），两态：normal 灰白 / hover 纯白，渲染缩放 12×12 |
| `build.gradle` | `implementation files('libs/jei-1.20.1-forge-15.20.0.129.jar')`（锁定用户环境版本） |

**关键决策（已核实）**：
- **EMI 官方 addRecipeDecorator 生产环境不可用（重大修正，第一版翻车根因）**：`EmiConfig.showRecipeDecorators` 默认 = `EmiAgnos.isDevelopmentEnvironment()`（已核实 emi-source EmiConfig:500）——**生产环境 decorator 根本不执行**，真机截图齿轮按钮不出现。EMI 把 decorator 当开发功能，官方 API 无生产可用的"配方屏加按钮"入口（EmiRegistry 全方法 javap 核实）。
- **最终方案：EMI/JEI 两侧统一走 ScreenEvent + GearButton**（12×12 齿轮图标，视觉与 EMI 原版按钮一致）。EMI 侧复用旧 RecipeScreenButtonHandler 的反射取配方逻辑（tabs/tab/page + currentPage WidgetGroup 定位），仅按钮从 18×18 "A" 换成齿轮；JEI 侧用 RecipesGui public 方法（getArea / getIngredientUnderMouse，javap 核实），无深反射。
- **JEI 无官方 addon 按钮 API**：`IRecipeButtonControllerFactory` / `IIconButtonController` @since **15.38.0**，用户环境（恶咒落幕曲/涟漪之篇/ATM9）全部是 **15.20.0.12x** → 不可用。`IRecipeCategoryDecorator`（15.1.0+）只有 draw/tooltip，**不能加可点击按钮**。
- **bi-optional 类加载安全**：JEI 专属类只经 JeiGuard 短路调用；handler 用类名字符串判断避免 import 内部类。
- `IRuntimeRegistration`（15.20 的 registerRuntime 参数）是**注入式**（插件 set 组件），拿不到 IRecipesGui 实例——这也是 JEI 侧不注册插件的原因。

**验证**：compileJava + build 通过；PlanSelfTest 53 PASS / 0 FAIL（plan 层回归）；jar `autocraft-1.1.0.jar`（105515B）含 GearButton + 齿轮纹理，已删 EmiAutoCraftPlugin。

**提交记录（本次 3 个，均干净）**：
- `7a0e977` 第一版：EMI decorator 方案 + JEI ScreenEvent 方案（含误提交 1083 文件的撤销，见下）
- `c2c1c51` 修正：EMI decorator 生产环境不可用 → EMI/JEI 统一 ScreenEvent + GearButton（撤销了把整个 autocraft 目录误提交的 commit，重新精确 add 6 文件）
- `8b55f16` 补丁：RecipeScreenButtonHandler 类加载安全（纯 JEI 包不崩）

**二轮真机反馈（2026-08-15，adimn）**：**齿轮按钮仍未出现（"还是差太多"）**。已修正 EMI decorator 门控问题并改为 ScreenEvent 方案，但真机仍未确认生效。**原因待查**（推测清单，未验证）：
- ① 测试时未替换新 jar / 未重启游戏
- ② ScreenEvent.Init.Post 在 EMI RecipeScreen 上未触发（EMI 屏是否走 Forge ScreenEvent 生命周期未实证）
- ③ 反射 `tabs`/`tab`/`page`/`currentPage` 字段名在用户环境 EMI 版本不一致（我们按 1.1.24 jar 字段名写，用户包 EMI 版本未确认）
- ④ 按钮被 EMI 后续 rebuild 清掉（RecipeScreen 切配方重建 WidgetGroup 时是否连带清 renderables 未实证）
- ⑤ 齿轮纹理加载失败（资源路径/打包问题——但 jar 内已确认含 buttons.png）

**下一步排查（按顺序）**：先让用户确认 jar 已替换 + 重启；再看 `logs/autocraft.log` 是否有 "EMI 插件已注册" / "已向 RecipeScreen 添加自动合成齿轮按钮" 日志定位断点；最后核对用户环境 EMI 版本与 1.1.24 的字段兼容性。

### 8.8 M8（2026-08-15，未真机验证）EMI 按钮列对齐 + Screen 层持久按钮

**背景**：§8.7 的 ScreenEvent + GearButton 方案仍未在真机出现。代码审查后进一步修正 EMI 侧实现，使其更贴近 EMI 原版按钮列、并排除"按钮被 currentPage 重建清掉"的可能。

**改动**：
| 文件 | 内容 |
|------|------|
| `ui/AutoCraftRecipeButton.java`（新增） | EMI 专用按钮：继承 `net.minecraft.client.gui.components.Button`（可进 Screen.renderables），用 `EmiDrawContext` 绘制 12×12 齿轮，与 EMI 原版同风格；点击取当前配方首个产出 → 关闭配方屏 → `OrderTrigger.order` |
| `ui/RecipeScreenHolder.java`（新增） | 返回旧容器界面；无旧界面时直接关闭配方屏，避免点击后静默无反应 |
| `ui/RecipeScreenButtonHandler.java` | 按钮列算法对齐 `RecipeDisplay.addButtons`（WidgetGroup 高度/space/rows/换列），齿轮作为右列第 4 个（`BUTTON_INDEX=3`）；**仍用 `event.addListener` 挂 Screen 层**，不塞 `widgetGroup.widgets`——避免 `setPage` 重建 `currentPage` 时按钮被清掉；反射失败时兜底右上角，不丢按钮 |

**关键决策**：
- **为什么不直接继承 `dev.emi.emi.widget.RecipeButtonWidget` 并 `widgetGroup.widgets.add()`**：`RecipeButtonWidget` 虽 public，但加进 WidgetGroup 后会被 `RecipeScreen.setPage` 重建 `currentPage` 清空（切页/切配方即消失）。Screen 层 Button 不受影响。
- **按钮索引取 3 而不是 4**：EMI 右列默认 FILL/TREE/DEFAULT 共 3 个，齿轮应为第 4 个；旧代码用 4 会在 rows=4 时跳到第二列并留空第一列第 4 位。
- **纹理采样修正**：`AutoCraftRecipeButton` 源图是 32×16（两枚 16×16），绘制目标 12×12；此前误用 12×12 源区域会裁掉图标边缘。

**验证**：`gradlew build --offline` 通过；PlanSelfTest 53 PASS / 0 FAIL；jar `autocraft-1.1.0.jar`（107387B）已重新生成。

**真机待验**：仍需要用户替换最新 jar 并开 `logDebug=true` 复测 EMI/JEI 配方屏齿轮按钮是否出现；若仍不出现，按 §8.7 推测清单继续查 ScreenEvent 触发/EMI 版本字段兼容。

### 8.9 allowCrossLayer=false（2026-08-15，已完成）

**背景**：§3.7 配置项一直缺失，规划器恒为跨层合成。

**改动**：
- `PureSearchPlanner`：新增 `resolve(..., boolean allowCrossLayer)` 重载；`false` 时只允许“配方输入直接从库存满足”，不递归生产输入材料（自耗类配方跳过）。
- `Config`：新增 `allowCrossLayer`（默认 true）+ `/autocraft cross <on|off>` 会话级覆盖。
- `OrderTrigger.plan` / `AutoCraftCommand.runPlan` 接入开关。
- `PlanSelfTest` 新增 T7（5 项）：直合模式缺中间料不可行、原料齐全一步合成、允许原木→木板直接配方。

**验证**：`compileJava` + PlanSelfTest 58 PASS / 0 FAIL；`build` 通过。

---

## 9. 后续计划（接下来干什么）

### 9.1 短期（下一轮终测）
1. **二轮终测**（TEST-PLAN.md）：T1-T3 回归木稿/箱子、T6 预览数量、T9 预览→开始菜单保持、T10/T11 局内指令、T13 深层链书架
2. **真机冒烟** `gradlew runClient`：全流程（B 键/按钮/命令/预览/数量/取消）
3. **公网服验证**（M6 剩余验收）：无 mod 服务器跑 T1/T2，`delay` 2-3 不被反作弊判定

### 9.2 中期（M6 收尾/打磨）
4. ~~**allowCrossLayer=false**：仅背包原料直合~~ ✅ 已实现（2026-08-15，PureSearchPlanner + Config + /autocraft cross，T7 单测 5 项）
5. **清理 v1 死代码**：TreeDriver/EMI BoM 驱动路径确认无引用后删除（减小 jar 体积）
6. **数量输入增强**：任意值输入（非仅 1/4/16/64 预设）；Shift+触发 = 材料用尽
7. **背包空间预检**（§7 风险表"背包满"缓解落地）
8. **失败恢复策略**：当前失败即停；评估"跳过不可用分支继续"或"局部重试"

### 9.3 扩展（v2.1+，明确非当前范围）
9. **机器配方**（熔炉/烟熏/石切）：通过 JEI/EMI 配方数据或逐 mod 适配（§0 范围外）
10. **可缩放依赖图视图**：规划→渲染解耦已预留（§3.5），v2.1 加图视图不动规划层
11. **规划异步化**：§3.3 的 CompletableFuture + 主线程 ≤250ms 降级（当前同步规划，典型目标 <500ms 已够用）

### 9.4 发布评估
- 创意工坊 / CurseForge：纯客户端 bi-optional（服务端免装）、rs_integration 合规已声明（§0/§8.5）、版本基线 Forge 47.4.0 + EMI 1.1.24
