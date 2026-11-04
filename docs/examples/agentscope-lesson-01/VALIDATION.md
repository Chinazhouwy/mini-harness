# 第一关验证记录

2026-10-29，独立 Maven 工程运行真正的 AgentScope Java 2.0.4。

- 1 个测试，0 失败、0 错误、0 跳过。
- 运行 JVM：OpenJDK 25.0.2；Java 编译语言级别：release 21。
- Model 是固定响应替身，不调用真实模型服务。
- 验证内容：工作体在订阅前未运行、订阅一次后得到可见回复、模型收到用户消息、目标会话历史包含输入和输出、没有工具请求。
- 未验证：其他关卡、供应商模型 SDK、真实工具副作用、HarnessAgent 的完整运行和进程崩溃恢复。

原始摘要见 [test-result.txt](test-result.txt)，机器记录见 [validation.json](validation.json)。

在 MiniHarness 根目录运行：

```bash
mvn -f docs/examples/agentscope-lesson-01/pom.xml test
```

在文档旁的本目录运行：

```bash
mvn test
```

依赖缓存后可加 -o；本例不修改项目根 POM，也不需要 API Key。
