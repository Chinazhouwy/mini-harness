# Spring AI / Spring AI Alibaba / AgentScope Java 系统学习计划

更新日期：2026-11-13。本文是当前学习主线的统一入口，供用户与后续模型接续工作。用户后续明确的调整优先于本文；调整时同步更新本文，不另建一份互相冲突的路线。

阅读顺序：第 1 节看学习目的，第 3 节看课程顺序、实验与比较方法，第 7 节看官方资料和版本，第 8 节看三个框架的 API / 原理表，第 9 节检查功能覆盖。2026-11-13 用户明确：这是对三个框架的系统性学习，现有功能、底层原理和三者区别都需要掌握；不能缩减为只选一个框架做项目。

## 1. 目的与边界

**最终目的：系统掌握 Spring AI、Spring AI Alibaba、AgentScope Java 的主要功能、核心 API、底层执行原理与设计差异；能够使用三者的代表性能力，沿源码解释行为，并根据任务作出有依据的设计与选型。**

MiniHarness 是学习、实验和综合实践的载体。Spring AI 是当前已有代码的起点，三个框架都是正式学习对象。按“同一个能力主题 → 三者官方用法 → 关键源码 → 同题实验 → 差异总结”推进；先用现成框架能力，再用必要的手写小实验解释机制。综合项目可最终选择一个框架，但这个选择不缩减其他框架的学习范围。

| 项目 | 约定 | 防止的偏离 |
| --- | --- | --- |
| 学习方式 | 每次一个问题、一段核心代码、一次可观察验证；用户参与关键代码与设计，模型解释、审查、排错 | 模型一次写完整框架，用户只能照着看 |
| 应用方向 | 以一组统一小案例覆盖功能；个人任务 Bot 保留为综合应用候选，选型不阻塞系统学习 | 用单一应用暂时不需要某功能作为跳过课程的理由 |
| 当前练习 | 查找学习目录中的 Java 文件，用于验证工具闭环 | 把临时练习场景误当最终产品需求 |
| 框架使用 | 三者都学习并保留隔离的最小实验；第 5 阶段比较执行机制，第 10 阶段做综合选型 | 提前选一个框架后，将另外两套降为只读介绍 |
| 并发学习 | 用同一小案例理解线程池、Future 和 Reactor；项目只保留所需方案 | 为学习所有 Reactor API 而中断主线 |
| 记忆范围 | 会话上下文、摘要、长期信息、任务事实分别建模 | 把聊天历史或摘要当作唯一任务状态 |
| 测试方式 | 真实 demo 为主要学习验证；关键确定性规则补少量自动化测试 | 为简单练习搭建大批 mock，或只凭模型回答就宣布机制可靠 |
| 协作范围 | 工作流与多 Agent 是必学专题，必须有小型协作实验；最终应用是否使用另行决定 | 将“不在产品里采用”混同“不需要学习和验证” |
| 本次文档工作 | 固定路线、边界与进度；不自动开始后续实现 | 把用户审阅计划理解成允许一次实现所有阶段 |

## 2. 当前基线：已有代码不等于已完成阶段

以下区分本次源码检查与 README 记载的历史验证；本次只整理文档，没有重新运行模型或测试。

| 内容 | 当前证据与限制 | 后续处理 |
| --- | --- | --- |
| `HarnessCore` | 当前源码封装 `ChatClient`，包含同步和流式分支；模型在类内初始化 | 保留作现成调用路径的对照，阶段 3 再按需要整理职责 |
| `HarnessCoreDemo` | 存在真实聊天与 Glob demo；README 记载已跑通，非本次复验 | 需要运行时显式选择 demo，不把它当手写循环验证 |
| `ManualSpringAiLoopTest` | 已有固定响应模型、同步循环、工具执行、消息回填和轮数限制；只处理示例任务工具，明确拒绝 `returnDirect` | 在此基础上改造接入真实模型与 Glob，不重新从零写同类练习 |
| 通用工具 | 已引入 AgentUtils；README 记载 Glob 的离线接入验证 | 搜索、网页抓取等按实际需求引入，不能宣称整套工具都已验证 |
| 混合依赖 | README 记录了 Spring AI 与 AgentScope 的 victools 冲突；隔离学习模块尚未完成 | 阶段 2 建立隔离环境，后续各主题在各自依赖环境中验证 |
| 旧 Harness 文档 | 本次 `src/main/java` 仅见 `HarnessCore`、`ModelConfig`、`ToolCofig`、`MiniHarnessApplication` 四个类；README 后半的旧任务图、事件与恢复描述不代表当前实现 | 需要复用时先查实际分支和文件，不能据旧说明跳过阶段 8 |
| 已有学习材料 | 保留 Reactor、Spring AI、AgentScope 练习和复盘文章 | 按当前问题引用，不要求重新完整学习一遍 |

## 3. 主计划表

状态使用：未开始、进行中、待用户决策、待验证、已完成。具体能力另可标记“无直接对应，已解释边界”或“扩展专题待深入”。核心课程不能因产品暂时不用而标记为不实施；受环境限制的实验保留待验证。阶段号沿用原编号，但本表按用户确认的系统学习目标重排内容。

