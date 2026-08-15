# 原版合成表自动链式合成 Mod — 架构设计（Forge 1.20.1）

> 状态：调研已完成，本文是给 Pi 子代理链的实现蓝图（K2 不写代码，只出架构）。
> 用户已对齐：做成 mod，调研先行；范围仅合成桌 2×2 / 3×3 的 crafting recipe；平台 Forge 1.20.1；客户端 mod（bi-optional，服务端不装也能用，类似 Crafting Tweaks）。

---

## 0. 目标与范围（已对齐）

- **目标**：玩家背包有全部**原始料**时，对一个目标物品触发"自动合成整条配方树"——逐层把配方填进合成桌、合成、把产物喂回上层，直到做出目标。
- **范围（v1）**：仅 `RecipeType.CRAFTING`（2×2 / 3×3 合成桌配方）。**不含**熔炉 / 烟熏 / 石切 / 其他机器。
- **平台**：Forge 1.20.1。
- **形态**：客户端 mod（bi-optional，类似 Crafting Tweaks），在公网服上无需服务端安装即可用客户端功能。
- **非目标**：不做 AE2 深度样板（那是另一套扁平 pattern 体系，根因相同但不是一回事）。

---

## 1. 查证过的真实 API（Forge 1.20.1，非猜测）

### 1.1 配方遍历
- 客户端即可拿到**完整**配方表（MC 给客户端一份用于显示）：
  `Minecraft.getInstance().level.getRecipeManager()`
- 取全部合成配方：
  `recipeManager.getRecipesFor(RecipeType.CRAFTING)` → `List<RecipeHolder<?>>`
  - ⚠️ 注意：NeoForge 1.21 改成了 `recipeMap().getRecipesFor(RecipeType, CraftingInput, level)`，**1.20.1 用老 API，勿混**。
- 单个 `CraftingRecipe`（接口）：
  - `getResultItem(RegistryAccess)` → `ItemStack`（产物）
  - `getIngredients()` → `NonNullList<Ingredient>`
- `Ingredient.getItems()` → `ItemStack[]`：该槽位**所有可替代物品**（如木板 tag 含所有木头）。解析时要挑一个具体物品作代表（取 `[0]`）。
- 匹配具体输入：`RecipeManager` 提供 `getRecipe(IInventory, World, RecipeType)`；合成匹配用 `InventoryCrafting` 作容器。

### 1.2 合成容器（客户端驱动）
- 3×3 合成桌：`net.minecraft.world.inventory.CraftingMenu`
  - 槽位布局（Crafting Tweaks 自带注册佐证）：结果槽 index=0，矩阵槽 `gridSlotNumber=1, gridSize=9`（即槽 1..9 是 3×3 网格）。
- 2×2 背包合成：`net.minecraft.world.inventory.InventoryCrafting`（在玩家背包 GUI 内）。
- **关键简化**：原版允许"小配方在大网格里匹配"（子区匹配）。所以**只要开着一个 3×3 合成桌**，2×2 配方（如木棍）也能在里面做。Executor 只需驱动 `CraftingMenu`，无需处理 2×2 背包合成。
- **驱动方式**：直接操作 `menu.slots` 的 `ItemStack`——
  - 把材料从玩家背包槽移到矩阵槽（等同 JEI `+` / Shift+`+` 的转移逻辑）；
  - 然后 shift-click 结果槽触发合成（等同 Crafting Tweaks 右键合成一组）；
  - 取走产物回背包。

### 1.3 反作弊风险（公网服必考虑）
- 公网服若装 NoCheatPlus 等，快速连点会被判 macro / fastclick。
- Crafting Tweaks 右键合成通常被容忍，但**整条树全自动、每秒几十次合成**可能触发。
- **设计必须含可配置 inter-craft 延迟**（参考 AutoCraftMod 的 `/autocraft delay`，默认单人不延迟、服上给 2+ ticks）。

---

## 2. 模块架构

```
AutoCraftMod (Forge 1.20.1, client)
├── RecipeResolver          // 树解析 + 数量传播 + 环检测 + 多配方选择
│   ├── buildTree(targetItem, inventorySnapshot) → CraftNode (DAG)
│   ├── propagateQuantities(root)                // 自底向上算每节点需做几个
│   ├── pickRecipe(item) → CraftingRecipe       // 多配方时选最优
│   └── detectCycle(node, visited)              // 防死循环
├── CraftExecutor            // 驱动 CraftingMenu 执行
│   ├── fillGrid(recipe, menu, playerInv)      // 等同 JEI 转移：材料入矩阵槽
│   ├── triggerCraft(menu)                      // shift-click 结果槽
│   └── loopWithDelay(delayTicks)              // 每层之间延迟
├── InputHandler             // 触发入口
│   └── keybind (在 JEI 物品上按某键 / 合成桌里按钮)
└── Config                   // inter-craft 延迟、是否允许跨层、最大层数
```

