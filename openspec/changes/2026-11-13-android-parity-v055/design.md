# Design

## 实时说话人重聚类

新增纯 Kotlin `LiveSpeakerReclusterer`，保存当前录音已识别句子的文本、声纹和临时编号。每新增若干句后对全部有效声纹调用现有 `SpeakerClustering`，按首次出现顺序重新编号，再通过 `SnapshotStateList` 替换可见条目。录音线程只入队，重聚类不阻塞录音；声纹数量和采样规模受现有聚类上限约束。

## 视觉鱼骨图

详情页在现有证据摘要前增加 `FishboneDiagram`：问题节点位于顶部，分支按纵向骨架排列，每个分支显示标题、说明和证据等级颜色。点击分支仍展开原话，点击时间戳仍调用现有播放器回调。宽屏使用横向排列，手机使用纵向排列。

## 搜索、术语与标签

`LibraryViewModel` 增加 `selectedTag`，渲染时同时应用搜索词、归档状态和标签筛选。标签来源取当前会议快照中的去重标签。已有引擎搜索继续负责跨会议正文、纪要和术语扩展；不在 Kotlin 重新实现全文搜索。

## TDD seams

1. `LiveSpeakerReclusterer`：声纹加入、重聚类、编号稳定和空声纹。
2. `FishboneDiagram`：问题、分支、证据状态和点击语义。
3. `LibraryViewModel`：标签筛选和搜索状态组合。
4. 现有批量操作和术语搜索测试作为回归护栏。