| 阶段 | 目的 / 用户要掌握什么 | 按顺序执行的工作 | 最小交付物 | 本阶段边界 | 完成标准 | 当前状态 |
| --- | --- | --- | --- | --- | --- | --- |
| **1. 从 HarnessCore 观察工具循环** | 看清模型、工具、消息和循环控制各自的职责 | 从现有 `HarnessCore` 和 Glob demo 出发 → 观察工具请求、执行与结果回填 → 再与已有手写循环对照 | 一次真实工具调用的脱敏过程记录；手写循环接真实模型作为后续对照 | 沿用已有流式 demo；暂不扩展并发和持久化；不重复实现 Glob | 至少观察到一次真实工具请求与执行、随后模型读取工具结果并回答；用户能沿日志解释流程；无工具调用的回答不算工具闭环通过 | **进行中：已按本地源码追踪入口，待增加观察并实测** |
| **2. 三框架全景与实验环境** | 知道三者各层提供什么、哪些是核心或扩展，建立公平对照 | 官方目录建功能地图 → 锁定各自版本并隔离依赖 → 各跑无工具/单工具最小例子 → 定义统一案例集 | 功能地图、三个实验入口、版本清单、正常/失败/暂停案例 | 不要求先选最终产品或主框架；无直接对应能力明确标注，不强行等同 | 三者最小调用均可观察；能指出模型、循环、工具执行归属；环境缺失记录待验证 | 未开始 |
| **3. 模型、消息、工具与输出契约** | 会用三者核心 API，理解适配与数据流 | 对比模型选项/提供商适配 → 消息与 Prompt → 工具 Schema/注册/绑定 → 结构化输出 → 最小职责整理 | 三套对应小例子、消息结构与参数传递对照、一个结构化业务结果 | 先追一次调用涉及的源码；不遍历全部提供商 SDK；不提前搭通用框架 | 能解释 Schema 如何生成、参数如何进入方法、结果如何回模型；三者结构化结果均有业务校验 | 未开始 |
| **4. 扩展点与执行边界** | 会用 Advisor / Hook / Interceptor / Middleware，并理解错误路径 | 各写日志或请求修改扩展 → 跟踪调用顺序 → 调用预算 → 参数/工具错误 → 重试、超时、权限与取消预习 | 三者扩展点实验、异常与超限案例、关键调用链图 | 固定响应可验证规则；写操作使用受控实验；不能把异常统一吞成成功 | 用户能定位“在哪一层改什么”；能说明运行上限、错误信号与返回给模型的错误值差异 | 未开始 |
| **5. 运行时、并发与流式** | 理解线程池、Reactor、Graph 调度与各框架循环的实际关系 | 串行/线程池/Reactor 同题实验 → 三者批次工具执行 → 流式事件聚合 → 背压、取消和资源关闭 → 关键源码对照 | 线程/时序图、耗时及失败对照、各自事件日志 | 不在此终止其他框架学习；保留三套最小实验，不维护三套完整业务产品；不靠返回类型猜线程行为 | 正确关联工具结果；解释开始/完成/输出顺序、并发安全、取消传播及底层仍运行的情况 | 未开始 |
| **6. 会话、状态与 MCP** | 区分模型上下文、运行时上下文、会话状态；掌握远程工具接入 | 两轮交流/会话隔离 → 三者状态归属 → 工具消息配对 → 本地 MCP 服务及三个客户端适配实验 | 状态映射表、会话隔离 demo、同一个 MCP 工具的接入记录 | 传输先选一种；无直接适配使用官方扩展或注明组合方式；不默认部署远程服务 | 会话不串话；能说明哪些数据进入模型；远程工具发现、执行、失败与关闭均可观察 | 未开始 |
| **7. 上下文工程、记忆、RAG 与 Skills** | 系统区分并使用窗口、摘要、长期记忆、检索增强与技能加载 | 分小课依次做长工具输出处理 → 摘要 → 长期记忆写入/纠错 → 小语料 RAG → 单个 Skill 渐进加载；逐项三者对照 | 压缩前后记录、跨会话记忆、检索与来源 demo、Skill demo、能力归属表 | 这是多次结对专题；底层公共组件可复用；不为缺少内置能力重写整个系统 | 每个子题都有运行证据和差异说明；用户能说明各机制如何取舍、如何避免信息丢失或混入错误事实 | 未开始 |
| **8. Harness、权限与持久化恢复** | 理解任务生命周期、检查点、工作区与人工介入 | 最小任务状态 → 各框架现成机制/应用层组合 → 同意/拒绝/等待 → 持久化 → 重启继续 → 幂等与模糊状态核对；阅读工作区/沙箱/Plan Mode 路径 | 暂停恢复与权限实验、持久化边界图、工作区和运行约束记录 | 不默认移植旧框架；说明内存/持久存储区别；不承诺任意代码位置续跑或外部动作恰好一次 | 能解释各层保存什么；批准前零副作用；重启能恢复既定案例；无内置对应时明确应用层责任 | 未开始 |
| **9. 工作流、多 Agent 与完成验证** | 会用主要协作模式，解释状态/上下文/控制权传递 | 固定顺序与并行流程 → 路由/主管模式 → Agent-as-tool/子 Agent → 委派失败与预算 → 完成验证；对照三者能力归属 | 顺序/并行/动态委派最小案例、三者实现与缺口表、同题收益与成本记录 | 学习实验必做；最终应用是否用协作另决定；不把工具并发当多 Agent，不无限递归 | 能解释各模式的调度者、状态所有者、结果返回与失败归属，并据证据判断任务完成 | 未开始 |
| **10. 综合比较、评估与项目实践** | 汇总功能、源码机制和设计取舍，能够有依据地选型 | 同案例检查功能/失败/成本 → 校准三者比较表 → 复盘代表性源码链 → 选综合场景和实现路线 → 整理可复现入口 | 三框架学习档案、比较报告、综合 demo、后续专题清单 | 选型不撤销其他框架课程；可复用此前实验；部署/UI不默认加入 | 三者主要功能都有学习记录，核心机制有源码和运行证据；用户能解释区别并为选型提供条件与依据 | 未开始 |

依赖顺序：1 → 2 → 3 → 4 → 5 → 6 → 7 → 8 → 9 → 10。阶段不是一次课，尤其阶段 7、8、9 要拆为多次结对。每次只推进一个可运行的小目标，按主题交替学习三者；不等某一套框架全部学完再从头学第二套。调整顺序时同步更新本表。

**阶段验收同时检查第 8、9 节对应条目。** 模型、工具、扩展点、执行并发、会话、压缩/长期记忆、MCP、RAG、Skills、恢复和协作都属于必学专题，均需要代表性实验。三者相同能力用同题对照；无直接对应时记录原生能力、官方扩展、社区扩展或应用层组合，不能编造一一对应 API。

### 3.1 每个专题的固定学习流程

| 顺序 | 学什么 / 做什么 | 留下的证据 |
| --- | --- | --- |
| 1. 功能 | 解决什么问题，提供哪些能力和配置，属于核心还是扩展 | 官方文档入口、版本、功能边界 |
| 2. 用法 | 三者各写最小调用；同输入与验收目标，保留各框架习惯用法 | 可独立运行的示例和配置说明 |
| 3. 原理 | 从公共 API 追关键调用链：构造、状态读写、调度、结束和异常 | 关键类/方法、源码位置、调用或时序图 |
| 4. 实验 | 改一个变量：参数、并发度、异常、上下文长度或中断点 | 预期行为、实际日志、差异解释；固定响应与真实模型分开 |
| 5. 比较 | 三者接口、机制、状态和代价有何异同，为什么这样设计 | 使用下面的统一比较维度，不写无条件优劣结论 |
| 6. 复述 | 用户解释或修改一个关键点；模型针对疑问纠正与补充 | 已掌握、待澄清、下一小步；不替用户宣布掌握 |

### 3.2 三者比较的固定维度

| 比较维度 | 每个专题都要回答的问题 |
| --- | --- |
| 能力归属 | 原生内置、官方扩展、社区扩展、应用组合，还是当前版本无对应？ |
| API 与配置 | 调用入口、返回类型、配置作用域与覆盖规则有什么区别？ |
| 控制流 | 谁负责循环、路由、终止，扩展点位于哪一层？ |
| 执行模型 | 同步/异步、订阅时机、线程池、最大并发、结果顺序分别是什么？ |
| 状态与上下文 | 状态由谁拥有、何时进入模型、何时持久化、如何隔离和恢复？ |
| 失败与约束 | 超时、取消、错误回填、重试、权限、预算分别如何处理？ |
| 工程与代价 | 需要多少接入代码，依赖/资源/模型调用成本是什么，是否便于观察和测试？ |
| 适用条件 | 在什么约束下适合此设计，代价是什么，证据是源码还是实测？ |

学习记录按“专题 × 框架”分别维护文档阅读、API 练习、源码理解、运行验证和差异总结。一个框架实验通过不能将另两个一起标为完成；能力不存在也要用资料/源码说明边界，而非以猜测填空。

## 4. 当前结对起点：从 HarnessCore 往下走

