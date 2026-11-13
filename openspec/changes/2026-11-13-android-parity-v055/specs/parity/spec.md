# Android parity Specification

## Requirements

### Requirement: 全场说话人重聚类

录音过程中应用 SHALL 保存已有实时句子的声纹，并周期性重新聚类；重新聚类后 MUST 更新可见临时编号，且不阻塞录音线程。录音结束后的正式转写仍是最终结果。

### Requirement: 视觉鱼骨图

详情页 SHALL 在原因分析区显示问题、分支和证据等级。分支 MUST 支持展开原话，时间戳 MUST 能跳回录音。

### Requirement: 跨会议搜索与术语

会议库 SHALL 支持搜索纪要和转写正文，并显示术语扩展后的搜索范围；搜索命中 MUST 能打开会议并跳到对应时间戳。设置页 SHALL 支持查看和维护跨会议术语/热词。

### Requirement: 标签、归档与批量操作

会议库 SHALL 支持按标签筛选，并保留归档、回收站、多选、批量归档、批量标签和批量删除。筛选状态 MUST 与搜索和归档状态同时生效。
