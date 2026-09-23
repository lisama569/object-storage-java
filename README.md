# 本地对象存储服务（Local Object Storage）

用本地磁盘模拟对象存储后端，通过 Spring Boot Web 应用对外提供 HTTP 入口。
核心目标是让同一对象在**并发写入、重复上传、中断/失败**三类场景下的最终状态
**确定、可复现、可解释**：

- 分块写入 + 内容寻址去重 + 不可变版本管理；
- 完整的分块/对象两级 SHA-256 校验，损坏或引用不完整一律拒绝且错误码可区分；
- 多写入者并发发布同一对象时，版本号单调递增、读取方只看到某个完整版本；
- 新版本失败或中止绝不破坏已存在的旧版本；上传会话持久化，进程重启后可恢复。

---

## 1. 构建与运行

```bash
# 构建（Java 21+）
mvn clean package

# 运行（默认存储根目录 data/storage，可用配置覆盖）
mvn spring-boot:run
# 或自定义存储根目录
mvn spring-boot:run -Dspring-boot.run.arguments="--objectstorage.root=/var/lib/objstore"
```

服务默认监听 `8080`。配置项见 `src/main/resources/application.properties`：

| 配置项 | 默认值 | 说明 |
| --- | --- | --- |
| `objectstorage.root` | `data/storage` | 存储根目录（blobs/对象版本/会话都在其下） |
| `server.port` | `8080` | HTTP 端口 |

---

## 2. HTTP API

对象键通过查询参数 `key` 传递（可含 `/`、`.` 等字符；服务端做安全校验并以
UTF-8 全量十六进制编码落盘，杜绝路径穿越）。

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| POST | `/api/objects/uploads?key=K` | 初始化分片上传，返回 `sessionId` |
| PUT | `/api/objects/uploads/{sid}/parts/{n}` | 上传第 n 个分块（body 为字节，可选头 `X-Content-SHA256`） |
| POST | `/api/objects/uploads/{sid}/complete` | 提交清单 + 对象整体校验和，原子发布版本 |
| DELETE | `/api/objects/uploads/{sid}` | 中止上传，清理会话（不影响已存在版本） |
| PUT | `/api/objects?key=K` | 单次整对象上传（等价于单分块的分片上传） |
| GET | `/api/objects?key=K[&version=N]` | 下载当前或指定版本（响应头含版本与 SHA-256） |
| GET | `/api/objects/info?key=K[&version=N]` | 查看版本元数据 |

### 2.1 分片上传流程

```bash
B=http://localhost:8080/api/objects
K=photos/holiday.jpg

# 1) 初始化
SID=$(curl -s -X POST "$B/uploads?key=$K" | sed -E 's/.*"sessionId":"([^"]+)".*/\1/')

# 2) 逐块上传（强烈建议带 X-Content-SHA256，服务端会逐块重算并比对）
curl -s -X PUT "$B/uploads/$SID/parts/1" \
  -H 'Content-Type: application/octet-stream' \
  -H "X-Content-SHA256: $(sha256sum part1.bin | cut -d' ' -f1)" \
  --data-binary @part1.bin
# ... parts/2, parts/3 ...

# 3) 完成：清单（分块序号+每块校验和，必须 1..N 连续）+ 对象整体 SHA-256
WHOLE=$(cat part1.bin part2.bin part3.bin | sha256sum | cut -d' ' -f1)
curl -s -X POST "$B/uploads/$SID/complete" -H 'Content-Type: application/json' -d "{
  \"parts\": [
    {\"partNumber\":1,\"checksum\":\"<sha-part1>\"},
    {\"partNumber\":2,\"checksum\":\"<sha-part2>\"},
    {\"partNumber\":3,\"checksum\":\"<sha-part3>\"}
  ],
  \"checksum\":\"$WHOLE\"
}"
```

完成响应里包含 `version`、`checksum`、`deduplicated` 以及人类可读的
`decision`（版本判定依据）。

### 2.2 错误码（可区分，不静默写入坏数据）