| 小步 | 具体动作 | 关注点与验收 |
| --- | --- | --- |
| 1.1 | 以 `HarnessCore` 和现有 Glob demo 为基础，追踪 `ChatClient` 中自动工具循环的入口 | 区分 `ChatClient`、`ToolCallingAdvisor`、`ToolCallingManager` 的职责；不重复教授已有示例 |
| 1.2 | 选择适合当前版本的扩展点，让 demo 能观察工具请求、执行及结果 | 确认事件确实来自工具执行；记录调用 ID、名称和安全的摘要，不记录密钥 |
| 1.3 | 在需要时改善 `HarnessCore` 中模型创建和注入的职责 | 用户参与设计与代码；保持现有真实 demo 可运行，不提前搭复杂框架 |
| 1.4 | 与 `ManualSpringAiLoopTest` 对照；按学习需要再接真实模型 | 说明框架自动循环和手动循环的控制边界；手写真实循环不是继续学习的前置条件 |

真实工具调用验收保留运行时间、调用入口、实际工具执行证据与未验证范围。README 记载原 demo 已运行，本次没有复跑；之前答案中仅凭最终文件名不能证明调用链细节均已观察。

## 5. 后续模型的工作约定

| 情况 | 应当怎么做 | 不应怎么做 |
| --- | --- | --- |
| 接手本项目 | 先读本文、README 学习现状和当前改动；核对当前阶段对应的代码与验证记录 | 依据旧聊天或旧 README 宣布整个 Harness 已存在 |
| 用户说“下一个” | 从当前阶段第一个未完成小目标继续，先用一句话说明目标 | 自行切换框架或开启新项目方向 |
| 用户提出旁支问题 | 先回答，并说明对应哪一阶段；问题处理完回到主线 | 每个问题都变成新的长期学习路线 |
| 用户正在写代码 | 优先读当前写法，解释具体问题，保留其参与空间 | 直接替换成一整套“最佳实践”代码 |
| 用户明确要求实现、修复或代写 | 按请求完成相应工作并解释，不额外强制用户手写 | 用“教学需要”拖延用户明确要求的实现 |
| 完成某一步 | 分别记录代码实现、实际运行、用户理解反馈；只有证据支持才更新状态 | 因为代码编译或模型回答看起来正确，就假定用户掌握或机制全部可靠 |
| 需要测试 | 选择本阶段相关测试或 demo；真实与固定响应分开记录 | 无差别运行所有依赖网络/凭据的学习测试，或编造通过结果 |
| 需要新依赖或框架 | 查当前版本和拥有实现的源码/官方资料；对照环境隔离，记录实际验证 | 把网上旧版本行为套到当前环境，强行覆盖公共依赖版本 |
| 场景或主框架需决策 | 提供具体选项、证据和推荐，等用户决定后再做依赖该选择的工作 | 把候选想法当已确认需求；例行小改动则不反复索要许可 |
| 任务完成交接 | 写清本次做到哪里、验证结果、剩余问题、下一小步 | 只写“已完成”或给出一个没有落点的大计划 |
| Git 与凭据 | 保留用户改动；提交、推送遵循当次授权；本地模型配置不进入版本库 | 将一次历史提交请求当成永久提交/推送授权；在日志或文档记录密钥 |

## 6. 决策与进度记录

| 决策项 | 当前结论 | 何时再次决定 |
| --- | --- | --- |
| 学习对象 | 三者都是正式对象，功能、原理、区别都必学；沿用现有 Spring AI 代码起步 | 不因综合应用选型而缩减其他框架学习 |
| 最终应用 | 未确定；个人任务 Bot 为候选；统一小案例先支持系统学习 | 阶段 10 确定综合项目，用户可提前明确 |
| 并发方案 | 线程池、Future、Reactor 和框架调度均做代表性实验 | 阶段 5 比较机制；综合应用再选所需方案 |
| 长期记忆 / 向量库 | 长期记忆与 RAG 必学并有最小实验，存储后端选一个代表即可 | 阶段 7；不要求试遍数据库 |
| 多 Agent | 工作流与多 Agent 必学，必须有最小协作实验 | 阶段 9；最终应用采用与否另行判断 |
| 服务部署 / UI / 定时调度 | 不属于当前必做路线 | 用户提出具体需求后另定范围 |

| 日期 | 阶段 / 动作 | 实际证据 | 未完成与下一步 |
| --- | --- | --- | --- |
| 2026-11-13 | 固定学习路线，检查当前源码，添加本文与 README 入口 | 已阅读 `HarnessCore`、`ManualSpringAiLoopTest`、`HarnessCoreDemo`；本次未改 Java、未运行模型或测试 | 下一步 1.1：共同读现有循环，随后最小改造接真实模型与 Glob |
| 2026-11-13 | 按用户要求补充三个框架的官方 API、原理与覆盖范围 | 检索并阅读官方参考文档；检查本地 Spring AI 2.0.2 source JAR 中的工具接口；核对 Alibaba v1.1.2.0 POM 与 AgentScope v2.0.4 部分源码；未做三框架运行验证 | 保持阶段 1 未开始；实际使用前核对 API 签名、依赖版本和文档冲突 |
| 2026-11-13 | 用户明确系统学习目标，修正项目选型导向 | 三框架功能、底层原理和区别都纳入正式学习；环境隔离提前到阶段 2，选型总结后移到阶段 10；MCP/RAG/Skills/长期记忆/协作增加必做代表实验 | 仅更新计划，未新增 Java 实现或运行验证；下一小步仍是 1.1 |
| 2026-11-14 | 开始阶段 1.1，阅读现有手写循环 | 查看 `runLoop` 和固定响应示例；本次未运行测试或真实模型 | 用户理解情况待交流；接下来最小改造以接受输入与通用工具 |
| 2026-11-14 | 用户纠正学习起点：已写到 `HarnessCore` | 当前 `HarnessCore` 使用 `ChatClient` 注册工具；README 记录了真实聊天和 Glob demo；固定响应手写循环已存在 | 以 `HarnessCore` 为主线，下一步观察其工具调用过程；手写真实循环作为后续对照，不再作为前置条件。本次未复跑 demo |
| 2026-11-14 | 追踪当前版本的工具调用切入点 | 阅读本地 Spring AI 2.0.2 源码：`ChatClient` 自动注册 `ToolCallingAdvisor`；流式工具执行委托给 `ToolCallingManager` 并调度到 boundedElastic；默认 manager 逐项调用工具 | 下一步在现有真实 demo 中装饰 manager 记录请求与结果，再观察下一轮模型输入；尚未增加代码或实测 |
| 2026-11-14 | 接入流式请求、响应片段和工具执行日志 | `test-compile` 通过；真实模型 demo 观察到两轮请求、一次 `find_files` 调用及工具结果、流式响应片段 | 日志仍是学习用途的原始对象输出；下一步逐项整理工具定义、消息和片段，并继续区分流式与同步路径 |

每次推进后更新主表状态并追加本表。若只进行了讲解，不把实现标为完成；若只完成实现但尚未运行，标为待验证。用户理解程度以交流反馈为依据，不由模型代为宣布。**当前以 `HarnessCore` 为主线，先观察其真实工具调用；按第 4 节的更新顺序推进。**

## 7. 官方依据、版本和掌握程度

### 7.1 本次核对的范围

