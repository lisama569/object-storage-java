# 本地对象存储服务（Local Object Storage）

基于 Spring Boot 3 / Java 21 实现的本地对象存储：用**本地磁盘 + 内容寻址（content-addressable storage）**
模拟对象存储后端，通过项目现有的 Spring MVC Web 应用对外提供 HTTP 入口。

设计目标是让同一对象在**并发写入、重复上传、中断恢复**三类场景下的最终状态**确定、可复现、可解释**：

- 读方任意时刻只能读到某个**完整已提交的版本**：整读/历史版/范围读都基于“同一次元数据快照”，
  范围支持 `起-止 / 只给起始 / 从末尾倒数` 三种写法，非法写法与超界分别返回可区分错误（400/416）；
- 版本号**单调递增、绝不回退**，旧版本**永不被覆盖**、始终可读；
- 相同内容以**拼装字节的 SHA-256** 判定，与分块数量/边界无关：重复上传**不产生冗余 blob，也不产生
  新版本**；判定结论（`deduplicated`、`dedupBasis`）真实、可复现；
- 分块校验和不匹配、清单缺失/引用不完整一律**拒绝并返回可区分的错误码**；
- 上传中断后可通过状态接口发现已落地分片，**断点续传**后完成提交；
- 版本淘汰/删除对象后，**不再被任何可读版本引用的 blob** 由两阶段、可重试的 GC 安全回收，
  不阻塞在途读写、绝不删除在用数据。

---

## 1. 存储布局（On-Disk Layout）

数据根目录由配置项 `objectstorage.root` 决定（默认 `./object-storage-data`，也可用环境变量 `OBJECTSTORAGE_ROOT` 覆盖）。

```
<root>/
├── blobs/
│     └── <sha256>.dat                 # 内容寻址、只写一次、天然去重的不可变数据块
├── quarantine/
│     └── <sha256>.dat                 # GC 第一阶段隔离区（至少存活一轮后才可能被物理删除）
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
  "deduplicated" : false,
  "contentChecksum" : "2c26b46b68ffc68ff99b453c1d30413413422d706483bfa0f98a5e886266e7ae",
  "partSizes" : [ 13, 14 ],
  "dedupBasis" : "new content: sha256(assembled bytes)=2c26… differs from current v1 content checksum 9f86…; publish a new version"
}
```

字段说明：

- `checksum`：旧的“分片布局相关”链式校验和，保留用于向后兼容，**不再**作为重复上传判定依据；
- `contentChecksum`：**对拼装后的原始字节**计算的 SHA-256，与分块方式无关，是重复上传的唯一判定依据。由服务端在完成时**自行重算**（不信任客户端）；清单可用可选字段 `contentChecksum` 预声明，不符则 `422` 拒绝。旧版本元数据缺该字段时，服务端会在判定时按其不可变 blob 流式现算；
- `partSizes`：每个引用分片的字节长度，用于跨分块范围读取时定位窗口；旧元数据缺失时按 blob 实际大小推导；
- `dedupBasis`：人类可读的判定说明，明确写出比较了哪两个摘要、结论如何得出，可照着重算复现。

---

## 2. 版本与去重规则

### 版本判定（version decision）

完成上传（complete）时，服务在对象锁内执行如下**确定性判定**：

1. 校验清单：分片必须按 `1..N` 连续编号、无重复、每项都带校验和；
2. 校验引用完整性：清单引用的每个分片都必须在**本上传会话**中存在，且其旁路校验和与清单一致，
   否则返回 `400 invalid_manifest`；
3. 计算对象级链式校验和：`objectChecksum = SHA256( concat(part1Hex, part2Hex, …) )`
   （每个分片校验和是定长 64 位小写十六进制，按顺序拼接无歧义；**顺序不同即不同对象**），
   与清单声称值不一致则返回 `422 checksum_mismatch`；
