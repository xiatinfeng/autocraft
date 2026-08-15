# AutoCraft 使用说明（v1.1.0）

一个 Forge 1.20.1 **纯客户端**模组：背包有全部原料时，对目标物品一键自动合成**整条配方树**（嵌套合成）——省略中间步骤（分解木板、合成木棍），直接从原木做出木稿。

> 模组 ID：`autocraft` ｜ 显示名：`AutoCraft` ｜ 版本：`1.1.0`
> 仅客户端需要；服务端无需安装（公网服免装，同 Crafting Tweaks 定位）。
> 不依赖 EMI/JEI 也能用（合成桌入口）；EMI 存在时额外提供悬停/按钮入口。

---

## 1. 安装

1. 确认整合包是 **Forge 1.20.1**（forge `47.4.0` 验证通过）。
2. 把 `autocraft-1.1.0.jar` 丢进整合包的 `mods/` 文件夹。
3. 启动游戏。EMI 可选（有则悬停/按钮可用，无则不影响合成桌入口）。

## 2. 核心用法（三条入口）

### ① 合成桌内按 `B`（推荐，无需 EMI）
- 打开合成台（3×3），把原料放背包。
- 在合成台网格里摆出目标物品（结果槽出现产物）→ 按 `B`。
- 弹出**计划预览**（步骤列表 + 开始/取消）→ 点「开始合成」→ 自动按计划逐层合成到做出目标。
- 执行中再按 `B` = **立即取消**。

### ② EMI 悬停物品按 `B`
- EMI 界面里悬停任意物品 → 按 `B` → 预览 → 开始。
- 需要先打开合成台（执行器要求 3×3 合成台界面）。

### ③ EMI/JEI 配方屏「齿轮」按钮
- 打开任意配方的详情屏 → 点击配方区右侧的 12×12 齿轮按钮 → 自动返回合成台并下单该配方产出。
- JEI 存在时同样会在 JEI 配方屏加齿轮按钮（悬停目标物品后点击）。

### 命令（单机调试）

| 命令 | 作用 |
|------|------|
| `/autocraft plan <item>` | 只规划，打印步骤清单（不执行） |
| `/autocraft craft <item>` | 规划 + 预览 + 执行（需开合成台） |
| `/autocraft delay <ticks>` | 会话级设置批次间隔（公网服防反作弊） |
| `/autocraft preview <on\|off>` | 会话级开关计划预览 |
| `/autocraft cross <on\|off>` | 会话级开关跨层合成（关闭=仅背包原料直合） |
| `/autocraft stop` | 停止当前执行 |

例：`/autocraft plan minecraft:wooden_pickaxe`

## 3. 执行行为

- **规划**：有界回溯搜索（护栏：256 步 / 65536 状态 / 500ms / 失败记忆），不可行时聊天栏报缺失材料。
- **执行**：每客户端 tick 只发 1 个容器点击包（接近人手）；每批精确放 1 个原料 → 校验结果槽 → shift-click 合成。
- **进度**：聊天栏显示「▶ 步骤 i/N：配方id」；完成显示「自动合成完成 ✓」。
- **失败**：材料不足 / 结果无效 / 合成台关闭 → 停止并保留已合成产物，不回滚。

## 4. 配置

文件位置：`config/autocraft-client.toml`

| 配置项 | 默认 | 说明 |
|--------|:----:|------|
| `delayTicks` | `20` | （旧版 EMI BoM 驱动，保留） |
| `interCraftDelayTicks` | `0` | 每批次合成间隔（tick）。**公网服建议 2+** 防反作弊 |
| `maxCraftsPerTick` | `8` | 每 tick 合成批次上限（当前状态机天然每 tick ≤1 批） |
| `showPlanPreview` | `true` | 下单前弹计划预览界面 |
| `maxSteps` | `256` | 规划器执行步骤上限 |
| `maxSearchStates` | `65536` | 规划器搜索状态上限 |
| `planningTimeoutMs` | `500` | 规划时间预算（ms） |
| `allowCrossLayer` | `true` | 允许跨层合成（false=仅背包原料直合） |
| `blacklistItems` | `[]` | 黑名单物品（mod:id），禁用为目标或原料 |
| `chatFeedback` | `true` | 聊天栏提示 |
| `logEnabled` / `logDebug` | `true` / `false` | 文件日志（`logs/autocraft.log`） |

## 5. 反作弊说明

- 纯客户端逻辑、不伪造槽位：全部走标准容器点击包（`handleInventoryMouseClick`），服务端与手点无差异。
- 每 tick 最多 1 个点击包；批次间可配 `interCraftDelayTicks`。公网服建议调大到 2+。

## 6. 常见问题

- **按 B 没反应？** 确认：①正打开合成台且结果槽有产物（或 EMI 悬停物品）②聊天栏有没有提示。
- **「请先打开合成台（3x3）」？** 执行器只支持合成桌菜单；背包 2×2 暂不支持。
- **预览弹出来了但开始后失败？** 打开 `logs/autocraft.log` 看执行日志；通常是材料在规划后被消耗/界面变化。

## 快速上手 checklist（一次测全功能）

1. 进单机世界，放 16 个原木进背包。
2. 打开合成台 → `/autocraft plan minecraft:wooden_pickaxe` → 应显示 3 步计划。
3. `/autocraft craft minecraft:wooden_pickaxe` → 预览 → 开始 → 应自动做出木稿。
4. 再放 8 个原木，摆一个木稿到网格 → 按 `B` → 预览 → 开始 → 同上。
5. 执行中按 `B` 或 `/autocraft stop` → 应立即停止。
6. 有 EMI：配方屏点「A」按钮 → 自动下单。
7. 缺料时触发（如目标钻石镐）→ 聊天栏报「缺少」。
