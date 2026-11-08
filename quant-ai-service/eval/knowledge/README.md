# 交易知识问答检索评测

用当前公开资料和 24 道固定问题对照整包与向量 Top 3。每条公开资料是一个检索片段，评测从 Java 服务原用的 [语料](../../src/main/resources/knowledge/public-sources.json) 加载出处。

`retrieval.py` 提供共享的 Embedding 和 pgvector 查询，`evaluate.py` 负责离线样例与报告，`serve.py` 提供 `POST /retrieve`。Java 客户端用 `Content-Length` 标明 UTF-8 JSON 的字节数，供检索端有界读取。构建本机检索镜像时一起打包 `serve.py`、`retrieval.py` 和同版公开语料。

正常 Compose 启动使用 [检索 Dockerfile](../../../docker/Dockerfile.retrieval) 构建镜像。镜像在构建时取得固定修订的模型文件，运行时通过 `--model-path` 加载，并连接 AI 回环网络上的 pgvector。以下命令供单独离线评测或检索开发使用。

## 运行

在仓库根目录运行整包基线。它只读语料和样例，不需要安装依赖。

```powershell
python -B quant-ai-service/eval/knowledge/evaluate.py --baseline-only
```

也可以先用内存余弦排序验证召回，结果会标为 `memory`。装好下方依赖后，运行 `python -B quant-ai-service/eval/knowledge/evaluate.py --memory --allow-model-download`。

向量对照使用本机一次性 PostgreSQL，数据库名为 `quant_knowledge_eval`，账号为 `knowledge_eval`。设置实验专用密码并执行以下命令。Docker 只发布到回环地址，数据库文件放在临时内存中。安装扩展的命令只对这台实验库执行。

```powershell
python -m pip install -r quant-ai-service/eval/knowledge/requirements.txt
$env:KNOWLEDGE_EVAL_PG_PASSWORD = 'local-knowledge-eval-only'
docker run --rm -d --name quant-knowledge-eval-db --publish 127.0.0.1:55432:5432 --tmpfs /var/lib/postgresql/data --env POSTGRES_DB=quant_knowledge_eval --env POSTGRES_USER=knowledge_eval --env "POSTGRES_PASSWORD=$env:KNOWLEDGE_EVAL_PG_PASSWORD" pgvector/pgvector:0.8.7-pg17-bookworm
# 等下面命令返回 accepting connections 后，继续执行。
docker exec quant-knowledge-eval-db pg_isready -h 127.0.0.1 -U knowledge_eval -d quant_knowledge_eval
docker exec quant-knowledge-eval-db psql -U knowledge_eval -d quant_knowledge_eval -v ON_ERROR_STOP=1 -c 'CREATE EXTENSION vector;'

python -B quant-ai-service/eval/knowledge/evaluate.py --pg-port 55432 --allow-model-download
docker stop quant-knowledge-eval-db
Remove-Item Env:KNOWLEDGE_EVAL_PG_PASSWORD
```

首次运行显式允许下载公开 Embedding 权重到 `tmp/quant-knowledge-eval/models`。已缓存后可以去掉 `--allow-model-download`，模型在本机 CPU 编码问题和片段。检索向量写入会话临时表，执行精确余弦距离查询，会话结束后表被回收。可用 `--top-k` 比较不同召回规模。生成模型、在线问答、Redis 和 MySQL 均不参与此实验。

## 本机问答试用

按上一节准备并保留一次性数据库，完成一次允许下载模型的评测以填充缓存，然后在同一终端运行：

```powershell
python -B quant-ai-service/eval/knowledge/serve.py --pg-port 55432 --port 18584
```

仅在独立启动的本机 AI 进程中设置 `AI_KNOWLEDGE_RETRIEVAL_PORT=18584`。该进程与检索进程须处于同一回环网络。检索端用本地缓存权重，只返回语料指纹和 Top 4 的公开来源 ID。AI 默认端口值为 `0`，使用整包；连接失败、超时或语料不匹配时也回退整包。结束试用时退出检索进程，再停止一次性数据库。

## 读结果

- **召回。** 只统计有标注来源的题。需要两条而只命中一条时，该题的召回为 0.5。整包召回天然为 100%，向量模式要在可接受的召回下减少上下文。
- **上下文和延迟。** 字节数只计模型请求的资料数组。检索 P95 计单题本地编码和所选后端的查询，不含首次模型加载及生成模型延迟。
- **回答对照。** 两组使用同一模型、提示和问题。按检索报告中的 `sourceIds` 取同版语料，每题保存模型的 `status` 和 `sourceIds`。把报告和答案以 UTF-8 JSON 保存后，运行 `python -B quant-ai-service/eval/knowledge/evaluate.py --retrieval-report 报告.json --answers 答案.json`。答案文件第一层是 `baseline` 和 `vector`，第二层以题目 ID 为键，每题的值为 `{"status":"ANSWERED","sourceIds":["trend-following-v1"]}`。程序核对状态、标注引用和实际提供的来源，回答正文能否由引用支持需人工复核。
