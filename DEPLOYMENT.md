# QuantCloud 部署与排障

以下命令从仓库根目录执行。先完成 [README 的快速启动](README.md#快速启动)。启动时若叠加开发覆盖文件，后续管理命令也使用相同的 `-f` 参数。

## 首次构建和验证

- 首次构建需要访问镜像仓库、Maven、npm、PyPI 和 Hugging Face。Windows 上请检查 Docker Desktop 存储磁盘的可用空间，镜像和数据卷可能位于源码目录所在磁盘之外。
- 用 `docker compose ps` 检查服务。正常模式启用 12 个服务。访问首页后，注册并登录一个账号，再验证实时盘面能加载历史 K 线、实时行情能更新。启用了真实模型时，可再提交一条知识问答验证 DeepSeek，调用计入模型额度。
- 同一 Docker 主机并行运行两套正常模式时，每套都设独立的 `QUANT_COMPOSE_PROJECT` 和 `QUANT_HTTP_PORT`，以隔离容器、数据卷和端口。开发模式项目名由 `QUANT_DEV_COMPOSE_PROJECT` 控制，默认是 `quantcloud-dev`。与正常模式同机启动时，也要给开发模式分配空闲的前端端口。若宿主机的 `8080` 已被占用，再设置 `QUANT_DEV_GATEWAY_PORT`。

## 首次建库和设置管理员

空 MySQL 数据卷启动时自动执行 [01_schema.sql](docker/init/sql/01_schema.sql) 和 [02_seed.sql](docker/init/sql/02_seed.sql)。种子只创建一条空白交易简报。已有数据卷沿用原有表和账号，升级时按现有库版本安排迁移。

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