| code | HTTP | 触发条件 |
| --- | --- | --- |
| `INVALID_OBJECT_KEY` | 400 | 对象键为空/过长/含控制字符、反斜杠或 `..` 路径段 |
| `BAD_REQUEST` | 400 | 分块号越界、校验和格式非法等 |
| `OBJECT_NOT_FOUND` | 404 | 对象不存在 |
| `VERSION_NOT_FOUND` | 404 | 指定版本不存在 |
| `SESSION_NOT_FOUND` | 404 | 会话不存在（含已中止/已完成被清理后再访问） |
| `SESSION_ABORTED` | 409 | 会话已中止后又被操作 |
| `CHUNK_CHECKSUM_MISMATCH` | 422 | 分块实际 SHA-256 与声明不符，或清单中某块校验和与存储不符 |
| `MANIFEST_MISSING` | 422 | 完成时未提供清单/清单为空/编号与校验和数量不一致 |
| `MANIFEST_INCOMPLETE` | 422 | 分块序号不从 1 连续、引用了从未上传的分块、引用的 blob 缺失 |
| `OBJECT_CHECKSUM_MISMATCH` | 422 | 服务端拼接重算的对象整体 SHA-256 与声明不符；或读取时发现内容与清单不符 |
| `IO_ERROR` | 500 | 底层磁盘 I/O 错误 |

---

## 3. 磁盘存储布局

```
<objectstorage.root>/
├── blobs/
│   └── <xx>/<sha256>                 # 内容寻址分块（CAS），xx=校验和前2位分桶
│
├── objects/
│   └── <xx>/<keyHex>/                # keyHex = 对象键 UTF-8 字节的全量十六进制
│       ├── versions/
│       │   ├── 0000000001.json        # 不可变版本清单（10 位零填充，字典序=版本序）
│       │   └── 0000000002.json
│       ├── current                    # 当前版本号指针（纯文本 "2"），原子移动切换
│       └── tmp/                       # 发布中的临时清单/临时指针（崩溃残留由启动恢复清理）
│
├── sessions/
│   └── <sessionId>/
│       ├── session.json               # 会话元数据（objectKey、createdAt、state）
│       └── parts/<n>.json             # 已上传分块引用（partNumber/checksum/size）
│
└── tmp/                               # 分块落盘临时区（启动时清空）
```

版本清单 JSON 示例：

```json
{
  "version": 2,
  "objectKey": "photos/holiday.jpg",
  "checksum": "1d377a58...c0c3",
  "size": 20,
  "parts": [ { "partNumber": 1, "checksum": "1d377a58...c0c3", "size": 20 } ],
  "createdAt": 1790118884713,
  "completedAt": 1790118884722
}
```

---

## 4. 版本与去重规则

### 4.1 两级去重

1. **分块级（内容寻址 CAS）**：每个分块先写入 `tmp/` 并实时计算 SHA-256，
   再原子移动到 `blobs/<xx>/<sha256>`。**分块字节内容即其主键**——
   无论同一会话重传、不同会话还是不同对象，只要分块字节相同，物理上只存一份。
   并发上传相同分块时由同一校验和的锁串行化，目标已存在则直接复用。
2. **对象级（幂等发布）**：完成上传时服务端按清单顺序拼接 blob，独立重算整个
   对象的 SHA-256（不盲信客户端）。若与 `current` 版本的 `(checksum, size)`
   完全相同，则**不分配新版本号**，`deduplicated=true`。
   - 因此即便分块边界不同，只要最终对象字节一致，也不会产生新版本。

> 说明：去重以"字节内容"为准。同样的整体内容用不同切分方式上传，对象层面判为
> 同一版本；但物理 blob 按分块字节存储，切分不同会产生不同的分块 blob（整体字节
> 仍是确定的）。相同切分的重复上传不会新增任何 blob。

### 4.2 单调、不可变的版本

- 版本号从 1 开始，在对象锁内按 `current + 1` 分配，**只增不减**。
- 每个 `versions/N.json` 一经发布即不可变；旧版本永不被覆盖或删除，可用
  `?version=N` 永久读取。
- 发布顺序（崩溃安全）：
  1. 清单写入对象 `tmp/` 并 fsync；
  2. 清单原子移入 `versions/N.json`（旧文件不动）；
  3. **最后**用一次性临时文件原子移动覆盖 `current` 指针。

  在第 3 步之前读者看到的永远是旧版本；指针切换是原子的，读者要么读到完整旧版、
  要么读到完整新版。

