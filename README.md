# 本地对象存储服务（Local Object Storage）

基于 Spring Boot 3 / Java 21 实现的本地对象存储：用**本地磁盘 + 内容寻址（content-addressable storage）**
模拟对象存储后端，通过项目现有的 Spring MVC Web 应用对外提供 HTTP 入口。

设计目标是让同一对象在**并发写入、重复上传、中断恢复**三类场景下的最终状态**确定、可复现、可解释**：

- 读方任意时刻只能读到某个**完整已提交的版本**，读不到写一半的内容；一次读取返回的**版本元信息与字节内容必然属于同一个版本快照**；
- 可以直接读取**历史版本**，也可以对当前版/历史版做**字节范围（Range）下载**，范围跨分块时逐 blob 完整性校验不降级；
- 版本号**单调递增、绝不回退**，旧版本**永不被覆盖**、在被显式退役前始终可读；
- 相同**字节内容**（与分块方式无关的整对象内容指纹相同）**不产生冗余 blob，也不产生新版本**；
- 被新版本替换、或被**显式退役**后不再被任何可读版本使用的 blob，可通过**垃圾回收**安全释放，回收不阻塞读写、失败可安全重试；
- 分块校验和不匹配、清单缺失/引用不完整一律**拒绝并返回可区分的错误码**；
- 上传中断后可通过状态接口发现已落地分片，**断点续传**后完成提交。

---

## 0. 能力与兼容范围

- **读取**：新增历史版本直读与单区间 `Range` 部分下载；无 `Range` 头时行为与旧版完全一致（200 全量）。
- **去重**：重复判定身份从“清单校验和（依赖分块）”升级为“整对象内容指纹（与分块无关）”。
  原清单校验和仍保留，用于校验完成清单；完成请求体格式不变，老客户端无需改造。
- **版本元数据**：`vN.json` 与 `/meta`、complete 响应新增 `contentChecksum` 字段（只增不改，旧字段仍在）。
- **回收**：旧版本默认永不删除的策略不变；磁盘释放需要**显式**调用“退役 + GC”两步，不会自动淘汰数据。
- **部署模型**：单 JVM、单机本地磁盘；读写串行化与租约保护为 JVM 内保证，跨进程的可见性依赖
  “原子 rename + 只追加版本号 + 不可变 blob”，GC 的在途保护跨进程由宽限窗口 `objectstorage.gc.min-age` 兜底。
