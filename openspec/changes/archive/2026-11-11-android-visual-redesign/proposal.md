# Proposal

## Why

Android 当前正式界面与已确认的 Tingo 视觉方向不一致，且各页面、空态和错误态缺少统一的视觉层级。现在将这套风格应用到完整生产界面，可以在保留录音、会议管理和设置能力的前提下，形成一致、可继续演进的 Android 体验。

## What Changes

- 建立 Android 视觉系统，并将获批的 Hallmark 风格应用到全部正式页面与状态；以纸白底、Tingo 蓝色强调、克制的细分隔和清晰的文字层级作为视觉基线，11 个预览状态作为参考，同时覆盖预览未展示的生产页面和子流程。
- 统一引导、录音、会议库、纪要详情和设置的排版、留白、控件及状态呈现；使用现有中英文与系统字体，保留现有数据、交互与功能逻辑。
- 保留五步新手引导、现有中英文、系统明暗主题，以及手机底部导航和宽屏侧栏/列表详情并排的 Android 自适应结构。
- 设置界面仅呈现 Android 已支持的能力；不加入 Apple Watch、iCloud 等平台专属入口。
- 设计预览目录继续只用于 Debug；不改变录音、转写、纪要生成、数据格式或服务 API。

## Capabilities

### New Capabilities

- `android-visual-system`: 定义 Android 正式界面的视觉语言、共享组件和跨页面呈现规则，并覆盖系统主题与自适应布局下的视觉表现。

### Modified Capabilities

None. 现有功能需求保持不变；视觉呈现规则由新能力单独定义。

## Impact

- Android Compose 正式界面：`TingoRoot`、新手引导、录音、会议库、纪要详情、设置及共享 UI 组件。
- Android 主题与本地化资源：`ui/theme/`、`res/values/`、`res/values-en/`。
- 不涉及 ViewModel 业务行为、存储结构、录音/转写引擎或网络 API；Debug 预览目录保留为视觉核对入口。
