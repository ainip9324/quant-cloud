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

## 快速启动

1. 安装 Docker 和 Compose 插件。Windows 可使用 [Docker Desktop 的 WSL 2 后端](https://docs.docker.com/desktop/features/wsl/)。首次安装 WSL 时，在管理员终端执行 `wsl --install` 并重启。启动 Docker 后，用 `docker info` 确认引擎可用，用 `docker compose version` 确认 Compose 可用。 [微软 WSL 安装说明](https://learn.microsoft.com/zh-cn/windows/wsl/install)
2. 获取 `sit` 源码。也可以直接[下载 sit ZIP](https://github.com/ainip9324/quant-cloud/archive/refs/heads/sit.zip)，解压后进入包含 `compose.yaml` 的目录。

   ```sh
   git clone --branch sit --single-branch https://github.com/ainip9324/quant-cloud.git
   cd quant-cloud
   ```

3. 在仓库根目录把 [`.env.example`](.env.example) 复制为 `.env`。两种模式都要填写 `JWT_SECRET`、`CORE_DATA_SERVICE_SECRET`、`MYSQL_ROOT_PASSWORD` 和 `MYSQL_PASSWORD`。为两种身份凭据各生成一份独立的至少 32 字节随机值。正常模式还需要填写 `DEEPSEEK_API_KEY` 和 `KNOWLEDGE_PG_PASSWORD`。请将实际凭据保存在本机 `.env`。

### 正常模式

```sh
docker compose config -q
docker compose up -d --build
docker compose ps
```

等服务健康后访问 [本机 QuantCloud](http://127.0.0.1:8088)。正常模式启用实时盘面、DeepSeek 和向量检索。已有镜像时，Windows 可用 `.\start.ps1` 启动。

### 开发联调

```sh
docker compose -f compose.yaml -f docker/compose.dev.yaml config -q
docker compose -f compose.yaml -f docker/compose.dev.yaml up -d --build
docker compose -f compose.yaml -f docker/compose.dev.yaml ps
```

联调使用 fake 模型，可将 `DEEPSEEK_API_KEY` 和 `KNOWLEDGE_PG_PASSWORD` 留空。向量检索和实时盘面默认关闭，历史行情请求仍可能访问 Bitget。已有镜像时，Windows 可用 `.\start-dev.ps1` 启动。

首次建库、设置管理员、局域网访问和常见启动故障见[部署与排障指南](DEPLOYMENT.md)。

## 离线评测

在仓库根目录运行 `python -B quant-ai-service/eval/knowledge/evaluate.py --baseline-only`，可以查看交易知识问答的内置语料基线。 [检索与评测说明](quant-ai-service/eval/knowledge/README.md)