初版路线基于项目代码与结对目标整理，不是逐章照抄官方教程。本次补充核对 Spring AI、Spring AI Alibaba、AgentScope Java 官方资料中的模型、工具、扩展点、上下文、执行、状态和协作章节，并对部分版本敏感接口检查源码。下面是本项目范围内的能力清单，不声称覆盖三个框架的全部 API，也不代表相关功能已经运行通过。

| 框架 / 组件 | 当前项目与资料基线 | 学习侧重 | 官方入口与版本依据 |
| --- | --- | --- | --- |
| Spring AI | 根 POM 为 **2.0.2**；本次官方稳定参考页也标为 2.0.2；工具接口另核对本地同版本 source JAR | 模型/消息/工具底层契约、ChatClient、Advisor 组合 | [官方参考][S0]；[2.0.2 固定源码][S-source] |
| Spring AI Alibaba | 根 POM **未引入**；对照先以官方快速开始的 **1.1.2.0 Agent Framework** 为阅读基线；其固定 POM 使用 Spring AI 1.1.2、Boot 3.5.8 | Graph 状态运行、Hook / Interceptor、检查点、工作流与多 Agent | [快速开始][B0]；[版本说明][B-version]；[固定版本 POM][B-pom] |
| AgentScope Java | 根 POM 为本机 **2.1.0-SNAPSHOT**，不是稳定版本证明；官方 v2 入口标示 v2.0.4，本次另读取 **v2.0.4** 的 ToolkitConfig / HarnessAgent 源码 | ReActAgent / HarnessAgent 分层、工具调度、Middleware、记忆与恢复 | [官方 v2 文档][C0]；[文档索引][C-index]；[固定 ToolkitConfig][C-tool-source]；[固定 HarnessAgent][C-harness-source] |
| Spring AI Community AgentUtils | 已引入 **0.13.0**；属于社区扩展，不是 Spring AI 核心自带工具集合 | 复用文件、搜索、网页、Skills 和记忆能力，减少重复实现 | [固定版本工具文档][U0] |

三者的重点是本计划根据官方架构做出的教学分工，不意味着只能用于这些场景。尤其 Alibaba 的 ReactAgent 本身运行于 Graph 之上，不能把它与 Spring AI 原生 ChatClient 当作同一层的 API。[架构说明][B-overview]

### 7.2 已发现的版本陷阱

| 问题 | 已核对事实 | 后续执行规则 |
| --- | --- | --- |
| Spring AI 1.x 与 2.0 工具循环 | 当前 2.0 文档把循环放在 ChatClient 的 `ToolCallingAdvisor`；直接 `ChatModel` 调用不会替你执行工具循环。[工具调用][S-tools] | 讲解时标注 2.0.2；不把旧教程中的模型内循环、旧选项直接套过来 |
| Spring AI 搜索混入 2.1 | 搜索结果会进入 2.1 的 session memory 文档；本项目仍是 2.0.2。[2.1 Advisor 页][S-21] | 本路线先用 2.0.2 `ChatMemory` API；引入新 session API 属于升级决策，不能静默替换 |
| Alibaba 与当前 Spring AI 版本不同 | 固定 v1.1.2.0 POM 的依赖基线与根项目不同。[POM][B-pom] | 独立模块或独立 demo 工程验证，不能把最新网页示例直接粘到根 POM 下 |
| AgentScope 文档内部不一致 | 本次读取的 [Compaction 页][C-compaction]称相关能力默认关闭，而 [Memory 页][C-memory]称默认开启；v2.0.4 `HarnessAgent.Builder` 源码确有默认 CompactionConfig / ToolResultEvictionConfig | 不把任一网页的默认值当作当前 SNAPSHOT 的已验证行为；实施时查实际 JAR/对应提交并运行小例子 |
| AgentScope stable / dev / 本机源码混用 | v2 文档入口、v2.0.4 固定源码和 2.1.0-SNAPSHOT 不是同一个版本标识 | 表内类名是学习定位入口；签名、默认值和能力有差异时以实际依赖为准；旧 `Hook` 示例不能自动等同 v2 Middleware |
| 官方示例不等于本项目实现 | 内存存储、示例摘要 Hook、示例权限策略各有适用范围 | 区分框架内置类与教程自定义类；`MemorySaver` / `MemoryStore` 名称不能证明能跨进程恢复 |

### 7.3 学到什么程度

| 等级 | 要求 | 验收方式 |
| --- | --- | --- |
| **会用、会写** | 三者对应能力都能写最小例子、修改关键配置和定位常见错误；无原生对应时明确组合方式 | 运行 demo 并解释输入、状态和结果；共享基础组件无需重复实现 |
| **懂原理、能比较** | 能沿关键源码说明控制流、状态归属、调度、异常和设计取舍 | 调用图/时序图、源码位置、改变量实验和三者差异表 |
| **扩展专题** | 了解完整功能地图；供应商/存储后端的同类变体及大规模部署不要求逐个实现 | 标记已了解或待深入；不能将核心能力挪入此类来回避实验 |

阶段 1 用已有 Spring AI 代码建立直觉；阶段 2 起按主题系统学习三者。“会写”与“懂原理、能比较”是核心专题的共同要求，不是三个框架之间的等级划分。保留三套最小实验是必要学习产物；不要求为每套框架维护完整业务产品。

## 8. 应掌握的 API 与原理表（三个框架）

本节列的是稳定定位用的类型、核心方法和关键配置，不要求背所有 builder 参数。每条都绑定阶段、原理、掌握程度和官方来源。`ReactAgent`（Alibaba）与 `ReActAgent`（AgentScope）的大小写不同；同名 `ToolContext`、`ChatModel`、`@Tool` 也必须检查所属包。

### 8.1 Spring AI

