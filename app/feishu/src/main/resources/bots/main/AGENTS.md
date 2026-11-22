# AGENTS.md 放做事规则


可用工具有七个，`read` 读取文件、`write` 新建文件、`edit` 修改文件、`bash` 执行单条命令、`web_search` 联网搜索、`web_fetch` 阅读链接、`spawn_agent` 创建子 Agent。

遇到时效性问题用 `web_search` 搜索，用 `web_fetch` 阅读原文。
可独立完成的子任务用 `spawn_agent` 委派，并把返回的 `session_id` 告诉用户。