4. 由服务端**重新流式读取本次会话的分片字节**，计算**与分块方式无关**的内容摘要
   `contentChecksum = SHA256(原始拼装字节)`。若清单带了可选的 `contentChecksum` 声明，先与之核对，
   不符同样 `422`；
5. 与当前版本比较（**只比内容摘要，不比分块**）：
   - 若**当前版本的内容摘要与本次相同**（旧版本没有该字段时，按其 blob 现算）→
     **不产生新版本、不新增存储**，复用当前版本号，响应 `deduplicated=true`，并在 `dedupBasis`
     中写明比较的两个摘要；
   - 否则 → 物化所需 blob，分配 `当前最大版本号 + 1`，原子发布新版本，`deduplicated=false`。

因此：

- **相同内容重复上传（哪怕重新分块、分片数/大小不同）**：版本号不变、不新增 blob；
- **不同内容上传**：版本号严格 +1；旧的 `vN.json` 与旧 blob 永不修改、永不删除，历史版本一直可读；
- 把历史内容再次上传为“相对当前是新的内容”时，会发布一个**向前的新版本号**（指针绝不回退），
  但因字节内容寻址，仍复用历史那份 blob，不重复占盘。

> 说明：`deduplicated` 表示“本次完成是否复用了已有版本”，与“blob 是否因内容寻址被复用”是两回事——
> 一个新发布的版本即便全部 blob 都已存在，`deduplicated` 仍为 `false`。

### 读取：历史版本与字节范围（range reads）

读取在**一次元数据快照 + 一把读租约**内完成：先确定“读哪个版本”，再打开该版本引用的 blob，
所以读到的版本号（响应头 `X-Object-Version`）与字节必然属于**同一个完整版本**；读取进行期间即使
有并发写入/发布/GC，也不会出现版本号与内容对不上、半截内容或读回旧内容。

- `GET /{name}`：读当前版本；`GET /{name}/versions/{v}`：读指定历史版本。
- 两种读法都支持 HTTP `Range` 头，接受**单段** `bytes=` 的三种常见写法：

| 写法 | 含义 | 示例（对象长 100） |
|---|---|---|
| `bytes=START-END` | 闭区间窗口；`END` 超过末尾时收敛到最后一个字节 | `bytes=10-19`、`bytes=90-500` → 90..99 |
| `bytes=START-` | 从 START 到对象末尾 | `bytes=95-` → 95..99 |
| `bytes=-SUFFIX` | 倒数 SUFFIX 个字节（超过对象长则返回整对象） | `bytes=-10` → 90..99 |

范围命中时返回 `206 Partial Content`，带 `Content-Range: bytes START-END/总长度`、`Content-Length`
（窗口字节数）与 `Accept-Ranges: bytes`。窗口可以**横跨多个分片**：服务端按 `partSizes` 计算每个
分片贡献的跳过/读取窗口，并且**对所有被触及的 blob 先各自整体做 SHA-256 校验**（与 blob 文件名
比对），完整性校验不因范围读取或跨分块而被跳过。

范围相关错误与既有错误码风格一致、可区分：

| 场景 | HTTP | 响应 `error` |
|---|---|---|
| Range 语法不合法（前缀错误、非数字、`end<start`、多段范围等） | 400 | `invalid_range` |
| Range 合法但该版本无法满足（`START >= size`、空对象取范围） | 416 | `range_not_satisfiable`（带 `Content-Range: bytes */*`） |

这两种情况都**不会**默默返回空体，也**不会**退化成吐整个对象。

### 版本淘汰与存储回收（prune & garbage collection）

- `DELETE /{name}/versions?retain=K`：只保留最新 K 个版本（当前版本恒在保留集内），删除更老的
  版本**元数据文件**。返回里明确给出 `retainedVersions`（仍可读）与 `removedVersions`（此后读
  `versions/{v}` 返回 404）。此操作**不删 blob**，只改变“谁还引用这些 blob”。
- `DELETE /{name}`：删除整个对象（全部版本元数据 + current 指针 + 残留 staging），其 blob 成为
  GC 候选。