| 编号 / 阶段 | API / 配置入口 | 应掌握的原理 | 掌握程度与练习 | 官方依据 |
| --- | --- | --- | --- | --- |
| S1 · 1、3 | `ChatModel.call(Prompt)`、`StreamingChatModel.stream(Prompt)`、`ChatClient.prompt().call()/stream()`；`content()`、`chatResponse()` | 底层一次推理与上层工具循环的区别；只取文本会丢失工具/usage 等元信息 | 会写两种入口，追踪调用链，解释循环归属，并与 B1/C1 对照 | [Model][S-model]、[Client][S-client]、[工具循环][S-tools] |
| S2 · 1、3 | `Prompt`、`ChatOptions`、`ChatResponse`、`Generation`；`SystemMessage`、`UserMessage`、`AssistantMessage.ToolCall`、`ToolResponseMessage` | 角色、工具 JSON 参数、调用 ID 与结果配对、消息顺序；提示约束与代码约束的区别 | 会写消息构造与回填；跟踪模型输入，理解模板参数化与指令/数据边界 | [Model][S-model]、[Client][S-client]、[Tools][S-tools] |
| S3 · 1、4 | `@Tool`、`@ToolParam`、`ToolCallbacks.from`、`ToolCallback`、`ToolDefinition`、`FunctionToolCallback`、`ToolContext` | 工具描述与 JSON Schema → 参数绑定 → Java 执行 → 结果转换；运行时上下文不自动发送给模型 | 会写注解工具与函数工具，跟踪 Schema/绑定/调用/返回路径；做 returnDirect 小实验 | [Tools][S-tools] |
| S4 · 1、4、5 | `ToolCallingManager.resolveToolDefinitions/executeToolCalls`；`ToolExecutionResult.conversationHistory()/returnDirect()`；`ToolCallingChatOptions` | Manager 负责工具解析与执行，Options 携带工具及模型选项；两者都不等于完整 Agent；当前默认 Manager 同步逐个执行 | 会写手动执行与超限实验；追踪工具解析、循环内历史和计数；与其他框架执行层比较 | [Tools][S-tools]、[2.0.2 Manager 源码][S-manager]、[Options 源码][S-options] |
| S5 · 3、4、6 | `ToolCallingAdvisor`、`CallAdvisor`、`StreamAdvisor`、对应 Chain、`getOrder()`；`defaultAdvisors` | 责任链/洋葱模型；工具循环重新进入下游链；memory 放在循环内外会改变能看到的消息 | 会写最小日志 Advisor；追踪链顺序和重新进入位置，与 Hook/Middleware 比较 | [Advisors][S-advisor]、[ToolCallingAdvisor][S-loop] |
| S6 · 3、4 | `OpenAiChatModel` / `OpenAiChatOptions`；`DeepSeekChatModel` / `DeepSeekChatOptions`；`model`、`temperature`、`maxTokens`、`topP` 等 | 通用抽象、供应商扩展和网关兼容接口的边界；默认/单次配置合并；重试与模型自主再调用不同 | 会配当前提供商；沿适配代码比较通用/扩展参数、覆盖规则、重试与协议转换；不要求试遍提供商 | [OpenAI 适配][S-openai]、[DeepSeek 适配][S-deepseek] |
| S7 · 3、9 | `.entity(Class)`、`BeanOutputConverter`、`StructuredOutputConverter`；原生结构化输出与 schema 校验入口 | JSON 可解析、Schema 合法和业务正确是三件事；结构化输出与工具参数不是同一机制 | 会写结构化结果并验证非法输出；追踪格式生成、解析/校验位置，与 B8/C10 对照 | [结构化输出转换][S-output] |
| S8 · 6、7、8 | `ChatMemory`、`ChatMemoryRepository`、`MessageWindowChatMemory`、`MessageChatMemoryAdvisor`、`ChatMemory.CONVERSATION_ID`；`JdbcChatMemoryRepository` 按需 | 窗口策略与存储分开；当前可见上下文、完整历史、任务状态分开；窗口淘汰不等于摘要压缩 | 会写隔离、窗口与持久存储代表例；追踪读写与淘汰，比较三者状态模型 | [Chat Memory][S-memory]、[工具与 Memory][S-tools] |
| S9 · 4、5、10 | `Flux` 流式响应、取消信号；Micrometer `ObservationRegistry`；响应 usage、模型/工具耗时与 trace | 文本流、工具事件和完整结果不同；流式不自动等于并行或非阻塞；观察必须覆盖实际执行路径 | 会写流式/取消/观测实验；解释事件和线程时序；比较三者事件粒度与观测覆盖 | [Client][S-client]、[Observability][S-observation] |
| S10 · 6、7 | `McpSyncClient` / `McpAsyncClient`、`SyncMcpToolCallbackProvider`；`VectorStore`、`EmbeddingModel`、`QuestionAnswerAdvisor` / `RetrievalAugmentationAdvisor` | MCP 是能力发现/调用协议；RAG 是检索并注入上下文；两者都不能代替任务状态和恢复 | MCP 客户端与小语料 RAG 实验必做；追踪工具适配与检索注入路径，后端选一个代表 | [MCP][S-mcp]、[MCP Client][S-mcp-client]、[RAG][S-rag] |

#### 当前最常接触的工具配置（已对照 2.0.2 source JAR）

| 所属对象 | 需要认识的配置 / 方法 | 用途与边界 |
| --- | --- | --- |
| `DefaultToolCallingManager.Builder` | `toolCallbackResolver`、`resolutionFallbackEnabled` | 工具解析与回退；知道执行时如何找到真实回调，详细调整遇到动态工具需求再做 |
| 同上 | `toolExecutionExceptionProcessor`、`observationRegistry` | 异常如何暴露给模型或调用者、执行如何被观察；不是模型采样选项 |
| 同上 | `maxCallsPerTool`（含按名称设置）、`maxTotalToolCalls`、`excludeToolFromLimit`、`onLimitExceeded`；`unlimited*` 仅需认识 | 工具调用预算；与模型轮数、时间和 token 预算分别计算；不为省事取消限制 |
| `ToolCallingChatOptions.Builder` | `toolCallbacks`、`toolContext` | 提供回调和不自动进入 prompt 的运行时数据；要理解默认值与本次请求的合并/覆盖规则 |
| 同上，继承的模型选项 | `model`、`temperature`、`maxTokens`、`topP`、`topK`、`stopSequences`、`frequencyPenalty`、`presencePenalty` | 配置模型生成行为；具体提供商未必支持全部字段 |
| `ToolCallingAdvisor.Builder` | `toolCallingManager`、`toolExecutionEligibilityChecker`、`advisorOrder`、`conversationHistoryEnabled` / `disableInternalConversationHistory` | 配置循环与消息历史责任，避免和 Memory Advisor 双重维护；手写直接 `ChatModel` 循环不需要再套一层自动循环 |

源码依据：[Manager][S-manager]、[Options][S-options]、[Advisor][S-loop-source]。不把 1.x 的 `internalToolExecutionEnabled` / 旧工具名称配置当作当前 `ToolCallingChatOptions` 参数表。

### 8.2 Spring AI Alibaba