---

## 3. 核心算法

### 3.1 buildTree（DAG 构建）
- 入参：目标 `Item` + 玩家背包 `ItemStack` 快照（含数量）。
- 对目标：取它的 crafting recipe（若无 → 该物品是叶子，检查背包是否够 → 够则 satisfied leaf，不够则 missing）。
- 对 recipe 的每个 `Ingredient`：取 `getItems()[0]`（挑一个具体物品作代表；tag 多解择一）。若该物品可再被 crafting（有 crafting recipe 且不在 visited）→ 递归建子节点；否则视为叶子。
- 叶子两类：
  ① 背包够 → satisfied；
  ② 背包不够 → missing（整棵树无法完成，报错并高亮缺什么）。

### 3.2 propagateQuantities（数量传播，最难的部分）
- 自顶向下需求：root 需要 1 个目标。
- 对每个节点：其 recipe 每次产出 `outCount`（如木板 1 原木→4 木板，outCount=4）。需要 `ceil(needed / outCount)` 次该配方。
- 每次消耗各 ingredient 的 `count`。累加回父节点需求。
- 最终得到：**每个物品总共需要几个**（含中间产物），与背包已有量对比，算出"还差 / 够不够"。

### 3.3 pickRecipe（多配方抉择）
- 一个物品可能有多个 crafting recipe（如箱子多种木板组合）。
- 启发式：优先选"所有 ingredient 要么背包已有、要么可继续向下合成"的那个；平手时选 ingredient 总数最少 / 最便宜的。
- 这是**设计决策点**，v1 用上述启发式，后续可让用户覆盖。

### 3.4 detectCycle
- `visited` 集合 + 最大深度（如 64）。遇到回边 → 报错"检测到配方环"，不崩。

---

## 4. Executor 执行流（leaf-most first）

1. 对 DAG 做拓扑排序，叶子优先。
2. 对每个可合成节点（按拓扑序）：
   - `fillGrid`：把该配方的材料从背包移入 `CraftingMenu` 矩阵槽（材料不足则先递归合成其前置——拓扑序已保证前置先完成）。
   - `triggerCraft`：shift-click 结果槽 → 合成一组。
   - 取走产物到背包。
   - `sleep(delayTicks)`（若配置了延迟）。
3. 重复直到 root 做出。

---

## 5. 边界与风险登记

| 风险 | 处理 |
|---|---|
| 多配方（箱子） | pickRecipe 启发式 + 用户可覆盖 |
| tag 多解（木板） | 取 `getItems()[0]` 作代表 |
| 配方环 | detectCycle 报错不崩 |
| 背包满取不出产物 | 检测满 → 提示先腾空间 |
| 服务端不认（网格不合法） | fillGrid 后校验 `menu` 确有合法结果再 trigger |
| 公网反作弊 | 可配置延迟；默认单人不延迟 |
| 客户端 recipe 表不全？ | 客户端 recipe manager 是完整副本（用于显示），解析足够；若担心，走服务端查询包 |
| NBT 敏感配方 | v1 忽略 NBT 匹配（大多数 crafting 不依赖 NBT） |

---

## 6. 与现有 mod 的关系（不重复造轮子）

- **JEI**：我们只借用其"物品→配方"的展示，不依赖其转移逻辑（自己实现 fillGrid）。可检测 JEI 是否在，按需复用其 transfer helper。
- **Crafting Tweaks**：其右键合成 / Refill 是单配方循环；我们不在"单配方"上重复，只在"跨层"这块补空白。可检测 CT 是否存在以复用其 craft-stack 行为，但 v1 自实现更可控。
- **JECT**：可视化配方树；我们可读取其树做展示，但**执行**是我们独有的。

---

## 7. 下一步

- 待你 review 这份架构后，我写 **Pi 实现提示词**（scout→planner→worker→reviewer 链），由 adimn 本地编译测试。
- 交付物形态：Forge 1.20.1 mod 工程骨架（build.gradle / mods.toml 风格 / 主类 + 上述模块 stub）。
- 若你更想先只跑现有工具（JECT 看树 + CT 单配方快循环），本 mod 可暂缓——但本设计已验证"自动跨层"是真实空白，值得做。

---

# v2（2026-08-13）：项目改名 autocraft + 规划器改为有界回溯搜索

> **文档状态**：§0–§7 = v1 设计（自底向上传播算法，**已废弃，见 §8 对比**）。
> **项目改名**：`emi-autocraft` → `autocraft`（目录/包 com.adimn.autocraft/主类 AutoCraft/modid autocraft，不再绑定 EMI，JEI/EMI 双触发）。
> **触发**：用户发现 rs_integration（Elten-huanghuang，RS 递归自动合成）几乎做了同类功能，决定借鉴其**规划思想**自研轻量版：仅依赖 JEI/EMI、纯客户端。