- `POST /gc/run`（可选 `minAgeMillis`，默认 60000）：执行一次**安全、可重试**的回收，两阶段：
  1. **识别 + 隔离（quarantine）**：在 `ReadGate` 独占段内重新枚举**所有仍可读版本**引用的 blob，
     把“不被任何可读版本引用、且失联超过 `minAgeMillis`”的 blob 原子地从 `blobs/` 移到
     `quarantine/`（只移动、不删字节）；
  2. **清扫（sweep）**：下一轮 GC 才会把隔离区里“仍未被重新引用、且已存活至少一整轮”的文件物理
     删除。**本轮刚隔离的本轮绝不删除**，因此隔离与删除永远发生在不同轮次，崩溃/重试都安全。

安全保证：

- 读者与发布者在整个读/发布期间持有**读租约**；GC 的独占段会等待所有在途租约释放，因此**绝不会**
  把正在读取或刚发布版本引用的 blob 当垃圾删掉；租约不绑定线程，异步流式响应在其流式线程关闭流时
  释放也安全；
- blob 被隔离后若又有上传物化了相同内容，发布过程会把它从 `quarantine/` **还原**回 `blobs/`，
  该文件随后不会被清扫；
- 每一步都幂等、可重复：重跑 GC 只会处理“此刻仍符合条件”的 blob，不会多删、重复删或删错；
- 元数据无法解析时**直接中止本轮 GC**（fail-safe），而不是冒险按不完整引用集删除。

### 完整性校验（三道关）

1. **上传分片时**：流式计算 SHA-256，与 `X-Checksum-SHA256` 头比对，不匹配则丢弃临时文件并拒绝；
2. **完成清单时**：校验分片编号连续性、引用完整性、分片旁路校验和；
3. **物化 blob 前**：再次对流式分片计算 SHA-256，确认与目标 blob 名一致后才原子落盘。

错误原因可区分：

| 场景 | HTTP 状态 | 响应 `error` |
|---|---|---|
| 对象/上传会话/历史版本不存在 | 404 | `not_found` |
| 分片校验和不匹配 / 对象总校验和不匹配 / 内容摘要不匹配 | 422 | `checksum_mismatch` |
| 清单缺失、编号不连续、引用了未上传/不一致的分片 | 400 | `invalid_manifest` |
| Range 头语法不合法（含多段范围） | 400 | `invalid_range` |
| Range 合法但超出该版本字节数 / 空对象取范围 | 416 | `range_not_satisfiable` |
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
- **读一致性快照**：每次读取（整读或范围读、当前版或历史版）先获取一把**读租约**，再在同一次
  解析里拿到“版本元数据 + 该版本 blob 流”。因此 `X-Object-Version`/`X-Checksum-SHA256` 头与响应体
  必然来自同一个完整版本；读取期间并发发布新版本，只会让“下一次”读取读到新版本，绝不撕裂当前读取。
  跨分块范围读取时，被触及的每个 blob 都先整体校验 SHA-256，完整性不跳过。
- **读 / GC 互斥（ReadGate）**：GC 隔离 blob 前先拿独占许可，等待所有在途读租约与发布租约释放；
  反过来读/发布在 GC 独占期间会短暂等待。租约是**计数式、不绑线程**的，异步 servlet 流可在任意
  线程关闭时释放。GC 因此不会挡住读写太久（独占段只做元数据枚举与原子改名），更不会删掉在用数据。
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
| GET    | `/{name}` | 读取当前版本（流式；响应头带 `X-Object-Version`、`X-Checksum-SHA256`、`Accept-Ranges: bytes`）；带 `Range` 头时返回 `206` 窗口 |
| GET    | `/{name}/meta` | 当前版本元数据（JSON） |
| GET    | `/{name}/versions` | 列出全部已提交版本（版本号升序） |
| GET    | `/{name}/versions/{version}` | 读取指定历史版本；同样支持 `Range` |
| DELETE | `/{name}/versions?retain=K` | 淘汰旧版本，仅保留最新 K 个（当前恒保留）；不删 blob |
| DELETE | `/{name}` | 删除整个对象（版本元数据 + 指针），blob 交给 GC |
| POST   | `/gc/run?minAgeMillis=` | 执行一次安全、可重试的垃圾回收（两阶段；`minAgeMillis` 可选，默认 60000） |