| 编号 / 阶段 | API / 配置入口 | 应掌握的原理 | 掌握程度与练习 | 官方依据 |
| --- | --- | --- | --- | --- |
| B1 · 2、3、5 | `ReactAgent.builder()`、`model`、`tools`、`systemPrompt` / `instruction`；`call`、`invoke`、`stream` | 基于 Spring AI 模型/工具接口，在 Graph Runtime 中执行 Agent 循环；调用入口不同，结果形态不同 | 会写最小 ReactAgent；阶段 2 起接入实验，追踪 Graph 内的模型/工具循环 | [Agents][B-agent] |
| B2 · 3、5、8 | `StateGraph`、`CompiledGraph`、`OverAllState`、`CompileConfig`、`RunnableConfig` | 图定义、编译和运行分开；节点返回状态更新，边控制路由；确定性工作流与模型决策循环的关系 | 会写含条件路由的小图，阅读定义→编译→执行关键链；比较图调度与 Agent 自主循环 | [Graph 指南][B-graph] |
| B3 · 5、6、8、9 | `KeyStrategyFactory`、`AppendStrategy`、`ReplaceStrategy`；`RunnableConfig.threadId` | 同一个状态键如何合并；并行分支覆盖冲突、消息追加/替换、会话身份与 Java 线程不同 | 会写两分支状态合并与会话隔离实验；沿更新策略源码解释覆盖/追加和冲突 | [Graph 指南][B-graph]、[Memory][B-memory] |
| B4 · 3、4、6 | `AgentHook`、`ModelHook`、`HookPosition`；`ModelInterceptor.interceptModel`、`ToolInterceptor.interceptToolCall` | 生命周期节点与调用包装不同；状态持久变更与仅修改本次模型输入不同；调用下游 handler 才会继续 | 会写日志 Hook 和一个 Interceptor；追踪生效位置、顺序与状态更新，和另两者对照 | [Hooks / Interceptors][B-hooks]、[上下文工程][B-context] |
| B5 · 4、5 | `ModelCallLimitHook`、`ToolRetryInterceptor`；Graph / 工具执行的异步扩展入口 | 调用上限、退避重试、错误回填；返回 Future/Flux 不证明实际并行；不能盲目重试写操作 | 会配置上限/重试并制造失败；从源码和日志确认异步、线程池与取消，不能按类型猜测 | [Hooks][B-hooks]、[Tools][B-tools] |
| B6 · 6、7、8 | `MemorySaver`、`RunnableConfig.threadId`；`SummarizationHook`；`MemoryStore`、`StoreItem` | 会话检查点、上下文摘要、跨会话存储各自的作用；内存实现不等于磁盘持久化 | 会做会话、摘要、跨会话存取与持久化代表实验；追踪状态路径，区分教程自定义 Hook 与内置类 | [短期记忆][B-memory]、[摘要 Hook][B-hooks]、[长期记忆][B-store] |
| B7 · 4、8 | `HumanInTheLoopHook`、`ToolConfig`、检查点和恢复调用 | 暂停等待决策、批准/拒绝后继续；审批结果应关联原工具请求；检查点不自动保证外部副作用幂等 | 必须做批准、拒绝、暂停恢复实验；追踪中断与恢复数据，使用受控写入替身验证副作用 | [Human-in-the-Loop][B-hitl] |
| B8 · 3、9 | `outputType`、`outputSchema`、`outputKey`；`OverAllState` 中的结构化结果 | 输出 Schema、业务结果和图状态键分开；结构正确不等于任务完成 | 会映射结构化业务类型并校验；跟踪生成策略与状态键，比较另外两者 | [Structured Output][B-output] |
| B9 · 5、9 | `SequentialAgent`、`ParallelAgent`、`LlmRoutingAgent`、`SupervisorAgent`、`LoopAgent`；`AgentTool.getFunctionToolCallback` | 固定流程、模型路由、主管循环、Agent-as-tool 的区别；父子结果传递与状态冲突 | 顺序、并行和动态委派均有小例子；追踪路由/聚合/失败，与 Agent-as-tool 和子 Agent 比较 | [Multi-agent][B-multi]、[Agent Tool][B-agent-tool] |
| B10 · 5、7、10 | `NodeOutput`、`StreamingOutput`、`OutputType`；`SkillRegistry`、`FileSystemSkillRegistry`、`SkillsAgentHook` | 节点事件和文本增量不同；Skills 先展示描述、按需加载正文；MCP/RAG 继续复用对应 Spring AI 生态能力 | 会消费节点事件并加载一个 Skill；MCP/RAG 复用兼容组件，验证接入点而不重复建设底层服务 | [Agents 流式输出][B-agent]、[Skills][B-skills] |

### 8.3 AgentScope Java

| 编号 / 阶段 | API / 配置入口 | 应掌握的原理 | 掌握程度与练习 | 官方依据 |
| --- | --- | --- | --- | --- |
| C1 · 2、3、5 | `Agent`、`ReActAgent`、`HarnessAgent`；`call`、`streamEvents`、`observe` | 推理/工具循环与外围 Harness 分层；`observe` 与主动推理不同；资源生命周期独立于会话生命周期 | 会写 ReActAgent 与最小 HarnessAgent；追踪二者分层，明确订阅、执行和资源关闭位置 | [Agent][C-agent]、[Harness 架构][C-architecture] |
| C2 · 1、3、5 | `Model`、`GenerateOptions`、`ExecutionConfig`、提供商 Model / Formatter；`Msg`、`ContentBlock`、`ToolUseBlock`、`ToolResultBlock` | 模型请求格式化、生成选项与执行策略分开；消息块表达工具调用和结果；提供商能力有差异 | 会写消息和工具结果；追踪 Formatter 与生成/执行选项传递，对照另外两者的数据结构 | [Model][C-model]、[Message & Event][C-message] |
| C3 · 1、4、5 | `Toolkit.registerTool`、`AgentTool` / `ToolBase`、AgentScope 的 `@Tool`；`ToolkitConfig.parallel/executorService/executionConfig` | 注册和 Schema 生成、参数校验、批次执行与结果关联；调度方式与工具自身并发安全都要检查 | 会注册工具并改并发配置；读取批次调度关键源码，观察线程/顺序和并发安全限制 | [Tool][C-tools]、[固定 ToolkitConfig][C-tool-source] |
| C4 · 3、4、6 | `MiddlewareBase`、`onAgent`、`onReasoning`、`onActing`、`onModelCall`、`onSystemPrompt` | 整次运行、单轮推理、工具执行和底层模型调用是不同拦截层；洋葱包装与 prompt 转换不同 | 会写日志/上下文 Middleware，追踪关键回调的嵌套和事件路径；对照另两者扩展点 | [Middleware][C-middleware] |
| C5 · 4、5、10 | `Mono<Msg>`、`Flux<AgentEvent>`、`streamEvents`、`RuntimeContext`；中断与资源关闭入口 | 订阅触发、阻塞桥接、线程切换、事件增量与完整消息；取消、外部工具暂停和失败不是同一状态 | 会处理事件、取消与关闭；用小实验验证订阅时机和线程切换；沿事件生成路径解释结果 | [Agent][C-agent]、[Message & Event][C-message] |
| C6 · 6、8 | `RuntimeContext`、`AgentState`、`AgentStateStore`、`JsonFileAgentStateStore`；用户/会话标识 | 本次执行上下文、持久状态和模型实际看到的上下文分开；同会话并发控制与跨会话隔离不同 | 会配置代表性状态存储，验证新实例/进程续接；追踪状态读写与会话执行隔离 | [Context & State][C-state]、[固定 Harness 源码][C-harness-source] |
| C7 · 7 | `CompactionConfig`、`CompactionMiddleware`、`ToolResultEvictionConfig`、`MemoryConfig` | 历史摘要、长工具结果外置、长期信息提取/整理、上下文溢出处理各有触发与成本 | 会配摘要、工具结果外置和长期记忆，做前后对照；追踪触发与写回，按 7.2 核对默认值 | [Compaction][C-compaction]、[Memory][C-memory] |
| C8 · 4、8 | `PermissionContextState`、`ToolBase.checkPermissions`；ALLOW / ASK / DENY；外部工具挂起与确认事件；`PlanModeTools` | 模型提出动作不等于拥有执行权限；确认绑定具体调用；只读计划模式靠运行时执行边界而非仅靠提示词 | 必须做批准/拒绝和最小 Plan Mode 实验；追踪检查与挂起恢复，验证拒绝时无副作用 | [Permissions][C-permission]、[Tool][C-tools]、[Plan Mode][C-plan] |
| C9 · 7、8、9 | `HarnessAgent.workspace`、`SubagentDeclaration`；`agent_spawn` / `agent_send` 工具；`AgentSkillRepository`、`FileSystemSkillRepository` | 工作区、会话和子 Agent 上下文隔离；委派任务与汇总结果；Skill 的发现与按需加载 | 会配置工作区、一个子 Agent 和一个 Skill；追踪上下文/结果/权限传递，比较图编排 | [Subagent][C-subagent]、[Skill][C-skill]、[Tool][C-tools] |
| C10 · 3、6、9 | 带结构化输出类型的 `call` 重载；`McpClientBuilder`、`McpClientWrapper`、`Toolkit.registerMcpClient` | 结构化输出仍需业务验证；MCP 把远程能力纳入工具系统，不替代 agent 协作协议 | 结构化输出与 MCP 接入都做最小实验；追踪输出校验和工具注册，正确释放客户端 | [Agent][C-agent]、[Tool / MCP][C-tools] |

