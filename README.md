# 本地对象存储服务（Local Object Storage）

基于 Spring Boot 3 / Java 21 实现的本地对象存储：用**本地磁盘 + 内容寻址（content-addressable storage）**
模拟对象存储后端，通过项目现有的 Spring MVC Web 应用对外提供 HTTP 入口。

设计目标是让同一对象在**并发写入、重复上传、中断恢复**三类场景下的最终状态**确定、可复现、可解释**：

- 读方任意时刻只能读到某个**完整已提交的版本**，读不到写一半的内容；
- 版本号**单调递增、绝不回退**，旧版本**永不被覆盖**、始终可读；
- 相同内容（相同分片校验和集合）**不产生冗余 blob，也不产生新版本**；
- 分块校验和不匹配、清单缺失/引用不完整一律**拒绝并返回可区分的错误码**；
- 上传中断后可通过状态接口发现已落地分片，**断点续传**后完成提交。

---

## 1. 存储布局（On-Disk Layout）

数据根目录由配置项 `objectstorage.root` 决定（默认 `./object-storage-data`，也可用环境变量 `OBJECTSTORAGE_ROOT` 覆盖）。

```
<root>/
├── blobs/
│     └── <sha256>.dat                 # 内容寻址、只写一次、天然去重的不可变数据块
├── objects/
│     └── <objectName>/
│           ├── current                # 指针文件，内容只有“当前版本号”，原子替换
│           └── versions/
│                 ├── v1.json          # 不可变版本元数据
│                 ├── v2.json
│                 └── ...
└── staging/
      └── <objectName>/
            └── <uploadId>/
                  └── parts/
                        ├── part-1          # 已校验通过的分片
                        ├── part-1.sha256   # 该分片的校验和旁路文件
                        ├── part-2
                        ├── part-2.sha256
                        └── *.tmp           # 崩溃残留的临时文件（重启自动清扫）
```

关键性质：

- **blob 只写一次**：blob 文件名就是分片内容的 SHA-256。写入时先写 `*.tmp`、校验通过后再**原子 rename**
  成 `<sha256>.dat`，因此 blob 目录里不可能出现“写一半”的数据块。
- **版本元数据不可变**：`vN.json` 先写临时文件再原子 rename，一旦出现即完整。
- **current 是指针**：当前版本通过原子替换 `current` 文件发布。读者要么看到旧指针、要么看到新指针，
  不存在中间状态。
- **staging 与已提交数据隔离**：未完成的上传只存在于 `staging/`，完成时才物化 blob、写版本、翻指针，
  最后才删除 staging。中途任何失败/取消都不会触碰已提交版本。
- 对象名只允许单段安全字符（字母、数字、`.`、`_`、`-`，≤128），杜绝路径穿越。

`vN.json` 示例：

```json
{
  "objectName" : "doc.txt",
  "version" : 2,
  "checksum" : "6db4b19dab57742e55bf53e8d3666a8fa5474c897205e35d27a52d5ea2bdbac4",
  "size" : 27,
  "partChecksums" : [ "de66ef28…", "bcfa3c7e…" ],
  "deduplicated" : false
}
```

---

## 2. 版本与去重规则

### 版本判定（version decision）

完成上传（complete）时，服务在对象锁内执行如下**确定性判定**：

1. 校验清单：分片必须按 `1..N` 连续编号、无重复、每项都带校验和；
2. 校验引用完整性：清单引用的每个分片都必须在**本上传会话**中存在，且其旁路校验和与清单一致，
   否则返回 `400 invalid_manifest`；
3. 计算对象级校验和：`objectChecksum = SHA256( concat(part1Hex, part2Hex, …) )`
   （每个分片校验和是定长 64 位小写十六进制，按顺序拼接无歧义；**顺序不同即不同对象**），
   与清单声称值不一致则返回 `422 checksum_mismatch`；
4. 与当前版本比较：
   - 若**当前版本的对象校验和与本次相同** → **不产生新版本、不新增存储**，复用当前版本号，
     响应中 `deduplicated=true`；
   - 否则 → 物化所需 blob，分配 `当前最大版本号 + 1`，原子发布新版本。

因此：
- **相同内容重复上传**：版本号不变、blob 不重复（分片级去重，跨对象/跨版本同样共享 blob）；
- **不同内容上传**：版本号严格 +1；旧的 `vN.json` 与旧 blob 永不修改、永不删除，历史版本一直可读。

### 完整性校验（三道关）

1. **上传分片时**：流式计算 SHA-256，与 `X-Checksum-SHA256` 头比对，不匹配则丢弃临时文件并拒绝；
2. **完成清单时**：校验分片编号连续性、引用完整性、分片旁路校验和；
3. **物化 blob 前**：再次对流式分片计算 SHA-256，确认与目标 blob 名一致后才原子落盘。

错误原因可区分：