完成清单请求体：

```json
{
  "checksum": "<对象级 SHA-256：SHA256(各分片校验和十六进制按序拼接)>",
  "parts": [
    { "partNumber": 1, "checksum": "<part1 sha256>" },
    { "partNumber": 2, "checksum": "<part2 sha256>" }
  ],
  "contentChecksum": "<可选：对拼装后原始字节的 SHA-256，与分块方式无关；服务端无论是否提供都会自行重算>"
}
```

> `contentChecksum` 不传也能完成：服务端始终从本次会话的分片字节自行重算并写入版本元数据，
> 重复判定以服务端重算结果为准；传了则多一道客户端声明校验（不符 → 422）。

> 分片 PUT 的请求体必须以 `Content-Type: application/octet-stream`（或其他非
> `application/x-www-form-urlencoded` 的类型）发送；表单编码类型会让 Servlet 容器先消费 body。

---

## 5. 本地验证方法

### 运行测试（推荐入口）

```bash
mvn test
```

测试使用 JUnit `@TempDir` 作为数据根目录，互不污染。各块业务与可在日志中看到的判定依据：

- `ChecksumServiceTest`：SHA-256 与已知向量一致、组合校验和确定性且**对顺序敏感**；
- `ByteRangeTest`：三种范围写法（起-止、只给起始、倒数后缀）、`end` 越界收敛、后缀超长按整对象；
  语法不合法 → `invalid_range`（400）与超出字节数 → `range_not_satisfiable`（416）逐例打印；
- `ReadPipelineTest`：
  - 当前版/历史版整读字节精确；
  - 范围横跨 2 个、3 个分片时窗口精确（日志标注“both blobs verified”，完整性不跳过）；
  - 历史版本范围读、400/416/404 区分（绝不返回空或整对象）；
  - 发布者并发发布 v2 期间，读者的每次整读/范围读都与其**自身快照版本**字节一致；
- `DeduplicationTest`：
  - 同一份字节以 3 种不同分块（3 块 / 1 块 / 2 块）上传 → 始终 v1、blob 数不增（打印
    `[dedup-rechunk]` 与 `dedupBasis`，展示“布局校验和变了但 contentChecksum 相等”）；
  - 内容真变 → 新版本、`deduplicated=false`；历史内容重新成为当前 → 发向前新版本但共享 blob；
  - 客户端谎报 `contentChecksum` → 422 拒绝、不发布；
  - 旧版本元数据缺 `contentChecksum` 字段 → 服务端按 blob 现算，仍能正确判重（`derived …`）；
- `GarbageCollectionTest`：
  - 淘汰后可读性明确（保留集可读、移除集 404）、跨版本**共享 blob 不被回收**；
  - 孤儿 blob 第一轮隔离（`quarantined=1`）、第二轮才删除（`deleted=1`），第三轮及之后不再多删，
    验证两阶段与重试幂等；删除整个对象后其 blob 全部回收；
  - 4 个读者在 50 轮 GC 下持续读当前版本，始终拿到完整字节（`[gc-race]`，GC 等待在途读租约）；