## 9. 防遗漏检查：必学实验与扩展范围

下表补足主线容易忽略的内容。核心专题既要理解也要有代表实验，是否用于最终产品另行决定。框架缺少内置能力时，学习其组合方式或记录明确缺口；不能为凑三份代码强造框架原本没有的 API。各项目前尚未逐项验收，不因加入表格就视为用户已掌握。

| 主题 / 阶段 | 三个框架的对应入口 | 必须掌握的原理 / 最小验证 | 实验要求与深入边界 |
| --- | --- | --- | --- |
| ReAct 与循环 · 1、5 | S1/S4/S5；B1/B2；C1/C3 | 理解模型决策 → 工具执行 → 观察结果 → 继续/结束；工具循环可体现 ReAct 式控制流，不要求展示模型隐含思考过程；ReAct 与 Reactor 完全不同 | 日志验证外部可观察行为，不把模型的解释当真实执行证据 |
| 结构化输出 · 3、9 | S7、B8、C10 | Schema、Java 类型、解析失败、业务校验、最终完成判断 | 三者分别做最小输出例子并比较转换/约束机制；同一个业务校验规则可以复用 |
| 并发与 Reactor · 4、5 | `ExecutorService.submit`、`Future.get/cancel`、`CompletableFuture.supplyAsync/allOf/thenCombine`；`Mono.defer/fromCallable`、`Flux.flatMap/concatMap/mergeSequential`、`subscribeOn/publishOn`、`collectList`、`timeout`、`doFinally` | 惰性与立即执行、线程归属、阻塞与非阻塞、最大并发、顺序与结果 ID、错误隔离、背压和取消；`Flux.just(blockingCall())` 不会把已执行的阻塞操作变成异步 | 使用现有 Reactor 练习补一个同题对照；不把所有操作符都变成新课程。[Reactor 官方参考][R1]、[Java 并发包][J1] |
| 工具权限与上下文可信度 · 4、6、8 | S3/S5；B4/B7；C3/C8 | 工具允许列表、路径范围、用户身份注入、参数验证；工具结果/网页中的指令不能自动提升为系统指令；读与写的幂等/重试策略不同 | 必做受控的允许/拒绝/等待实验和不可信工具输出案例；读关键执行拦截代码，不部署完整权限平台 |
| 工具选择与渐进加载 · 5、7 | Spring AI 工具注册/解析；Alibaba 动态工具 Interceptor / Skills；AgentScope Tool Group / Skills | 工具描述质量、重复名称、Schema 开销、可见工具集合与实际执行权限不同；少发 Schema 不代表取消鉴权 | 必做一个工具集合变化/渐进加载小实验；对照三者入口和执行权限边界，不搭工具市场 |
| 通用文件/搜索/网页工具 · 1、2、5 | AgentUtils 的 `GlobTool`、`GrepTool`、`FileSystemTools`、`BraveWebSearchTool`、`SmartWebFetchTool`；Alibaba 复用兼容工具/MCP；AgentScope 工具与 MCP | 查文件名、搜索内容、网页检索、抓取正文是不同能力；服务凭据、目录/URL 范围、输出长度与来源证据要明确 | 先 Glob，再分别做文件内容查找、网页抓取与搜索代表例；缺少外部服务时保留待验证。区分核心与社区扩展。[工具文档][U0] |
| MCP · 6 | S10、B10、C10 | Client/Server、tools/resources/prompts、Schema、发现、传输、连接生命周期、调用失败；MCP 不等于多 Agent | 必做一个本地 MCP 服务和三个框架的接入实验；先一种传输，比较工具适配与资源关闭；其他传输/认证专题后续深入 |
| Skills · 7 | AgentUtils `SkillsTool`；Alibaba `SkillsAgentHook`；AgentScope `AgentSkillRepository` | 技能是可读取的指令和资源，不是工具方法本身；渐进加载与工具权限分开；执行脚本仍需工具能力 | 三者各接入/组合一个 Skill，观察描述→正文加载及工具执行；读关键加载路径，不做技能市场。[社区][U-skills]、[Alibaba][B-skills]、[AgentScope][C-skill] |
| RAG / 向量检索 · 7 | S10；Alibaba RAG 指南；AgentScope 可用工具接检索服务 | 文档切分、Embedding、检索、过滤、重排与来源归属；事实检索、对话记忆、摘要压缩不同 | 必做小语料检索与回答实验，对比三者的原生/扩展/工具组合入口；复用同一语料和后端，了解重排与混合检索再选代表深入。[RAG][S-rag] |
| 长期记忆 · 7 | Spring AI ChatMemory 外的业务存储或 AgentUtils `AutoMemoryToolsAdvisor`；Alibaba Store；AgentScope `MemoryConfig` | 保存、检索、纠错/删除、过期与用户隔离；模型提取的候选信息不自动升级为权威事实 | 必做跨会话保存/检索/纠错与隔离实验；比较已有机制或业务组合，不要求重写内置组件。[社区][U-memory]、[Alibaba][B-store]、[AgentScope][C-memory] |
| 恢复与人机交互 · 8 | Spring AI 外围任务状态；Alibaba Checkpoint/HITL；AgentScope StateStore/Harness | 请求运行结束、任务等待、任务完成不同；持久化边界、待处理工具结果、恢复输入与副作用核对 | 三者各按能力边界验证等待/恢复与状态持久化；没有内置任务恢复时标清应用组合；比较保存与重放路径，不声称恰好一次 |
| 协作与工作流 · 5、9 | Spring AI 工具/应用编排；Alibaba Graph/FlowAgent；AgentScope Subagent | 工具并行、Agent 委派、固定工作流、主管调度、handoff 的区别；上下文/权限/预算与失败归属 | 顺序、并行、动态委派的代表实验必做，并比较原生与应用组合方式；A2A/远程 Agent 列为扩展专题，先学接口与协议边界 |
| 可观察性与评估 · 4、10 | S9；Alibaba Hook/Interceptor/节点事件；AgentScope Middleware/AgentEvent | 记录轮次、工具 ID、耗时、token、错误与结束原因；系统正确性、任务完成率、回答质量和成本分别观察 | 固定响应验证机制，真实模型验证实际效果；少量案例回归，不以“LLM 评委说通过”替代业务证据 |
| 多模态 · 3，扩展专题 | Spring AI Media / UserMessage；Alibaba 复用模型能力；AgentScope ContentBlock | 文本、图片和附件的编码/大小限制，提供商支持与证据有效性不同 | 学习消息与提供商能力边界，阶段 3 补一个图片输入代表例；受模型能力限制记为待验证。语音/图像生成等列入功能全景，单独作为扩展专题 |
| 工作区/沙箱 · 8，部署扩展 · 10 | Boot 配置与依赖管理；Graph 持久化后端；AgentScope Workspace / Sandbox / Service | 对象生命周期、凭据、资源释放、并发会话与运行环境边界 | 工作区、隔离与资源生命周期纳入阶段 8；代表性沙箱机制读源码并安排小实验，缺环境保留待验证；分布式部署、UI与定时调度为扩展专题 |

