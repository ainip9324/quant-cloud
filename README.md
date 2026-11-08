# QuantCloud

QuantCloud 是一个量化研究工作台，提供 Spot 与 USDT Futures 行情、策略回测、回放和 AI 辅助研究。回测使用模拟成交。

## 主要功能

- **实时盘面**。订阅行情、查看 K 线和共享区域。管理员可维护区域与交易简报，观察者可查看。
- **策略研究**。管理员可用 EMA20 和海龟策略提交异步回测，查看收益、回撤和模拟成交，再在 K 线中回放策略事件。
- **AI 复盘**。以结构化回测事实生成报告，展示可追溯证据。

## AI 问答与评测

首页和工作台可配置交易知识问答。Java 负责在线问答、额度和工具编排，Python 提供可选的 Embedding 检索服务及离线评测。 [检索与评测说明](quant-ai-service/eval/knowledge/README.md)

## 仓库结构

| 目录 | 职责 |
| --- | --- |
| [quant-frontend](quant-frontend/) | Vue 界面、图表与回放 |
| [quant-gateway](quant-gateway/) | API 路由与入口鉴权 |
| [quant-core-service](quant-core-service/) | 账号、回测任务、盘面与区域 |
| [quant-data-service](quant-data-service/) | Bitget 公共行情、K 线缓存与存储 |
| [quant-ai-service](quant-ai-service/) | AI 复盘、问答、检索与评测 |
| [quant-common](quant-common/) | 跨服务模型与校验 |
| [compose.yaml](compose.yaml) | 正常启动入口 |
| [docker](docker/) | 开发覆盖、镜像构建与数据库初始化 |

## 启动

安装 Docker 与 Compose，将 [环境模板](.env.example) 复制为仓库根目录的 `.env`。两种模式都需要填写 `JWT_SECRET`、`CORE_DATA_SERVICE_SECRET`、`MYSQL_ROOT_PASSWORD` 和 `MYSQL_PASSWORD`。JWT 与服务间凭据各至少 32 字节，使用不同的随机值。

### 正常启动

PowerShell 可直接运行 `.\start.ps1`。

在 `.env` 填写 `DEEPSEEK_API_KEY` 和 `KNOWLEDGE_PG_PASSWORD`，然后从仓库根目录启动。

```sh
docker compose up -d
docker compose ps
```

默认入口为 `http://localhost:8088`。服务启用实时行情、共享盘面、DeepSeek 问答、向量检索、只读报价和混合问答。首次构建会下载依赖和指定版本的公开 Embedding 权重，运行时从镜像加载权重。Bitget 默认直连，需要代理时按环境模板填写容器可达的地址和端口。

### 开发联调

PowerShell 可直接运行 `.\start-dev.ps1`。

```sh
docker compose -f compose.yaml -f docker/compose.dev.yaml up -d
docker compose -f compose.yaml -f docker/compose.dev.yaml ps
```

联调使用 fake 模型，`DEEPSEEK_API_KEY` 和 `KNOWLEDGE_PG_PASSWORD` 可以留空。实时行情、区域监控、盘面、只读报价和混合问答关闭。联调项目拥有独立的网络和数据卷。并行启动时，为不同项目配置不同的 `QUANT_HTTP_PORT`。

## 数据库初始化

Compose 在首次使用空 MySQL 数据卷时，自动执行 [01_schema.sql](docker/init/sql/01_schema.sql) 建表，再由 [02_seed.sql](docker/init/sql/02_seed.sql) 创建一条空白盘面简报。使用已有数据卷升级时，按已有库的版本安排迁移。容器重启会保留已有表和数据。

新库没有预置登录账号。先注册自己的账号，再由数据库管理员将对应的 `users.role` 设置为 `ADMIN` 并重新登录。普通注册账号默认是 `VIEWER`。

## 离线评测

在仓库根目录运行 `python -B quant-ai-service/eval/knowledge/evaluate.py --baseline-only`，可以查看交易知识问答的内置语料基线。 [检索与评测说明](quant-ai-service/eval/knowledge/README.md)