| 场景 | HTTP 状态 | 响应 `error` |
|---|---|---|
| 对象/上传会话不存在 | 404 | `not_found` |
| 分片校验和不匹配 / 对象总校验和不匹配 | 422 | `checksum_mismatch` |
| 清单缺失、编号不连续、引用了未上传/不一致的分片 | 400 | `invalid_manifest` |
| 其他存储 IO 故障 | 500 | `storage_error` |

---

## 3. 并发语义（Concurrency Semantics）

- **同对象写串行化**：初始化/分片上传/完成/取消对同一对象名获取同一把 JVM 内分条锁（striped lock），
  不同对象互不阻塞。
- **版本发布原子化**：新版本 = 写版本文件（原子 rename）+ 原子替换 `current` 指针。指针翻转是一个
  原子操作，所以读者只会看到某个**完整版本**，不会看到半成品。
- **版本号不回退**：新版本号来自 `versions/` 目录里的最大编号 + 1（在对象锁内计算），指针只向前移动。
- **blob 创建幂等**：blob 以校验和命名，存在即复用；并发落同一 blob 时原子 rename 冲突被视为成功
  （内容相同）。
- **读一致性快照**：一次读取应基于“同一份版本元数据”打开其引用的 blob（测试中的并发读取器即按
  `head()` 返回的版本号调用 `…/versions/{v}`）。`GET /objects/{name}` 与紧接着的元数据查询之间若发生
  版本发布，二者可能分别属于相邻的两个完整版本——两者各自都是完整、自洽的，绝不会撕裂。
- **失败/取消的原子性**：complete 在 blob 物化、版本元数据、指针翻转**全部完成之后**才删除 staging；
  此前任何异常（校验失败、IO 故障、显式 abort）都只影响 staging，已提交的当前版本原样可读。
- **崩溃恢复**：分片先写 `*.tmp`，校验通过才原子改名；JVM 崩溃残留的 `*.tmp` 不构成任何分片或 blob，
  应用重启时自动清扫。客户端可通过状态接口查询已落地分片，对缺失分片续传后完成。

> 说明：锁保证单 JVM（本服务的部署模型）内的写顺序；跨进程的可见性由“临时文件 + 原子 rename +
> 只追加版本号 + 不可变 blob”这些文件系统原语保证。

---

## 4. HTTP API

基础路径：`/api/objects`，对象名为单段安全路径段。

| 方法 | 路径 | 说明 |
|---|---|---|
| POST   | `/{name}/uploads` | 初始化分片上传，返回 `uploadId` |
| PUT    | `/{name}/uploads/{uploadId}/parts/{partNumber}` | 上传一个分片；请求头 `X-Checksum-SHA256` 声明分片 SHA-256；**幂等**，重传已存在且校验和一致的分片返回 `alreadyPresent=true` |
| GET    | `/{name}/uploads/{uploadId}` | 查询已落地分片（用于断点续传） |
| POST   | `/{name}/uploads/{uploadId}/complete` | 提交清单（JSON），校验通过后原子发布版本 |
| DELETE | `/{name}/uploads/{uploadId}` | 取消上传，仅清理 staging，不动已提交版本 |
| GET    | `/{name}` | 读取当前版本（流式；响应头带 `X-Object-Version`、`X-Checksum-SHA256`） |
| GET    | `/{name}/meta` | 当前版本元数据（JSON） |
| GET    | `/{name}/versions/{version}` | 读取指定历史版本 |

完成清单请求体：

```json
{
  "checksum": "<对象级 SHA-256：SHA256(各分片校验和十六进制按序拼接)>",
  "parts": [
    { "partNumber": 1, "checksum": "<part1 sha256>" },
    { "partNumber": 2, "checksum": "<part2 sha256>" }
  ]
}
```

> 分片 PUT 的请求体必须以 `Content-Type: application/octet-stream`（或其他非
> `application/x-www-form-urlencoded` 的类型）发送；表单编码类型会让 Servlet 容器先消费 body。

---

## 5. 本地验证方法

### 运行测试（推荐入口）

```bash
mvn test
```

测试使用 JUnit `@TempDir` 作为数据根目录，互不污染。覆盖场景与可在日志中看到的判定依据：

- `ChecksumServiceTest`：SHA-256 与已知向量一致、组合校验和确定性且**对顺序敏感**；
- `ObjectStorageServiceTest`：
  - 首次发布版本并按字节读回；
  - 相同内容重复上传 → 版本不变、blob 数不增（打印去重判定）；
  - 新版本发布后 v1 仍按字节可读（打印版本判定依据）；
  - 分片校验和错误 → `422` 拒绝；清单引用缺失分片 → `400` 拒绝；对象总校验和不符 → 拒绝且指针停在 v1；
  - 中断后按状态续传、重复分片幂等；abort 不影响已提交版本；