- **文件系统**：依赖原子 rename 与（POSIX）“已打开文件删除后仍可读”；Windows 上删除正在读取的 blob
  可能本轮失败，会自动留待下一轮重试，不会删错。

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
│                 ├── v1.retired       # 退役墓碑标记（存在即表示 v1 不可再读，可被 GC 回收）
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
  "contentChecksum" : "a3f1c0…（整对象原始字节的 SHA-256，与分块方式无关，用于重复判定）",
  "size" : 27,
  "partChecksums" : [ "de66ef28…", "bcfa3c7e…" ],
  "deduplicated" : false
}
```

---

## 2. 版本与去重规则

服务对一个对象同时维护两种校验和，职责不同，不能混用：

| 字段 | 计算方式 | 是否依赖分块方式 | 用途 |
|---|---|---|---|
| `checksum`（清单校验和） | `SHA256( concat(各分片校验和十六进制) )` | **依赖**：分片切法/顺序不同则不同 | 校验完成清单是否被篡改、分片顺序/集合是否与上传一致 |
| `contentChecksum`（内容指纹） | 对**拼接后的整对象原始字节**流式求 `SHA256` | **不依赖**：只要最终字节相同就相同 | 版本发布与**重复上传判定的唯一身份** |

### 版本判定（version decision）

完成上传（complete）时，服务在对象锁内执行如下**确定性判定**：

1. 校验清单：分片必须按 `1..N` 连续编号、无重复、每项都带校验和；
2. 校验引用完整性：清单引用的每个分片都必须在**本上传会话**中存在，且其旁路校验和与清单一致，
   否则返回 `400 invalid_manifest`；
3. 计算**清单校验和**：`objectChecksum = SHA256( concat(part1Hex, part2Hex, …) )`
   （每个分片校验和是定长 64 位小写十六进制，按顺序拼接无歧义；**顺序不同即不同对象**），
   与清单声称值不一致则返回 `422 checksum_mismatch`；
4. 计算**内容指纹**：按分片顺序流式读取**本次会话的原始分片字节**，拼接后求 SHA-256，
   得到 `contentChecksum`（本次额外读取只发生在 complete 的对象锁内，不影响已提交数据）；
5. 与该对象**所有仍可读（未退役）的版本**逐个比较 `contentChecksum`：
   - 若某个可读版本的内容指纹与本次相同 → **不产生新版本、不新增存储**，复用那个版本号，
     响应中 `deduplicated=true`；
   - 否则 → 物化所需 blob，分配 `当前最大版本号 + 1`，原子发布新版本，`deduplicated=false`。

### 重复上传判定口径（duplicate decision）——可复现、口径唯一

- **判定身份只有一个：整对象原始字节的 `contentChecksum`。**
- 因此，同样的字节即使这次**换了分块方式**（块的个数、每块大小都不同），只要拼起来字节一致，
  就算重复：复用已有版本、`deduplicated=true`、一个新 blob 都不产生。
- 内容**真的变了**（哪怕只变一个字节），`contentChecksum` 必不同 → 一定发新版本、
  `deduplicated=false`。对外的“是否重复”结论与磁盘事实严格一致，可反复复现。
- 判定范围是**同一对象名**下所有可读版本；若再次上传的字节恰好等于某个**历史版本**（不是当前版），
  会复用那个历史版本号，`current` 指针**不会因此回退**（复用的是历史身份，不是当前位置）。
- **跨对象**的相同字节属于不同的版本线（各对象仍有自己的 v1/v2…，不互算“重复”），但由于 blob
  内容寻址，底层字节天然共享、**不会多占一份磁盘**。
- **不同内容上传**：版本号严格 +1；旧的 `vN.json` 与旧 blob 永不修改；历史版本在被显式退役前一直可读。

判定依据会以 `[dedup] decision …` / `[version] decision …` 写入日志并在测试日志中可见。

### 完整性校验（写入三道关 + 读取逐 blob 复核）

1. **上传分片时**：流式计算 SHA-256，与 `X-Checksum-SHA256` 头比对，不匹配则丢弃临时文件并拒绝；
2. **完成清单时**：校验分片编号连续性、引用完整性、分片旁路校验和；
3. **物化 blob 前**：再次对流式分片计算 SHA-256，确认与目标 blob 名一致后才原子落盘；
4. **读取时（含范围读取）**：读取链上**每一个被触及的 blob 都整体流式计算 SHA-256** 并与其内容寻址
   文件名比对；范围跨分块时，窗口**开始之前 / 结束之后的字节虽不返回、但仍参与哈希**，因此范围下载的
   完整性校验与整读完全一致。读到损坏 blob 会在流上抛 `422 checksum_mismatch`。

错误原因可区分：

| 场景 | HTTP 状态 | 响应 `error` |
|---|---|---|
| 对象/上传会话/可读版本不存在（含已退役版本） | 404 | `not_found` |
| 分片校验和不匹配 / 对象总校验和不匹配 / 读到损坏 blob | 422 | `checksum_mismatch` |
| 清单缺失、编号不连续、引用了未上传/不一致的分片；Range 头语法非法或含多个区间 | 400 | `invalid_manifest` |
| Range 起点超出对象大小 | 416 | `range_not_satisfiable`（带 `Content-Range: bytes */<size>`） |
| 尝试退役当前版本 | 409 | `version_conflict` |
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
- **读一致性快照（强一致）**：一次读取（无论当前版还是历史版、无论是否带 Range）在服务内部
  **只解析一次版本快照**，并由这同一份 `StoredObject` 元数据去打开它引用的 blob 流；响应头里的
  `X-Object-Version`、校验和与流出的字节**必然属于同一个完整版本**。即使读取进行中恰好有并发发布，
  也不会出现“头是新版、内容是旧版/半截”或内容回退的情况。读取期间这些 blob 由租约保护，不会被
  并发的退役 + GC 删除（见第 4 节）。
- **blob 在途/在读保护**：新版本在“物化 blob → 写版本元数据 → 翻指针”的整个窗口内，其 blob 由
  `BlobLeaseManager` 标记为 pending；一次读取从拿到快照到流关闭持有 lease。GC 永不删除受保护 blob。
- **失败/取消的原子性**：complete 在 blob 物化、版本元数据、指针翻转**全部完成之后**才删除 staging；
  此前任何异常（校验失败、IO 故障、显式 abort）都只影响 staging，已提交的当前版本原样可读。
- **崩溃恢复**：分片先写 `*.tmp`，校验通过才原子改名；JVM 崩溃残留的 `*.tmp` 不构成任何分片或 blob，
  应用重启时自动清扫。客户端可通过状态接口查询已落地分片，对缺失分片续传后完成。

> 说明：锁保证单 JVM（本服务的部署模型）内的写顺序；跨进程的可见性由“临时文件 + 原子 rename +
> 只追加版本号 + 不可变 blob”这些文件系统原语保证。

---

## 4. 读取能力：历史版本与字节范围

### 4.1 版本快照（snapshot）

- `GET /api/objects/{name}` 读当前版；`GET /api/objects/{name}/versions/{v}` 读指定历史版。
- 服务在**一次调用内只解析一次版本元数据**，并直接用这份元数据打开其 blob 序列。返回的元信息
  （响应头 `X-Object-Version`、`X-Checksum-SHA256`、`X-Content-Checksum-SHA256`）与响应体字节
  必然来自**同一版本**，不会被并发发布撕裂或回退。
- 历史版本在被**显式退役**之前一直可读；读取历史版本也支持 Range。

### 4.2 字节范围（Range）

当前版与历史版都支持**单个** `Range: bytes=` 区间（RFC 9110）：

| 写法 | 含义 |
|---|---|
| `bytes=START-END` | 闭区间 `[START, END]`；`END` 超过末尾会被裁剪到最后一个字节 |
| `bytes=START-` | 从 `START` 到对象末尾 |
| `bytes=-N` | 最后 `N` 个字节（后缀） |

- 命中区间返回 **206 Partial Content**，带 `Content-Range: bytes start-end/total`、`Content-Length`
  与 `Accept-Ranges: bytes`；无 Range 头时照常返回 200 全量。
- 仅支持**单个区间**：多区间（含逗号）或非 `bytes` 单位返回 `400 invalid_manifest`；
  起点越界返回 `416 range_not_satisfiable`（带 `Content-Range: bytes */<总大小>`）。
- **跨分块完整性**：范围可能横跨多个 blob。服务对窗口触及的**每个 blob 读取并计算整 blob SHA-256**，
  窗口之外的前缀/后缀字节“只哈希、不下发”。因此部分下载与整读的完整性保证一致，blob 损坏会得到
  `422 checksum_mismatch` 而不是悄悄返回坏数据。

---

## 5. 版本退役与垃圾回收（Retirement & Garbage Collection）

旧版本默认**永不被覆盖、永不自动删除**。要真正释放磁盘，需两步显式操作：

1. **退役（retire）**：`DELETE /api/objects/{name}/versions/{v}`
   - 在该版本的 `vN.json` 旁边原子放置 `vN.retired` 墓碑标记（内容为退役时间戳）；**绝不删除数据本身**。
   - 退役后该版本立即**不可再读**（返回 404，状态列在版本清单中显示为 `RETIRED`），也不再参与
     重复上传匹配；其**独占** blob 才进入可回收候选。
   - **当前版本不允许退役**（返回 `409 version_conflict`）——先上传新版本替换它。
   - `GET /api/objects/{name}/versions` 列出全部版本及状态：`CURRENT`（当前指针）/ `LIVE`
     （历史但可读）/ `RETIRED`（已退役不可读，元数据保留）。
2. **回收（GC）**：`POST /api/maintenance/gc`（可重复调用，返回本轮报告）。

### 5.1 回收规则（哪些能读、哪些不能，回收什么）

GC 先扫描**所有对象**、汇总**所有仍可读版本**（CURRENT + LIVE，即没有 `.retired` 标记的 `vN.json`）
引用的 blob 集合，然后遍历 `blobs/`：

- 被**任意一个可读版本**引用的 blob（哪怕只被一个历史版本、或另一个对象引用）→ **永不删除**（blob 跨版本/跨对象共享）；
- 无任何可读版本引用、但正被**读取租约**或**在途发布**保护的 blob → 本轮跳过（`skippedInUse`）；
- 无引用且其最后修改时间**年轻于宽限窗口** `objectstorage.gc.min-age`（默认 `1m`）→ 本轮跳过
  （`skippedTooYoung`），用于覆盖“物化→翻指针”之间崩溃以及时钟误差；
- 其余无引用、无保护、足够老的 blob → 删除（`deletedBlobs` / `reclaimedBytes`）。

因此：

- **被新版本替换**后，只要旧版本没退役，它仍是 `LIVE`、依旧可读，其 blob 不会被回收；
- **退役旧版本**后，旧版本变 `RETIRED` 不可读；只有**不再被任何可读版本使用**的那部分 blob 会被回收，
  与当前版/其他对象共享的 blob 一定保留；
- 墓碑元数据（`vN.json` + `.retired`）保留，便于说明“这个版本何时退役、现在读不到了”。

### 5.2 安全性质

- **不挡读写**：GC 全程不获取对象锁，与并发上传/发布/读取并行。
- **绝不删在用数据**：引用集合先算后删；在读租约与发布 pending 双重保护 + 宽限窗口兜底。
- **失败可安全重试**：唯一的状态变更就是物理删除；某个 blob 删失败（如 Windows 上文件正被打开）只计数
  （`deletionFailures`）并留到下一轮。重复调用不会删两次，也不会误删——引用集合每轮重新计算。
- 单 JVM 内租约可证明地杜绝“读到一半被删”；POSIX 上即使文件被删，已打开的句柄仍可读完，作为额外兜底。

---

## 6. HTTP API

基础路径：`/api/objects`（GC 在 `/api/maintenance`），对象名为单段安全路径段。

| 方法 | 路径 | 说明 |
|---|---|---|
| POST   | `/{name}/uploads` | 初始化分片上传，返回 `uploadId` |
| PUT    | `/{name}/uploads/{uploadId}/parts/{partNumber}` | 上传一个分片；请求头 `X-Checksum-SHA256` 声明分片 SHA-256；**幂等**，重传已存在且校验和一致的分片返回 `alreadyPresent=true` |
| GET    | `/{name}/uploads/{uploadId}` | 查询已落地分片（用于断点续传） |
| POST   | `/{name}/uploads/{uploadId}/complete` | 提交清单（JSON），校验通过后原子发布版本 |
| DELETE | `/{name}/uploads/{uploadId}` | 取消上传，仅清理 staging，不动已提交版本 |
| GET    | `/{name}` | 读取当前版本（流式；支持 `Range`；响应头带 `X-Object-Version`、`X-Checksum-SHA256`、`X-Content-Checksum-SHA256`） |
| GET    | `/{name}/meta` | 当前版本元数据（JSON） |
| GET    | `/{name}/versions` | 列出全部版本及状态（CURRENT/LIVE/RETIRED） |
| GET    | `/{name}/versions/{version}` | 读取指定历史版本（支持 `Range`） |
| DELETE | `/{name}/versions/{version}` | 退役指定历史版本（当前版返回 409）；放墓碑、数据交给 GC |
| POST   | `/api/maintenance/gc` | 触发一轮非阻塞垃圾回收，返回删除/跳过/失败统计 |

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

## 7. 本地验证方法

### 运行测试（推荐入口）

```bash
mvn test
```

测试使用 JUnit `@TempDir` 作为数据根目录，互不污染。当前共 34 个用例，业务覆盖与日志中可见的判定依据：

- `ChecksumServiceTest`：SHA-256 与已知向量一致、组合校验和确定性且**对顺序敏感**；
- `ObjectStorageServiceTest`（原有，无回归）：
  - 首次发布版本并按字节读回；
  - 相同内容重复上传 → 版本不变、blob 数不增（打印去重判定）；
  - 新版本发布后 v1 仍按字节可读（打印版本判定依据）；
  - 分片校验和错误 → `422` 拒绝；清单引用缺失分片 → `400` 拒绝；对象总校验和不符 → 拒绝且指针停在 v1；
  - 中断后按状态续传、重复分片幂等；abort 不影响已提交版本；
- `ConcurrentWritersTest`（原有，无回归）：8 个写者并发上传**不同内容** + 持续读取者 → 读者永远读到
  某个完整版本、版本号单调不回退、最终恰好 8 版本/24 blob、哨兵内容确定落在 v9；6 个写者并发相同内容
  → 收敛为唯一 v1/2 blob；
- `InterruptionRecoveryTest`（原有，无回归）：分片写到一半崩溃（孤儿 `.tmp`）不产生半成品，重启后续传
  发布字节精确的 v2，v1 依旧可读；
- **`VersionedRangeReadTest`（新）**：
  - v2 存在时历史 v1 仍字节精确可读，返回的元数据就是 v1；
  - Range 的显式区间/开放区间/后缀/越界裁剪；
  - **跨 3 个分块的范围**（`bytes=7-22`）按字节正确，逐 blob 校验；
  - 非法 Range→400、起点越界→416（带 `bytes */size`）；
  - 故意损坏中间 blob，范围读取必须报完整性错误；
- **`ContentDeduplicationTest`（新）**：
  - 同样 30 字节以 `[30]`、`[10,10,10]`、`[3,7,15,5]` 三种切法上传 → 复用同一版本、blob 数不增；
  - 只改 1 字节 → 必发新版本、`deduplicated=false`（打印 `[dedup-decision]`）；
  - A→B→再以不同切法传 A → 复用 v1 且 current 不回退（打印 `[dedup-history]`）；
  - 跨对象相同字节不新增 blob（打印 `[dedup-cross-object]`）；
- **`SnapshotConsistencyTest`（新）**：6 个并发写者 + 全量/范围两类并发读取者，断言每次读取的版本头与
  字节都自洽、窗口字节恰好来自头所指版本（打印 `[snapshot]`）；
- **`GarbageCollectionTest`（新）**：
  - 替换版本后 v1 仍 LIVE 时 GC 不删；退役 v1 后只回收其独占 blob，与 v2 共享的 blob 保留；二次 GC 幂等；
  - 当前版本退役 → 409；
  - 宽限窗口内跳过（`skippedTooYoung`）、持租约跳过（`skippedInUse`）、释放且变老后才删；
  - GC 与读写并发进行，LIVE 的 v1 始终字节可读（打印 `[gc]` / `[gc-concurrency]`）；
- **`ExtendedHttpFeaturesTest`（新，真实 Spring MVC）**：206 + `Content-Range`/`Accept-Ranges` 跨块下载、
  历史版 Range、416 头；版本列表 CURRENT/LIVE、退役当前版 409、退役后 404/RETIRED、GC 端点
  `deletedBlobs=1` 且当前版仍可服务；
- `ObjectStorageControllerTest`（原有，无回归）：端到端生命周期、去重、三类错误码区分、abort 后旧版本可读。

测试日志中的判定依据形如：

```
[version] decision object=… content differs -> publish version=2 manifestChecksum=… contentChecksum=…
[dedup] decision object=… contentChecksum=… -> reuse existing version 1 (possibly different part layout; no new version, no new storage)
[dedup-rechunk] contentChecksum=… reused=v1 across layouts [30], [10x3], [3,7,15,5]; blobs=1
[dedup-history] A=v1 B=v2 A'(re-chunked)->reuse v1; current remains v2; checksum=…
[read-range] cross-chunk bytes=7-22 (3 blobs) length=16 verified per-blob
[read-integrity] corrupted middle blob detected during ranged read: Integrity check failed …
[snapshot] fullReads=… rangeReads=… writers=6 final=v7; every read was one self-consistent version
[gc] reclaimed unreferenced blob checksum=… bytes=…
[gc] pass complete totalBlobs=3 referenced=2 deleted=1 skippedInUse=0 skippedYoung=0 failures=0 reclaimedBytes=…
[retire] object=… version=1 retired; its exclusive blobs are now GC candidates
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