## 8. v2 核心：有界回溯搜索规划器（自研，思想参考 rs_integration）

### 8.1 为什么重构 v1（对照 §3）

v1 是**确定性图算法**（自底向上传播）：
```
buildTree（每物品 pickRecipe 启发式选定一个配方）→ propagateQuantities（自底向上算数量）→ 拓扑执行
```
弱点（对照 rs_integration 的 PureRecipePlanner 才看清）：
1. **多配方抉择一次定死**——启发式选错无法回退（箱子有 N 种木板组合，选了一个缺料的就整树失败）
2. **没有"库存状态"**——tag 替代品（如羊毛）怎么跨变体凑数量、中间产物被其他分支消耗，都没建模
3. **环处理只"报错不崩"**——自耗型配方（羊毛染色互转）没有净增益判断
4. **无搜索预算**——组合爆炸无防护（护栏只在 §5 表格里一句话）

### 8.2 算法：有界回溯搜索（约束求解范式）

**数据结构**（自研，通用图设计；与 rs_integration 的 ImmutableRecipeGraph 同构但不抄代码）：
```java
record MaterialRef(ResourceLocation itemId, String nbt)          // 物品 + NBT
record IngredientRef(List<MaterialRef> alternatives, int count)  // 槽位；tag 多解 = alternatives
record RecipeNode(ResourceLocation recipeId, MaterialRef output, int outputCount,
                  List<IngredientRef> inputs)                    // 配方节点
record ImmutableRecipeGraph(Map<MaterialRef, List<RecipeNode>> recipesByOutput)  // 物品→产出配方
```

**Search 核心**（状态 = stock 库存快照 + pending 任务队列 + resolving 正在求解集 + steps 步骤清单）：
```
任务：DemandTask(ingredient)             // 需要某物品 N 个
     CompleteRecipeTask(recipeId, output, outputCount, consumeCount, batches)

solveDemand(需要 N 个 X) 三条路，顺序尝试失败回溯：
  ① 库存直接扣            —— stock 里有 X 且够 → 扣，继续
  ② tag 替代品聚合         —— 多个变体按"预留未来单件需求"排序凑够 N
  ③ 生产 X                —— 枚举 recipesByOutput[X] 每个配方：
                             batches = ceil(needed / 每批产出)
                             生成分支：配方输入→新 DemandTask（数量×batches）+ CompleteRecipeTask → 递归
                             失败 → 回溯试下一个配方/下一条路

环保护：
  resolving 集合          —— 正在求解的物品，回边跳过（防无限递归）
  selfConsumedPerBatch    —— 配方输入含自身输出（如羊毛染色）→ netGain = 产出 - 自耗
                            netGain ≤ 0 → 该配方不可用于生产（no-gain 剪枝）
护栏（防组合爆炸）：
  maxSteps（步骤上限）/ maxSearchStates（状态上限 65536）/
  callDepth（递归深度 512）/ deadlineNanos（时间上限）/ 失败记忆（状态哈希去重 ≤8192）
```

**输出**：`List<PlannedStep(recipeId, batches)>` —— 有序执行清单（做哪个配方、做几次）+ missing / remaining。

### 8.3 为什么搜索优于传播（范式结论）

| 问题 | v1 传播 | v2 回溯搜索 |
|---|---|---|
| 多配方 | 启发式一次定死 | 搜索分支 + 失败回退 |
| 库存交互 | 无状态 | 实时扣减 + 替代品聚合 |
| 环 | 报错不崩 | resolving + 自耗净增益剪枝 |
| 组合爆炸 | 无防护 | 状态/深度/时间/记忆护栏 |

### 8.4 模块架构 v2

```
RecipeIndex（客户端 getRecipeManager()，crafting 过滤）
  → ImmutableRecipeGraph（自研，§8.2）
    → PureSearchPlanner（回溯搜索，§8.2）
      → List<PlannedStep> 有序执行清单
        → CraftExecutor（驱动 CraftingMenu：fillGrid + shift-click；复用 v1 骨架 TreeDriver）
触发：JEI/EMI 物品上按键 / 合成桌按钮（KeyHandler / RecipeScreenButtonHandler 已有）
GUI v1：步骤列表界面（清单 + 缺失高亮；可缩放依赖图留 v2，参考其"规划产出清单→渲染层消费"架构思路，界面自研）
```

### 8.5 许可合规（重要，2026-08-13 实证）

