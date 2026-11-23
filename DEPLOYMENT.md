# QuantCloud 部署与排障

以下命令从仓库根目录执行。先完成 [README 的快速启动](README.md#快速启动)。启动时若叠加开发覆盖文件，后续管理命令也使用相同的 `-f` 参数。

## 首次构建和验证

- 首次构建需要访问镜像仓库、Maven、npm、PyPI 和 Hugging Face。Windows 上请检查 Docker Desktop 存储磁盘的可用空间，镜像和数据卷可能位于源码目录所在磁盘之外。
- 用 `docker compose ps` 检查服务。正常模式启用 12 个服务。访问首页后，注册并登录一个账号，再验证实时盘面能加载历史 K 线、实时行情能更新。启用了真实模型时，可再提交一条知识问答验证 DeepSeek，调用计入模型额度。
- 同一 Docker 主机并行运行两套正常模式时，每套都设独立的 `QUANT_COMPOSE_PROJECT` 和 `QUANT_HTTP_PORT`，以隔离容器、数据卷和端口。开发模式项目名由 `QUANT_DEV_COMPOSE_PROJECT` 控制，默认是 `quantcloud-dev`。与正常模式同机启动时，也要给开发模式分配空闲的前端端口。若宿主机的 `8080` 已被占用，再设置 `QUANT_DEV_GATEWAY_PORT`。

## 首次建库和设置管理员

空 MySQL 数据卷启动时自动执行 [01_schema.sql](docker/init/sql/01_schema.sql) 和 [02_seed.sql](docker/init/sql/02_seed.sql)。种子只创建一条空白交易简报。已有数据卷沿用原有表和账号，升级时按现有库版本安排迁移。

旧库升级到支持平行通道的版本时，先备份 MySQL，在维护窗口中用 MySQL 客户端对 `quant_cloud` 执行 [盘面图形迁移](docker/migrations/market-zone-chart-geometry.sql)，再更新 Core。空库随初始化 SQL 建表，可直接启动。

普通注册账号初始角色为 `VIEWER`。需要管理员时，先在网页注册，确认 `docker compose ps mysql` 显示的是目标部署，再通过容器内的客户端进入 MySQL，按提示输入 `MYSQL_ROOT_PASSWORD`。

```sh
docker compose exec mysql mysql -uroot -p quant_cloud
```

把示例中的 `your_registered_username` 换成注册用户名。先核对第一条查询的结果，再执行更新和第二条查询。

```sql
SET @admin_username = 'your_registered_username';
SELECT id, username, role FROM users WHERE username = @admin_username;
UPDATE users SET role = 'ADMIN'
WHERE username = @admin_username AND role = 'VIEWER';
SELECT username, role FROM users WHERE username = @admin_username;
```

看到 `ADMIN` 后，在网页退出并重新登录，以获取带新角色的会话。用 API 手工验证时，登录令牌放在 `Q-Token` 请求头中。

## 网页区域提醒

新库随初始化 SQL 建好提醒的三张表。已有 MySQL 数据卷在获准的维护窗口先备份，再对目标 `quant_cloud` 执行 [网页提醒迁移](docker/migrations/zone-web-alerts.sql)。迁移只创建缺失的表，可重复执行。若使用 Bash，从部署目录执行。

```sh
docker compose exec -T mysql sh -c 'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mysql -uroot quant_cloud' < docker/migrations/zone-web-alerts.sql
```

在运行环境的 `.env` 设置 `ZONE_ALERTS_ENABLED=true`，并保持共享区域、实时观测和工作台开启。沿用当前 Compose 项目名、环境文件及覆盖文件，更新 Core、Gateway、前端和 AI 服务。区间反转、收盘确认和 AI 复盘沿用已有盘面图形与提醒表。已有这两套结构的库从网页提醒版升级时无需新增迁移。早于平行通道的卷先执行上面的盘面图形迁移。

```sh
docker compose up -d --build --no-deps core-service gateway frontend ai-service
```

等待服务健康后，登录实时盘面，展开「区域提醒」订阅管理员启用的共享区间或通道。在「账号提醒设置」里开启提醒。在面板试听后，网页可在当前可见标签页为接近预警和收盘形态确认播放音效。用两个账号分别检查订阅及提醒记录，各账号应只看到自己的数据。关闭功能时，将 `ZONE_ALERTS_ENABLED` 设回 `false` 并重建 Core，已有订阅及记录可保留。

面板在当前账号成功读取 `GET /api/core/zone-alerts` 后显示。若面板没有出现，先确认目标部署已更新、Core 实际启动参数带有网页提醒开关，再检查浏览器 Network 中该请求及 Core 日志。登录后预期返回 200。404 时检查功能开关和镜像版本，401 时检查登录状态，服务端错误时检查三张提醒表与数据库连接。

```sh
docker compose ps core-service gateway frontend ai-service
docker compose exec -T core-service sh -c 'tr "\000" "\n" </proc/1/cmdline | grep -Fx -- "--zone-alerts.enabled=true"'
```

网页提醒无需邮件。确需邮件时，沿用部署环境审核过的 SMTP 渠道，通过 `ZONE_ALERTS_MAIL_HOST`、`ZONE_ALERTS_MAIL_PORT`、`ZONE_ALERTS_MAIL_TLSMODE`（`STARTTLS` 或 `IMPLICIT_TLS`）、`ZONE_ALERTS_MAIL_FROM`、`ZONE_ALERTS_MAIL_USERNAME` 和 `ZONE_ALERTS_MAIL_PASSWORD` 配置。管理员可修改自己的收件地址。普通账号需要部署人员用 `zone-alerts.mail.recipients` 按 ID 预设收件地址。把凭据留在部署环境。

安卓模拟提醒使用独立的覆盖文件 `docker/compose.android-synthetic-trial.yaml`、`ANDROID_SYNTHETIC_ENABLED` 和试用账号白名单。若当前部署已使用该覆盖文件，更新时继续带原有的 `-f` 参数及环境配置。网页提醒开关仅控制网页链路。

## 局域网访问

默认 `QUANT_HTTP_BIND=127.0.0.1`。确实要在可信局域网内共享时，将 `.env` 中的绑定地址改为宿主机的局域网 IPv4 地址，重新执行 `docker compose up -d`，再从另一台设备访问该地址和端口。防火墙入站规则仅放行可信局域网。公网访问请另设 HTTPS 反向代理和访问控制，并验证实时盘面的 SSE 收帧与重连。

## 常见故障

| 现象 | 检查方法 |
| --- | --- |
| `git clone` 连接超时 | 使用 README 中的 `sit` ZIP 链接，从解压目录启动。 |
| Docker daemon 连接失败 | 先启动 Docker Desktop 或 Docker Engine，再运行 `docker info`。Windows 可用 `wsl --status` 查看 WSL 状态。 |
| 构建在 `auth.docker.io` 超时 | 先运行 `docker pull node:20-alpine` 验证 Docker Hub 连通性。需要代理时先检查 Docker 自身的代理设置，再重试构建。 |
| 首页可开，盘面没有 K 线 | 确认使用正常模式，再查看 `docker compose logs --tail 100 data-service`。若 Bitget 直连失败，在 `.env` 启用 `BITGET_PROXY_ENABLED`，填写容器可达的代理地址和端口，再执行 `docker compose up -d --no-deps data-service`。 |
| 页面可开，登录或 AI 请求异常 | 用 `docker compose logs --tail 100 gateway core-service ai-service` 查看对应服务。手工请求遇到 401 时先检查 `Q-Token`。正常模式的 AI 还要确认模型凭据和检索容器状态。 |

Docker Desktop 的拉镜像代理和 QuantCloud Data 服务的 Bitget 代理分别配置。Docker Desktop 上，若代理运行在宿主机，可从容器尝试通过 `host.docker.internal` 访问，并填写实际监听端口。 [Docker Desktop 代理设置](https://docs.docker.com/desktop/settings-and-maintenance/settings/#proxies) · [容器访问宿主机说明](https://docs.docker.com/desktop/features/networking/)

## 停机与数据

用 `docker compose stop` 暂停服务，之后用 `docker compose up -d` 恢复。普通 `down` 保留命名卷，`down -v` 会移除命名卷。升级前请先备份业务库和 `.env`，将备份保存在仓库之外。

在 Bash 中可用以下命令导出 MySQL，运行后确认命令成功退出。

```sh
docker compose exec -T mysql sh -c 'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mysqldump --single-transaction -uroot quant_cloud' > "$HOME/quant-cloud-$(date +%Y%m%d-%H%M%S).sql"
```
