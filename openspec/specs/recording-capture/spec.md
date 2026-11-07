# recording-capture Specification

## Purpose
TBD - created by archiving change add-android-recording-asr. Update Purpose after archive.

## Requirements

### Requirement: 麦克风录音

应用 SHALL 能在用户明确触发后开始录音，采集 16 kHz、单声道、16 bit PCM，并按会议目录
（`<根目录>/<YYYY-MM-DD_HHMM_标题>/录音.wav`）落盘；录制的文件 MUST 能被现有引擎的会议目录规则识别。

#### Scenario: 开始与结束录音

- **WHEN** 用户在录音页按下开始并授予麦克风权限
- **THEN** 应用创建会议目录、开始写入 `录音.wav`，并把该会议以"录制中"状态显示在会议列表
- **WHEN** 用户按下结束
- **THEN** 文件正常收尾（wav 头长度字段被回填），目录出现在会议库中且可打开

#### Scenario: 拒绝麦克风权限

- **WHEN** 用户拒绝 `RECORD_AUDIO` 权限
- **THEN** 录音不开始，界面说明"缺少麦克风权限，无法录音"，且会议库浏览等其他功能仍可用

### Requirement: 录音过程中的状态与保活

录音 SHALL 在前台服务中运行，锁屏或切到其他应用时 MUST 继续；通知栏 SHALL 显示录音状态与"结束录音"操作。

#### Scenario: 切到后台继续录

- **WHEN** 录音中用户按 Home 键切走
- **THEN** 录音继续，通知栏出现持续通知，回到应用时计时与波形状态与实际录音时长一致

#### Scenario: 通知权限被拒绝

- **WHEN** 用户拒绝 `POST_NOTIFICATIONS` 但授予了麦克风权限
- **THEN** 录音仍可进行（前台服务照常启动），界面提示"通知被关闭，录音状态只在应用内可见"

### Requirement: 暂停与继续

录音 SHALL 支持暂停与继续，暂停期间 MUST NOT 写入音频数据，继续后 MUST 追加到同一个文件。

#### Scenario: 暂停后继续

- **WHEN** 用户暂停 10 秒再继续
- **THEN** 产物仍是一个 `录音.wav`，且总时长不包含暂停的 10 秒

### Requirement: 系统内录的尽力尝试

应用 SHALL 提供"同时录制系统声音"的选项，通过 `MediaProjection` + `AudioPlaybackCapture`
尝试采集其他应用播放的声音（写入 `system.wav`）；当目标应用的音频用途不在可捕获范围（例如会议类应用使用的
`USAGE_VOICE_COMMUNICATION`）时，MUST 自动回落到仅麦克风，并明确告知用户"该应用不支持内录"。
麦克风轨道始终写入 `mic.wav`，结束时两路合成 `录音.wav`——与桌面版的音轨布局一致。

#### Scenario: 内录不可用的会议应用

- **WHEN** 用户尝试录制一场腾讯会议并勾选"同时录制系统声音"
- **THEN** 用户确认屏幕录制后，实际只录到麦克风；界面明确说明"没抓到会议软件的声音（对方多半禁止了内录）"，
  录音继续、不中断，并提示可改用外放让麦克风收音

#### Scenario: 内录可用的场景

- **WHEN** 用户录制本地播放的媒体（`USAGE_MEDIA`）
- **THEN** 产物包含系统声音与麦克风的混音

### Requirement: 录音文件的完整性

中断（进程被杀、电量耗尽）之后，已写入的音频 SHALL 可恢复为可播放的 wav；应用 MUST 在下次启动时
把未收尾的录音修复成可播放、可转写而不是丢弃。

#### Scenario: 录音过程中进程被杀

- **WHEN** 录音进行中进程被系统回收
- **THEN** 下次启动时应用自动补好 wav 头，该会议存在一条可播放、可转写的音轨（`录音.wav`，或在其生成前回落到的 `mic.wav` / `system.wav`），长度以实际写入为准

### Requirement: 会议库备份与恢复

应用 SHALL 支持把整个会议库打包成单个 zip 交给用户选择保存位置，并 SHALL 支持从这样的
备份恢复会议。恢复 MUST 拒绝任何试图写到会议库之外的条目（`..`、绝对路径、多余层级），
且默认 MUST NOT 覆盖已存在的同名会议。

注：本变更**不提供"修改会议库目录"**。SAF 只给 `content://` URI，而引擎按绝对路径读写，
把存储抽象加进引擎的代价远超收益；备份/恢复覆盖了"带走、迁移、找回"的实际用途。
（见 design.md 的偏差记录。）

#### Scenario: 换手机带走会议

- **WHEN** 用户在设置页点"备份到文件"，选一个位置保存
- **THEN** 得到一个包含所有会议目录及其文件的 zip，界面告知备份了几场会议

#### Scenario: 从备份恢复

- **WHEN** 用户选一个备份文件恢复
- **THEN** 新会议出现在列表里；已存在的同名会议被跳过而不是被覆盖，界面说明恢复/跳过/拒绝的数量

#### Scenario: 选错文件或恶意 zip

- **WHEN** 用户选了一个普通文件，或 zip 里含 `../` 这类条目
- **THEN** 应用报错（普通文件）或拒绝该条目（危险路径），绝不把文件写到会议库之外
