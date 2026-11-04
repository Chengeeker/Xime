# Review UI 迁移映射与源码快照

记录日期：2026-10-13。目标项目：本仓库 Xime。

## Review 参考快照

- 参考项目：Review（独立维护的参考仓库）
- HEAD：`25a02ef52b1f15f4124a20d0473b7cccde871788`
- Flutter / Dart：规范记录为 Flutter 3.47.1 / Dart 3.13.1；当前终端未提供 flutter/dart 命令，未重新验证 SDK。
- `pubspec.yaml`：`3.2.0+171`（以规范记录为准）
- 工作区：有未提交修改；以下 UI 参考文件在当前工作区均未显示修改，按本地文件 SHA-256 固定，不把 HEAD 单独当作快照。

| Review 源文件 | 当前 SHA-256 |
|---|---|
| `lib/main.dart` | `EAEFE68AF9A1F1DFE5ABEF0A6511EE0B983D75858750C13AB2697DA42E0EF61D` |
| `lib/core/theme/app_theme.dart` | `2A10A7ED5843C1538B1E3319629F78058399ADFA871B0B4913618BC29A1DFB0E` |
| `lib/core/theme/theme_provider.dart` | `7032D4A14E4A7BFC6F568F6D0BB686138D57AE453C5B5346E1605900328F5D06` |
| `lib/core/theme/weibo_style_provider.dart` | `AA9F4F04AC14ACF0A35B715D3E326FE5DC3E71ABF6761B157E45ADD87D68BDEF` |
| `lib/core/design_system/components/review_page_scaffold.dart` | `7FED22C3D3827DF646E180499449034800BD1D1FF5A1485D1F3EE1CFA6C0929A` |
| `lib/core/design_system/components/review_frosted_app_bar.dart` | `DDF9DC2BFB6577ED90C9F960CB5F203AAE9A6FB79AEEC40D217A1A8583A4D358` |
| `lib/core/design_system/components/review_navigation_bar.dart` | `AFF28E61DAC09E0078F56E3AC3ADA12277CBC85F641DE1B5AC25E82C0A16FE0F` |
| `lib/features/home/presentation/main_scaffold.dart` | `F9E6E472F2BBC7B9189B1063580AB4BEE2C9434E91815EF43E56BFF21CD03311` |
| `lib/core/widgets/app_section_card.dart` | `78991DBB20EC2E393FDBCB91794FBAA6517E06FD0DAA5FD71D1BD4C9F48E2B58` |
| `lib/core/design_system/components/review_preference_tile.dart` | `A237CD575265BE83242005A70B7D95AE78DF18310B0B9939BB2470375C0E772C` |
| `lib/core/design_system/components/review_switch_tile.dart` | `6AE3593496AD1533E494FC15BD5E9857B32C74A0897FF3DADD406EE27490DBAB` |
| `lib/core/design_system/components/review_choice_tile.dart` | `BC666FD17A4037F234D8CD570217C07472774E074DAD65B79CBBAF2C78CB3095` |
| `lib/features/settings/presentation/settings_view.dart` | `3033E335FA998D82CB2F5C5413420750F524C77110787BF13195DC6B95A8BA00` |
| `lib/features/profile/presentation/theme_settings_page.dart` | `5C9329E3BACA937641CB4A1CB1CDCC78B925077AA31DC7125390A2E76C105964` |
| `lib/core/utils/app_dialog.dart` | `4C887D5BC939D911C2D16021C6EDC518BAE315BA334B6948BD3FE7A6C20F6844` |
| `lib/core/utils/app_toast.dart` | `F224A658E6371F59B0D23B3800633EE3D09A0D7ABDFF73038348CF48ED84365D` |
| `lib/core/utils/haptic_feedback_util.dart` | `9CDFCBD2F1FA272B95B2F9A857BDC1B7C17787CB39D76FAF42DA825BA8FBEA12` |

## 页面映射