---

## 5. 并发语义

- **写入 ↔ 写入**：服务对每个对象键使用固定数量、**永不移除的分段锁**
  （striped locks）。同一对象的"读当前版本 → 去重判定 → 分配版本号 → 原子发布"
  整段在该对象锁内串行完成，因此：
  - 版本号严格单调递增，不会回退；
  - 每个不同内容对应一个版本，不会相互覆盖丢失；
  - 相同内容并发完成时幂等（只认一个版本）。

  > 实现注意：不能在临界区结束后把锁从 Map 中移除——若另一线程正阻塞等待同一把
  > 锁对象，移除会让新线程拿到一把新锁而并发进入临界区，基于过期 current 分配出
  > 回退版本号。固定分段锁避免了该问题。

- **读取 ↔ 写入**：读取不加锁，仅依赖"版本清单不可变 + `current` 指针原子切换"。
  读取当前版本时**只读一次指针**，并严格按这一次得到的同一份清单去拼接 blob
  （`readCurrentSnapshot`），从而保证"看到的版本号"与"读到的字节"属于同一版本，
  不会出现"旧清单 + 新字节"的错配。读到的字节会整体重算 SHA-256 并与清单核对，
  任何不一致都以 `OBJECT_CHECKSUM_MISMATCH` 拒绝，绝不返回半成品。

- **一致性模型**：每个读者自身观测到的版本号单调不减；对"读区间严格不重叠"的两次
  读取（前一次读完之后后一次才开始），后一次看到的版本不会更低。并发重叠的读取
  之间可能分别落在发布前后，属正常现象（读到的都是某个完整版本）。

---

## 6. 中断恢复与失败隔离

- 会话元数据与每个已上传分块引用都持久化在 `sessions/<id>/`。分块传到一半停止后，
  用**同一个 sessionId** 继续补齐剩余分块再 complete 即可。
- **进程重启恢复**（启动时自动执行）：
  - 清空全局 `tmp/`（未落盘完成的分块临时文件）；
  - 清理各对象 `tmp/` 的发布残留，并删除编号大于 `current` 指针的"半发布"清单，
    保证版本号连续、不留半成品；
  - 扫描会话目录：`ACTIVE` 会话保留可继续；`COMPLETED/ABORTED` 残留目录删除。
- **失败隔离**：分块校验失败、清单缺失/不完整、对象整体校验不符等都发生在
  指针切换**之前**，只会让本次会话失败；`current` 与旧清单完全不被触碰，旧版本
  始终可读。显式 `DELETE` 会话只删除该会话目录与分块引用，共享 blob 与既有版本
  一律不动。

---

## 7. 本地验证（测试）

```bash
mvn test
```

共 21 个测试（`src/test/java/.../tests/`），每个用例使用独立临时目录：

| 测试类 | 覆盖内容 |
| --- | --- |
| `BasicStorageTest` | 分片上传/下载、分块校验失败、清单缺失、清单缺口/悬空引用、对象整体校验失败、路径穿越拒绝、单次上传 |
| `DedupVersionTest` | 相同内容重复上传不产生新版本、内容变更产生单调版本且旧版本可读、跨对象相同分块只存一份 blob、不同切分相同内容幂等 |
| `ConcurrentWriteTest` | 8 写入者并发发布不同内容 + 4 读取者并发读：最终版本恰为 8、版本集合 1..8 且内容一一对应、无撕裂读、严格先后读不回退 |
| `InterruptionRecoveryTest` | 分块中断后续传、中止后拒绝再写、新版本失败不破坏旧版本、模拟重启后恢复 ACTIVE 会话并确定性完成 |
| `WebApiIntegrationTest` | 端到端 HTTP：完整分片流程、下载校验、重复上传去重、422/404 等错误码 |
| `ApplicationTests` | Spring 上下文加载 |

测试日志会打印**每个版本的 SHA-256 校验和**与 `decision`（版本判定依据），
并发测试还会打印每个写入者得到的版本号、读者观测次数与撕裂读计数
（稳定为 `tornReads=0`），便于核对最终状态为何确定且可复现。

也可用第 2 节的 curl 手工验证；磁盘上可用
`find <root> -type f`、`cat <objectDir>/current` 直接核对布局与指针。
