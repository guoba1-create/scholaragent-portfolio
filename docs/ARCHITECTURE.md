# 架构与数据流

## 一次任务的生命周期

1. Web 或 Telegram 提交问题，Java 解析用户、知识空间和会话上下文。
2. 路由层产生业务意图与执行策略，执行目录决定 Workflow 模板或 Agent Profile。
3. 统一召回短期上下文、语义记忆和当前知识空间信息，按上下文预算编译输入。
4. 快速问答进入 Java 检索回答链；Workflow 和 DeepResearch 经 gRPC 分发到 Python。
5. Python 节点调用 Java 工具服务，检索和文档访问沿用任务身份范围。
6. Python 回传执行事件与结果，Java 聚合 Trace、证据和 Snapshot，SSE 更新工作台。

## 文献与检索

文档上传后由 GROBID 解析论文结构，生成章节及片段信息，过滤参考文献。文档结构与元数据负责目录和片段组织，Pinecone 负责稠密与稀疏检索。检索结果经过融合和精排后，转换为结构化证据。

`documentId` 标识文档，`chunkId` 标识片段。`citationId` 是当前任务中用于回答引用的别名。证据对象将这些字段与标题、章节、文本和分数放在一起，回答引用可定位到具体片段。

## 固定证据工作流

| 模板 | 主要阶段 |
| --- | --- |
| 观点核验 | 问题预处理、观点拆解、检索、评估、补查、支持度判断、生成 |
| 相关工作 | 问题预处理、文献检索、评估、补查、综述生成 |
| 论文比较 | 问题预处理、比较检索、评估、补查、对比生成 |
| 引用支持 | 问题预处理、观点抽取、引用检索、评估、补查、引用建议 |

节点拓扑由代码定义。证据不足且预算允许时，补查节点生成检索词并返回对应检索节点。实现见 `python-runtime/src/langgraph_runtime/workflow_graph.py`。

## DeepResearch 与协作

DeepResearch Coordinator 负责计划、执行本地研究、评价证据覆盖度、调整研究步骤并生成报告。External Scholar Scout 接收有边界的外部检索任务，返回论文或网页来源。父任务聚合本地与外部证据，继续评估并生成最终报告。

Profile 定义角色、工具集合、handoff 目标、终止条件和资源上限。Java 与 Python 使用执行目录版本校验，确保能力定义匹配。Profile 见 `python-runtime/src/langgraph_runtime/profiles.py`。

## 状态与事件

| 对象 | 用途 |
| --- | --- |
| LangGraph State | 节点之间读取、更新任务状态 |
| Checkpoint | 保存图执行状态与恢复位置 |
| Event | 传递节点运行过程与阶段变化 |
| Trace | 保存调用输入、输出、状态与耗时 |
| Snapshot | 聚合任务状态、最终答案、证据和诊断 |

gRPC 服务承载 Java/Python 在线任务与事件流。Kafka 配置用于异步文档、通知等后台链路，相关消费者与主题定义见源码。
