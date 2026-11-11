# Tasks

## 1. 新手引导与共享标题

- [x] 1.1 先在 `OnboardingScreenTest` 增加页面标题语义和五步操作可达性断言，运行该用例确认标题断言先失败；实现共享页面标题与引导视觉层级后，重复运行该测试并确认五步及前进/返回/跳过操作通过。

## 2. 录音页面

- [x] 2.1 先在 `RecordScreenTest` 增加页面标题语义、待录音/录音中状态及主要操作的 Compose 断言，运行确认标题语义先失败；重排录音状态、实时文字、系统声音提示和录音操作后，运行该类用例确认状态与回调通过。

## 3. 会议库

- [x] 3.1 先扩展 `LibraryScreenTest`，增加页面标题语义以及空库、列表、多选和回收站可用操作的断言，运行确认标题语义先失败；统一搜索、筛选、列表行、批量操作条与回收站状态的呈现后，运行该类用例确认原操作通过。

## 4. 纪要详情

- [x] 4.1 先扩展 `DetailScreenTest`，增加详情标题语义及纪要/转写切换、配置缺失和播放控件可达性断言，运行确认标题语义先失败；重排标题操作、章节、出处时间戳、任务状态与底部播放器后，运行该类用例确认内容和回调通过。

## 5. 设置页面

- [x] 5.1 先新增 `SettingsScreenTest`，增加页面标题语义、现有 Android 设置可达性和语言操作断言，并确认不出现 Apple Watch/iCloud 入口；运行确认标题语义先失败；重排设置分组、输入项、模型/存储/备份状态后，运行该类用例确认既有设置和回调通过。

## 6. 应用外壳与集成验收

- [x] 6.1 调整 `TingoRoot` 的底部/侧边导航视觉并保留当前窄屏、宽屏行为；运行 `TingoRootAdaptiveTest` 和 `LanguageSwitchTest`，确认导航、详情布局及语言切换通过。
- [x] 6.2 运行 `:app:testDebugUnitTest` 与 API 36 模拟器上的相关 Compose instrumentation tests，使用 `-PtingoSkipBundledModels=true` 构建 Debug 包并安装到连接的 Android 测试机；检查中英文、系统浅/深色、引导/录音/会议库/详情/设置截图及系统栏避让。
- [x] 6.3 按 code-review 技能从已确认基点执行 Standards 与 Spec 两轴审查，修复所有发现并重复审查与相关测试，直至两轴均无未解决问题。

## Workflow follow-up

- 将变更归档后，验证新 `android-visual-system` 规格已合并到 `openspec/specs/`。
