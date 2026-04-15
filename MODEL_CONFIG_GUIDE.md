# Opencode AI 模型配置指南

## 当前配置状态

### Opencode 配置 (更新于 2026-04-15)
- **配置文件**: `~/.opencode/opencode.json`
- **当前模型**: `volcengine/deepseek-v3-2-251201`
- **备用模型**: `volcengine/doubao-seed-2-0-pro-260215`
- **API提供商**: 火山引擎 (Volcengine)

### 项目中的AI配置
- **Agent框架**: AgentScope
- **当前模型**: Qwen-max (通过阿里云DashScope API)
- **配置文件**: `agents/orchestrator/config.yaml`

## 最新模型信息 (2026年4月)

### DeepSeek V3.2
- **发布日期**: 2025年12月
- **性能**:
  - GPQA: 84.0%
  - MMLU Pro: 86.2%
  - LiveCodeBench: 86.2%
  - AIME 2025: 93.1%
- **上下文长度**: 128K tokens
- **价格**: $0.27/M输入, $0.40-$0.42/M输出
- **特点**:
  - 开源模型，MIT许可证
  - 优秀的编码能力
  - 性价比极高

### Doubao Seed 2.0 Pro
- **发布日期**: 2026年2月14日
- **性能**:
  - AIME 2025: 98.3
  - Codeforces评分: 3020
  - SWE-Bench: 76.5
- **上下文长度**: 256K tokens (多模态)
- **价格**: $0.47/M输入, $2.37/M输出
- **特点**:
  - 强大的推理能力
  - 比GPT-5.2便宜3.7倍
  - 支持图像输入

## 环境变量设置

### 需要设置的API密钥

1. **火山引擎API密钥** (用于当前配置):
```bash
# 在 ~/.zshrc 或 ~/.bashrc 中添加
export VOLCANO_ENGINE_API_KEY="your_volcano_engine_api_key"
```

2. **DeepSeek API密钥** (可选，用于备用):
```bash
export DEEPSEEK_API_KEY="your_deepseek_api_key"
```

3. **阿里云DashScope API密钥** (用于项目Agent配置):
```bash
export DASHSCOPE_API_KEY="your_dashscope_api_key"
```

### 应用环境变量
```bash
# 重新加载配置文件
source ~/.zshrc

# 或直接设置当前会话
export VOLCANO_ENGINE_API_KEY="your_key_here"
```

## 模型切换指南

### 快速切换模型

#### 1. 切换到Doubao Seed 2.0 Pro
```bash
# 编辑opencode配置
sed -i '' 's/"model": "volcengine\/deepseek-v3-2-251201"/"model": "volcengine\/doubao-seed-2-0-pro-260215"/' ~/.opencode/opencode.json
```

#### 2. 切换到本地Ollama模型
```bash
# 更新配置文件为本地模型
cat > ~/.opencode/opencode.json << EOF
{
  "\$schema": "https://opencode.ai/config.json",
  "provider": {
    "ollama": {
      "npm": "@ai-sdk/openai-compatible",
      "name": "Ollama (本地部署)",
      "options": {
        "baseURL": "http://localhost:11434/v1"
      },
      "models": {
        "qwen3:14b": {
          "name": "Qwen3 14B"
        }
      }
    }
  },
  "model": "ollama/qwen3:14b"
}
EOF
```

### 项目Agent配置更新

如果需要更新项目中的Agent模型配置，修改 `agents/orchestrator/config.yaml`:

```yaml
agentscope:
  model:
    model_type: openai
    model_name: deepseek-chat  # 或 deepseek-coder
    api_key: ${DEEPSEEK_API_KEY}
    base_url: https://api.deepseek.com/v1
```

## 本地模型部署

### 使用Ollama部署本地模型

1. **安装Ollama**:
```bash
curl -fsSL https://ollama.com/install.sh | sh
```

2. **拉取模型**:
```bash
# DeepSeek Coder (推荐)
ollama pull deepseek-coder:latest

# Qwen3 (中文优化)
ollama pull qwen3:14b

# Llama 3.2
ollama pull llama3.2:latest
```

3. **启动服务**:
```bash
# 启动Ollama服务
ollama serve &

# 或使用系统服务
brew services start ollama
```

### 使用vLLM部署生产级模型

```bash
# 安装vLLM
pip install vllm

# 启动DeepSeek模型
vllm serve deepseek-ai/deepseek-coder-6.7b-instruct --port 8000
```

## 性能对比和选择建议

### 不同场景的模型选择

| 使用场景 | 推荐模型 | 理由 |
|----------|----------|------|
| **日常开发** | DeepSeek V3.2 | 性价比高，编码能力强 |
| **复杂架构设计** | Doubao Seed 2.0 Pro | 推理能力强，适合复杂任务 |
| **隐私敏感任务** | 本地Ollama模型 | 数据不离本地 |
| **高频使用** | 本地模型或DeepSeek | 降低成本 |
| **多模态任务** | Doubao Seed 2.0 Pro | 支持图像输入 |

### 成本对比

| 模型 | 输入成本 | 输出成本 | 相对成本 |
|------|----------|----------|----------|
| DeepSeek V3.2 | $0.27/M | $0.41/M | 基准 |
| Doubao Seed 2.0 Pro | $0.47/M | $2.37/M | 2-6倍 |
| GPT-5.2 | $1.75/M | $14.00/M | 6-34倍 |
| Claude Opus 4.5 | $5.00/M | $25.00/M | 18-60倍 |

## 故障排除

### 常见问题

1. **模型无法连接**:
   - 检查API密钥是否正确设置
   - 验证网络连接
   - 确认API服务状态

2. **本地模型启动失败**:
   - 检查Ollama服务状态: `ollama list`
   - 确保有足够内存: 至少16GB RAM
   - 查看日志: `ollama serve` 的输出

3. **配置不生效**:
   - 确认配置文件路径正确
   - 重启opencode服务
   - 检查环境变量是否生效

### 性能优化建议

1. **启用模型缓存**:
```bash
export OLLAMA_KEEP_ALIVE=24h
```

2. **调整上下文长度**:
```bash
OLLAMA_NUM_CTX=32768 ollama run deepseek-coder:latest
```

3. **使用GPU加速**:
```bash
# 确认CUDA可用
ollama show deepseek-coder:latest --gpu
```

## 更新历史

- 2026-04-15: 初始配置指南
- 从Doubao Seed 2.0 Pro切换到DeepSeek V3.2
- 添加本地部署选项

## 参考链接

1. [DeepSeek官方文档](https://platform.deepseek.com/api-docs/)
2. [火山引擎API文档](https://www.volcengine.com/docs/82379)
3. [Ollama文档](https://github.com/ollama/ollama)
4. [OpenCode配置文档](https://opencode.ai/docs/config)