# 5) 读取 / 元数据 / 历史版本
curl -i http://localhost:8080/api/objects/$NAME
curl -s http://localhost:8080/api/objects/$NAME/meta
curl -s http://localhost:8080/api/objects/$NAME/versions/1

# 5a) 字节范围（206 Partial Content + Content-Range；当前版/历史版都支持）
curl -i -H 'Range: bytes=0-4' http://localhost:8080/api/objects/$NAME
curl -i -H 'Range: bytes=-5' http://localhost:8080/api/objects/$NAME/versions/1   # 最后 5 字节
#    起点越界 -> 416，带 Content-Range: bytes */<对象总大小>

# 6) 用相同内容但不同分块再传一次 -> 版本仍是 1，deduplicated=true，blob 数不增

# 7) 发布一个真正不同的新版本后，退役 v1 并回收其独占 blob
curl -s http://localhost:8080/api/objects/$NAME/versions                 # v1=LIVE, v2=CURRENT
curl -s -XDELETE http://localhost:8080/api/objects/$NAME/versions/1      # v1 -> RETIRED（当前版退役会 409）
curl -s -XPOST http://localhost:8080/api/maintenance/gc                 # 返回 deletedBlobs / skipped* / failures
#    退役后 GET .../versions/1 -> 404；共享 blob 不会被删；GC 可重复调用且幂等
```

---

## 8. 代码结构

```
src/main/java/com/github/lisama569/objectstoragejava/
├── Application.java                 # Spring Boot 入口
├── config/
│   ├── StorageProperties.java       # objectstorage.root 等配置
│   └── StorageBootstrap.java        # 启动建目录 + 崩溃临时文件清扫
├── dto/
│   ├── StoredObject.java            # 版本元数据（checksum=清单校验和, contentChecksum=内容指纹）
│   ├── ByteRange.java               # Range 解析/归一化（显式/开放/后缀区间）
│   ├── OpenedObject.java            # 一次读取的“元数据+流”同一快照（AutoCloseable，关流即释放租约）
│   ├── VersionState.java / VersionInfo.java   # CURRENT/LIVE/RETIRED 与版本列表项
│   └── GarbageCollectionReport.java           # 一轮 GC 的删除/跳过/失败统计
├── exception/                       # ChecksumMismatch / InvalidManifest / UploadNotFound
│                                    # RangeNotSatisfiable(416) / VersionConflict(409)
├── service/
│   ├── ChecksumService.java         # 流式 SHA-256、分片校验和组合
│   ├── LockManager.java             # 按对象名分条的 JVM 锁
│   ├── StorageLayout.java           # 磁盘路径布局与安全校验（含 .retired 墓碑路径）
│   ├── BlobLeaseManager.java        # 引用计数式在读/在途 blob 保护（读取租约 + 发布 pending）
│   ├── GarbageCollectorService.java # 引用集合扫描 + 宽限/租约保护 + 可重试的非阻塞回收
│   ├── VerifiedBlobInputStream.java # 范围/全量读取中对每个触及 blob 整体哈希校验
│   └── ObjectStorageService.java    # 核心：分块写入、内容指纹去重、原子版本发布、
│                                    #       快照读取/Range、版本列表/退役
└── web/
    ├── ObjectStorageController.java # 对象 HTTP 入口（含 Range 206、版本列表、退役）
    ├── MaintenanceController.java   # POST /api/maintenance/gc
    └── StorageExceptionHandler.java # 异常 -> 可区分状态码
```
