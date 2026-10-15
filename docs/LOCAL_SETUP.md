# 本地运行

## 环境

- Docker Desktop / Docker Compose
- Node.js 20+
- Python 3.11+
- JDK 17 与 Maven 3.9+；PowerShell 下也可运行根目录 `setup-env.ps1` 安装项目级工具链

## 配置

复制 `scripts/local-env.example.ps1` 为 `scripts/local-env.ps1`，填写：

| 变量 | 用途 |
| --- | --- |
| `DASHSCOPE_API_KEY` | Java 和 Python 模型调用 |
| `PINECONE_API_KEY` | Pinecone 索引访问 |
| `PINECONE_INDEX_NAME` | 自己的稠密索引名称 |
| `PINECONE_SPARSE_INDEX_NAME` | 自己的稀疏索引名称 |
| `PINECONE_SPARSE_HOST` | 稀疏索引端点；开启自动解析时可留空 |
| `AGENT_INTERNAL_TOKEN` | Java/Python 内部工具鉴权，启动脚本可生成进程内值 |
| `PUBLIC_APP_BASE_URL` | 本机演示入口，默认 localhost |

Pinecone 稠密索引维度需与 Java 配置的 embedding 模型输出维度一致。稀疏索引使用对应的稀疏模型和文本字段。检索配置以 `src/main/resources/application.yml` 为准。

## 启动

在 `frontend` 执行 `npm ci` 和 `npm run build`，构建产物写入 Spring Boot 静态资源目录。回到仓库根目录执行：

```powershell
.\scripts\start-local-stack.ps1
```

该脚本启动 Redis、Kafka、GROBID、SearXNG、Prometheus、Grafana，创建 Python 虚拟环境，再运行 Java 与 Python 服务。

打开 `http://localhost:8080/app/`。在工作台选择知识空间、上传自己的论文，然后提交问题。

```powershell
.\scripts\stop-local-stack.ps1
```

## Telegram

复制 `scripts/telegram-local-env.example.ps1` 为 `scripts/telegram-local-env.ps1`，填写自己创建的 Bot Token。启动完整栈时指定这份环境文件：

```powershell
.\scripts\start-local-stack.ps1 -EnvFile scripts/telegram-local-env.ps1
```

它会先加载共享模型与索引配置，再启用 Bot 轮询。用户绑定知识空间后，Bot 与 Web 使用同一套后端任务能力。

## 配置管理

实际 `.env`、`*-local-env.ps1`、文档数据、日志和运行时状态由 `.gitignore` 排除。`.example` 文件仅提供变量模板。公开服务文档地址和 localhost 地址用于依赖与本地运行。