| Xime 目标页面 | Review 对应页面 | 复用的实现/参数 | 允许替换的业务内容与边界 |
|---|---|---|---|
| 设置首页：`SettingsMainScreen.kt` | `settings_view.dart` | 无标题独立栏的页面几何、分组卡片 24dp、标题与卡片 12dp、大组间距 32dp、左右 16dp、安全区与浮栏留白 | Xime 设置入口、图标、描述、回调；入口数量不同，需保留可滚动列表 |
| 设置详情：`*SettingsScreen.kt`、`SettingsComponents.kt` | `theme_settings_page.dart` 与三个 preference tile | Review 子页顶栏/内容 inset；设置分组无阴影/无描边；行内边距、图标至文本 16dp、分隔线、字号及语义角色 | Xime 参数、开关、选项、说明文案和验证状态；保留 Xime 特有表单与滚动区域 |
| Xime 主 Activity / 设置 NavHost | `main.dart`、`review_page_scaffold.dart`、`app_theme.dart` | Material 配色/字体/系统栏接线、LTR/RTL 与减少动画下的推入/返回规则；保留现有 Xime 260ms 设置路由动画时需说明其与 Review 320/220ms 的差异 | Android Compose 框架及输入法业务；不得改变键盘/窗口生命周期 |
| 设置页路由动画 | Review 普通路由动画 | 横向滑入、easeOutCubic / easeInCubic、覆盖静止旧页；按 Review 路由参数实现 | Compose Navigation 生命周期、预测式返回支持需单独检查 |
| Bitwarden 保险库首页/设置标签 | 无直接对应的 Review 页面；以 `review_navigation_bar.dart`、`app_section_card.dart`、`review_frosted_app_bar.dart` 作为组件映射 | 仅复用对应的导航/卡片/顶栏表现参数 | Bitwarden 数据分组、TOTP、保险库入口、生成器和设置回调；标为组合适配，不能声称存在像素对应页面 |
| 输入键盘、候选栏、输入法工具条、设置向导、词库/插件/模型市场、备份/权限/文件浏览 | 无对应 Review 页面 | 仅使用已映射主题、导航行/卡片、按钮/弹层与动效规则 | 输入法专用触控几何、键盘反馈和高密度业务 UI 保持自身结构；逐页截图未验收前列为待验收 |

## 初步迁移决策

- 使用 Review 的实际源码调用参数，而不是将 Review 的 Flutter 页面机械翻译成 Compose 默认组件。
- 默认悬浮导航为 264×64dp、圆角 32dp、三个入口；贴底导航高 68dp，两种分支必须分开处理。
- 设置首页分组圆角 24dp、无描边/阴影/tint；首页与子页采用各自的间距规则。
- Review 普通路由为 push 320ms / pop 220ms；Tab 是单独的 260ms 双向平移和页面保活规则。没有 Xime 应用级底栏的页面不添加 Review 主导航。
- 顶栏磨砂由裁剪区域内 sigma 20 背景模糊及 alpha 0.45 Material 色层、0.95→0 渐变组成。Compose 跨框架的实时背景模糊需要单独实测，若目标平台没有等价实现则记录差异，不得只凭编译宣称一致。
- 当前无连接 Android 设备（`adb devices -l` 为空），本轮截图、录屏及平板/横屏对照无法在本机执行；具体未比对的页面必须留在待验收清单。

## 验收环境与待办

- 规范所述的相同设备、DPR/逻辑尺寸、字体缩放、locale、系统导航、主题种子、动态取色、深浅/纯黑、内容及滚动位置均须固定；Xime Android 与 Review Flutter 仍有字体栅格化差异。
- 截图待覆盖：设置首页、主题子页、输入设置、键盘顶栏/候选栏、Bitwarden 保险库及编辑/生成器、弹窗/底部面板；浅色、深色、纯黑、悬浮/贴底、大字体、横屏和平板按页面能力覆盖。
- 转场待录屏：普通设置页 push/pop、返回手势、键盘面板切换、保险库内标签和编辑流。
- 对应差异、修正及证据保存在本地开发记录，未完成画面对照的页面继续标为待验收。