- `RangeAndLifecycleControllerTest`（HTTP 端到端）：历史/当前 `Range` → 206 + `Content-Range`；
  400/416 区分；版本列表、淘汰、`/gc/run` 两遍、删除对象 404；
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
[range] bytes=2-5 of 100 -> ByteRange[start=2, end=5] length=4
[range-reject] bytes=100-200 of 100 -> 416: not satisfiable ...
[range-read] bytes=7-15 across parts -> '789ABCDEF' version=v1 (both blobs verified)
[dedup-rechunk] v1 reused; chained layout checksum changed <a> -> <b> but contentChecksum=<c> stayed equal
[dedup-rechunk] basis: duplicate: sha256(assembled bytes)=<c> equals current v1 content checksum <c> ...
[dedup-differs] new content: sha256(assembled bytes)=<c2> differs from current v1 content checksum <c1> ...
[dedup-legacy] duplicate: ... (derived on the fly from the old version's immutable blobs ...)
[prune] retained=[2, 3] removed=[1] -> readable={v2,v3}, unreadable={v1}
[gc] phase1: quarantined=1, blobs/ now holds only live=1
[gc] phase2+sweep: orphan deleted; retries delete nothing extra (retry-safe, idempotent)
[gc-race] GC waited for in-flight reads; current v2 always complete; only unreferenced v1 blobs reclaimed
[concurrent] writer=3 published-or-reused version=4 checksum=…
[concurrent] FINAL decision: current=v9 checksum=… (deterministic sentinel)
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

# 5b) 范围读取（三种写法），返回 206 + Content-Range: bytes a-b/总长度
curl -i -H 'Range: bytes=2-6'   http://localhost:8080/api/objects/$NAME             # 起+止（可跨分片）
curl -i -H 'Range: bytes=7-'    http://localhost:8080/api/objects/$NAME             # 只给起始
curl -i -H 'Range: bytes=-4'    http://localhost:8080/api/objects/$NAME/versions/1  # 从末尾倒数
# 非法写法 -> 400 invalid_range；超出字节数 -> 416 range_not_satisfiable
curl -i -H 'Range: bytes=9999-' http://localhost:8080/api/objects/$NAME

# 6) 用相同内容再传一次（即使重新分块）-> 版本仍是 1，deduplicated=true

# 7) 淘汰历史版本（只保留最新 1 个）+ 两阶段垃圾回收
curl -s -XDELETE "http://localhost:8080/api/objects/$NAME/versions?retain=1"
curl -s -XPOST "http://localhost:8080/api/objects/gc/run?minAgeMillis=0"  # 第一轮：隔离孤儿 blob
curl -s -XPOST "http://localhost:8080/api/objects/gc/run?minAgeMillis=0"  # 第二轮：物理删除
```

---

## 6. 代码结构

```
src/main/java/com/github/lisama569/objectstoragejava/
├── Application.java                 # Spring Boot 入口
├── config/
│   ├── StorageProperties.java       # objectstorage.root 配置
│   └── StorageBootstrap.java        # 启动建目录 + 崩溃临时文件清扫
├── dto/                             # 请求/响应记录（Manifest/StoredObject/RangeReadResult/GcReport/PruneResult …）
├── exception/                       # ChecksumMismatch / InvalidManifest / InvalidRange(400)
│                                    #   / RangeNotSatisfiable(416) / UploadNotFound …
├── service/
│   ├── ChecksumService.java         # 流式 SHA-256、分片校验和组合、读校验
│   ├── LockManager.java             # 按对象名分条的 JVM 锁（写串行化）
│   ├── ReadGate.java                # 计数式跨线程读租约 + GC 独占段（读/发布 与 GC 互斥）
│   ├── ByteRange.java               # bytes= 三种写法解析；400 与 416 分开抛
│   ├── StorageLayout.java           # 磁盘路径布局（blobs/ quarantine/ objects/ staging/）与安全校验
│   ├── ObjectStorageService.java    # 核心：分块写入、内容级去重、原子版本发布、
│   │                                #   快照一致读取/跨分块范围读、淘汰、删除、存活引用枚举
│   └── StorageReclamationService.java # 两阶段（隔离→下一轮清扫）、可重试的 blob 回收
└── web/
    ├── ObjectStorageController.java # HTTP 入口（Range/版本列表/淘汰/删除/GC）
    └── StorageExceptionHandler.java # 异常 -> 可区分状态码（含 400 invalid_range、416）
```