rs_integration = **专有许可（Proprietary License，Copyright 2026 Huanghuang，All rights reserved）**，GitHub 标注 NOASSERTION：
- ❌ 不得复制其任何源码（RecipeIndex / PureRecipePlanner / GUI 渲染层）
- ✅ 回溯搜索、图结构、搜索护栏为**通用算法/设计**，可参考思想自行实现
- 本项目所有代码自研；本文档引用其设计思想并注明来源；GUI 仅借鉴"规划→渲染解耦"架构
- 参考源码快照已留 `autocraft/_rsref/`（仅学习参考，不入发布物）

### 8.6 依赖与形态（用户拍板，2026-08-13）

- **仅依赖 JEI/EMI**（客户端，可选检测）；不依赖 RS / 任何服务端 mod
- **纯客户端**（bi-optional），公网服免装服务端（原版兼容，同 Crafting Tweaks 定位）
- 范围 v1：仅 crafting 配方（合成桌 2×2/3×3）；机器配方后续通过 JEI/EMI 配方数据或逐 mod 适配

### 8.7 实施清单

- [ ] 数据结构：MaterialRef/IngredientRef/RecipeNode/ImmutableRecipeGraph（新包 `plan/`）
- [ ] 规划器：PureSearchPlanner（回溯 + 环保护 + 护栏 + 失败记忆）
- [ ] 索引：RecipeIndex（客户端 RecipeManager，crafting 过滤）
- [ ] 执行：CraftExecutor 接 PlannedStep（复用/改造 TreeDriver 骨架）
- [ ] GUI：步骤列表界面（新，清单 + 缺失高亮）
- [ ] 触发：JEI/EMI 双入口（改 RecipeScreenButtonHandler）
- [ ] 测试：规划器离线单测（图构建 / 多配方 / 环 / 预算护栏）
- [ ] 命名迁移验证：编译通过（包/类/mods.toml 已改 autocraft）

## 9. 复盘：为什么我们当初想不出"回溯搜索"（2026-08-13）

> 对照 v1 设计文档（§3）与 rs_integration 实现（PureRecipePlanner），诚实复盘设计思维差异。

**根因一：我们把问题归类为"图遍历"，它本质是"约束搜索"**
- 我们 v1 从"配方树"这个**比喻**出发 → 设计成树构建 + 数量传播（图遍历范式）
- rs_integration 从"**有库存约束的求解**"出发 → 设计成带状态的回溯搜索（约束求解范式）
- 教训：先问"这是什么问题类型"——确定性子图（无多解/无交互）才配传播；有**多解 + 库存交互 + 环**就是搜索问题。

**根因二：场景复杂度差异掩盖了需求**
- 我们 v1 场景 = 玩家背包 + 合成桌（小规模、配方少、冲突少）→ "传播"直觉上够用
- rs_integration 场景 = RS 网络 + 机器 + 大库存 + 多配方冲突（NBT 敏感/催化剂复用/tag 燃料）→ 复杂度和错误率逼出"搜索"
- 教训：不能按"我的简单场景够用"设计算法；要为**真实整合包的规模**设计（我们 EMC 引擎踩过同一坑：17 万配方）

**根因三：没有把"库存"建模进算法状态**
- 我们 v1：原料够 = 静态假设，中间产物自动够
- rs_integration：stock 是搜索状态的一部分 → 才能处理"tag 变体凑数、中间产物被别的分支吃掉、预留未来需求"
- 教训：算法状态要包含**会变化的资源**，不能做静态假设

**根因四：多配方抉择从"策略"降级为"搜索"的认知盲区**
- 我们写"pickRecipe 启发式（选最便宜）"——把决策当策略问题
- rs_integration 让它**运行时搜索试错**——决策是求解的一部分，失败自动回退
- 教训：当"最优策略"不可判定时（依赖运行时库存），交给搜索而不是启发式

**根因五：缺乏"组合爆炸"意识（工程护栏思维）**
- 我们 v1 只有"detectCycle 报错不崩"，没有搜索预算
- rs_integration 有全套护栏（状态上限 65536 / 深度 512 / 时间 / 失败记忆 8192）——说明作者**先想到会爆炸，再设计护栏**
- 教训：递归/搜索类算法设计时**先设计终止性与预算**，再写主体（这也是我们 EMC 引擎 v1 的护栏思路，只是没同步到 autocraft）

**总结**：我们 v1 是"自上而下给架构"（直觉驱动，把嵌套合成当图遍历）；rs_integration 是"从执行需求演化"（问题驱动，把嵌套合成当约束搜索）。差距不在代码能力，在**问题分类 + 状态建模 + 爆炸意识**。已固化到 autocraft v2（§8）与 EMC 引擎（§15/§16）的设计原则：多解/交互/环 → 默认搜索范式 + 预算护栏。
