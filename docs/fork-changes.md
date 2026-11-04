# Xime fork 维护说明

本仓库基于 [Xime](https://github.com/ximeiorg/Xime)，当前上游基线为
`30bded554629d6ee54035439fc99af1f5146a95d`（3.5.0-alpha.1）。
Rime 资源子模块为 `ee57fa7832ee1f3459eca8bc17d36aa246e16340`。

## 本地功能

- 内置白霜全拼、官方小鹤双拼和同一 schema 的混输方案；资源增量复制保留用户修改。
- Bitwarden 账户同步与保险库界面，以及 Android Autofill、凭据提供方接线。
- 键盘显示、布局、工具栏和触觉设置，以及专题词库下载和部署。
- 设置界面沿用现有 Compose 结构；关闭共享点击/长按涟漪，保留独立底栏选中态动画和触觉反馈。
- 构建仅输出 arm64-v8a APK；细胞词库通过 `packageFrostCellDictionaries` 单独打包。

白霜固定版本为 `f1bc68b308adcca916c51bcaad25a267876e6efe`，
依赖和 GPL 许可证见 [资源说明](../app/src/main/assets/rime-frost/README.md)；
可选词库见 [组件说明](../app/optional/rime-frost-cells/README.md)。
独立词库服务源码维护于 [Xime-Dictionaries](https://github.com/Chengeeker/Xime-Dictionaries)。

## 当前验证边界

2026-11-04 的 `:app:compileReleaseKotlin` 与 `:app:assembleRelease` 成功。
APK 静态检查确认仅含 arm64-v8a、`testOnly=false`、正式签名有效且通过 16 KiB ZIP 对齐。
本轮未运行完整单元测试和设备测试；首次安装、升级保留、真实 Bitwarden 账户、
Android 输入/Autofill、横屏/平板以及触控动效仍需设备验收。
UI 对照状态见 [Review 映射记录](REVIEW_UI_REUSE.md)。

仓库不提供签名私钥、账号数据、APK、构建缓存或本地视觉参考图片。