- `ConcurrentWritersTest`：8 个写者并发上传**不同内容** + 1 个持续读取者，断言
  - 读者读到的永远是某个完整版本（按版本元数据快照校验字节）、观察到的版本号单调不回退；
  - 最终产生恰好 8 个版本、24 个唯一分片 = 24 个 blob；
  - 再顺序写入一个“哨兵”内容，断言**最终版本确定为 v9**、字节确定（可复现）；
  - 6 个写者并发上传**相同内容** → 收敛为唯一 v1、仅 2 个 blob；
  - 日志打印每个写者的版本判定、最终 `FINAL decision`、blob 去重数与读取者版本轨迹；
- `InterruptionRecoveryTest`：模拟分片 2 写到一半崩溃（残留孤儿 `.tmp`），断言
  - 无半成品 blob/版本、当前仍为 v1 且字节不变；
  - “重启”后的新服务实例只识别已完整提交的分片，续传后发布字节精确的 v2，且 v1 依旧可读；
- `ObjectStorageControllerTest`：经真实 Spring MVC 的端到端生命周期、去重、三类错误码区分、
  abort 后旧版本仍可通过 HTTP 读取。

测试日志中的判定依据形如：

```
[concurrent] writer=3 published-or-reused version=4 checksum=…
[concurrent] unique parts=24 blobs-on-disk=24
[concurrent] FINAL decision: current=v9 checksum=… (deterministic sentinel)
[dedup-storm] decision: all 6 writers reuse v1; blobs=2 checksum=…
[version] decision: content differs v1=… -> publish v2=…; v1 still readable
[reject] wrong object checksum -> Object checksum mismatch: manifest claims … but assembled object has …
```

### 启动服务并用 curl 手动验证

```bash
mvn spring-boot:run
# 数据默认写入 ./object-storage-data，可用 OBJECTSTORAGE_ROOT=/tmp/osd mvn spring-boot:run 覆盖
```

另开终端：

```bash
NAME=demo.txt
# 1) 初始化
UP=$(curl -s -XPOST http://localhost:8080/api/objects/$NAME/uploads | sed -E 's/.*"uploadId":"([^"]+)".*/\1/')

# 2) 上传两个分片（自行计算 sha256）
#    注意：必须声明非表单类型（application/octet-stream）。
#    若使用 curl 默认的 application/x-www-form-urlencoded，
#    Spring 会把请求体当作表单参数提前消费，服务端将读到空 body 而报 checksum_mismatch。
echo -n "hello-" > /tmp/p1; echo -n "world" > /tmp/p2
CS1=$(sha256sum /tmp/p1 | cut -d' ' -f1)
CS2=$(sha256sum /tmp/p2 | cut -d' ' -f1)
curl -s -XPUT -H "X-Checksum-SHA256: $CS1" \
  -H 'Content-Type: application/octet-stream' --data-binary @/tmp/p1 \
  http://localhost:8080/api/objects/$NAME/uploads/$UP/parts/1
curl -s -XPUT -H "X-Checksum-SHA256: $CS2" \
  -H 'Content-Type: application/octet-stream' --data-binary @/tmp/p2 \
  http://localhost:8080/api/objects/$NAME/uploads/$UP/parts/2

# 3) 续传前查看已落地分片
curl -s http://localhost:8080/api/objects/$NAME/uploads/$UP

# 4) 计算对象级校验和（各分片校验和十六进制按序拼接后再 SHA-256）
OBJCS=$(printf "%s%s" "$CS1" "$CS2" | sha256sum | cut -d' ' -f1)
curl -s -XPOST -H 'Content-Type: application/json' \
  -d "{\"checksum\":\"$OBJCS\",\"parts\":[{\"partNumber\":1,\"checksum\":\"$CS1\"},{\"partNumber\":2,\"checksum\":\"$CS2\"}]}" \
  http://localhost:8080/api/objects/$NAME/uploads/$UP/complete

# 5) 读取 / 查看元数据 / 读历史版本
curl -i http://localhost:8080/api/objects/$NAME
curl -s http://localhost:8080/api/objects/$NAME/meta
curl -s http://localhost:8080/api/objects/$NAME/versions/1

# 6) 用相同内容再传一次 -> 版本仍是 1，deduplicated=true
```

---

## 6. 代码结构

```
src/main/java/com/github/lisama569/objectstoragejava/
├── Application.java                 # Spring Boot 入口
├── config/
│   ├── StorageProperties.java       # objectstorage.root 配置
│   └── StorageBootstrap.java        # 启动建目录 + 崩溃临时文件清扫
├── dto/                             # 请求/响应记录（init/status/manifest/StoredObject …）
├── exception/                       # ChecksumMismatch / InvalidManifest / UploadNotFound …
├── service/
│   ├── ChecksumService.java         # 流式 SHA-256、分片校验和组合
│   ├── LockManager.java             # 按对象名分条的 JVM 锁
│   ├── StorageLayout.java           # 磁盘路径布局与安全校验
│   └── ObjectStorageService.java    # 核心：分块写入、校验、去重、原子版本发布、读取
└── web/
    ├── ObjectStorageController.java # HTTP 入口
    └── StorageExceptionHandler.java # 异常 -> 可区分状态码
```