## 10. 官方参考链接与后续更新要求

表中的来源链接分别指向框架官方参考资料、官方仓库或 AgentUtils 维护者文档。在线页面会更新；固定源码标签用来辅助校验，并不证明与当前本机 SNAPSHOT 完全相同。

后续模型每推进一个专题，应分别记录三者的：**API 编号 → 实际依赖版本 → 官方功能与关键源码链 → 最小运行结果 → 差异与取舍 → 用户尚未理解的问题**。系统学习覆盖主要功能与代表机制；同类后端和参数变体了解分类后按代表展开，不机械穷举。发现新能力先补功能地图和所属专题，不能因当前应用暂时不用而自动跳过。发现文档冲突时记录差异，以实际版本源码和可复现行为确认。

[S0]: https://docs.spring.io/spring-ai/reference/index.html
[S-source]: https://github.com/spring-projects/spring-ai/tree/v2.0.2
[S-model]: https://docs.spring.io/spring-ai/reference/api/chatmodel.html
[S-client]: https://docs.spring.io/spring-ai/reference/api/chatclient.html
[S-tools]: https://docs.spring.io/spring-ai/reference/api/tools.html
[S-manager]: https://github.com/spring-projects/spring-ai/blob/v2.0.2/spring-ai-model/src/main/java/org/springframework/ai/model/tool/DefaultToolCallingManager.java
[S-options]: https://github.com/spring-projects/spring-ai/blob/v2.0.2/spring-ai-model/src/main/java/org/springframework/ai/model/tool/ToolCallingChatOptions.java
[S-advisor]: https://docs.spring.io/spring-ai/reference/api/advisors.html
[S-loop]: https://docs.spring.io/spring-ai/reference/api/tools/tool-calling-advisor.html
[S-loop-source]: https://github.com/spring-projects/spring-ai/blob/v2.0.2/spring-ai-client-chat/src/main/java/org/springframework/ai/chat/client/advisor/ToolCallingAdvisor.java
[S-openai]: https://docs.spring.io/spring-ai/reference/api/chat/openai-chat.html
[S-deepseek]: https://docs.spring.io/spring-ai/reference/api/chat/deepseek-chat.html
[S-output]: https://docs.spring.io/spring-ai/reference/api/structured-output/converters.html
[S-memory]: https://docs.spring.io/spring-ai/reference/api/chat-memory.html
[S-observation]: https://docs.spring.io/spring-ai/reference/observability/index.html
[S-mcp]: https://docs.spring.io/spring-ai/reference/api/mcp/mcp-overview.html
[S-mcp-client]: https://docs.spring.io/spring-ai/reference/api/mcp/mcp-client-boot-starter-docs.html
[S-rag]: https://docs.spring.io/spring-ai/reference/api/retrieval-augmented-generation.html
[S-21]: https://docs.spring.io/spring-ai/reference/2.1/api/advisors.html
[B0]: https://java2ai.com/docs/quick-start/
[B-version]: https://java2ai.com/docs/versions/
[B-pom]: https://github.com/alibaba/spring-ai-alibaba/blob/v1.1.2.0/pom.xml
[B-overview]: https://java2ai.com/docs/overview/
[B-agent]: https://java2ai.com/docs/frameworks/agent-framework/tutorials/agents/
[B-graph]: https://java2ai.com/docs/frameworks/graph-core/quick-start/
[B-hooks]: https://java2ai.com/docs/frameworks/agent-framework/tutorials/hooks/
[B-tools]: https://java2ai.com/docs/frameworks/agent-framework/tutorials/tools/
[B-context]: https://java2ai.com/docs/frameworks/agent-framework/advanced/context-engineering/
[B-memory]: https://java2ai.com/docs/frameworks/agent-framework/tutorials/memory/
[B-store]: https://java2ai.com/docs/frameworks/agent-framework/advanced/memory/
[B-hitl]: https://java2ai.com/docs/frameworks/agent-framework/advanced/human-in-the-loop/
[B-output]: https://java2ai.com/docs/frameworks/agent-framework/tutorials/structured-output/
[B-multi]: https://java2ai.com/docs/frameworks/agent-framework/advanced/multi-agent/
[B-agent-tool]: https://java2ai.com/docs/frameworks/agent-framework/advanced/agent-tool/
[B-skills]: https://java2ai.com/docs/frameworks/agent-framework/tutorials/skills/
[C0]: https://java.agentscope.io/v2/en/intro
[C-index]: https://java.agentscope.io/llms.txt
[C-tool-source]: https://github.com/agentscope-ai/agentscope-java/blob/v2.0.4/agentscope-core/src/main/java/io/agentscope/core/tool/ToolkitConfig.java
[C-harness-source]: https://github.com/agentscope-ai/agentscope-java/blob/v2.0.4/agentscope-harness/src/main/java/io/agentscope/harness/agent/HarnessAgent.java
[C-agent]: https://java.agentscope.io/v2/en/docs/building-blocks/agent.md
[C-architecture]: https://java.agentscope.io/v2/en/docs/harness/architecture.md
[C-model]: https://java.agentscope.io/v2/en/docs/building-blocks/model.md
[C-message]: https://java.agentscope.io/v2/en/docs/building-blocks/message-and-event.md
[C-tools]: https://java.agentscope.io/v2/en/docs/building-blocks/tool.md
[C-middleware]: https://java.agentscope.io/v2/en/docs/building-blocks/middleware.md
[C-state]: https://java.agentscope.io/v2/en/docs/building-blocks/context.md
[C-compaction]: https://java.agentscope.io/v2/en/docs/harness/compaction.md
[C-memory]: https://java.agentscope.io/v2/en/docs/harness/memory.md
[C-permission]: https://java.agentscope.io/v2/en/docs/building-blocks/permission-system.md
[C-plan]: https://java.agentscope.io/v2/en/docs/harness/plan-mode.md
[C-subagent]: https://java.agentscope.io/v2/en/docs/harness/subagent.md
[C-skill]: https://java.agentscope.io/v2/en/docs/harness/skill.md
[U0]: https://github.com/spring-ai-community/spring-ai-agent-utils/tree/v0.13.0/docs/tools
[U-skills]: https://github.com/spring-ai-community/spring-ai-agent-utils/blob/v0.13.0/docs/tools/SkillsTool.md
[U-memory]: https://github.com/spring-ai-community/spring-ai-agent-utils/blob/v0.13.0/docs/tools/AutoMemoryToolsAdvisor.md
[R1]: https://projectreactor.io/docs/core/release/reference/coreFeatures/schedulers.html
[J1]: https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/util/concurrent/package-summary.html
