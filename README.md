# 本地对象存储服务（Local Object Storage）

基于 Spring Boot 3 / Java 21 实现的本地对象存储：用**本地磁盘 + 内容寻址（content-addressable storage）**
模拟对象存储后端，通过项目现有的 Spring MVC Web 应用对外提供 HTTP 入口。

设计目标是让同一对象在**并发写入、重复上传、中断恢复、按版本/范围读取、存储回收**五类场景下的最终状态**确定、可复现、可解释**：

- 读方任意时刻只能读到某个**完整已提交的版本**，一次读取返回的版本号与字节必然来自同一个版本快照；读不到写一半的内容；
- 支持读取**历史版本**与按字节 **Range（范围）**读取（`起-止`、`起-`、`-尾长`三种写法）；
- 范围非法或超出该版本字节数时返回**可区分的错误**（416），不会默默返回空、也不会退化成整个对象；
- 版本号**单调递增、绝不回退**，旧版本**永不被覆盖**、在未被主动淘汰前始终可读；
- 相同**字节内容**（与分块数量、每块大小无关）**不产生冗余 blob，也不产生新版本**，且“是否重复”的结论有明确可复现的判定依据；
- 分块校验和不匹配、清单缺失/引用不完整一律**拒绝并返回可区分的错误码**；
- 上传中断后可通过状态接口发现已落地分片，**断点续传**后完成提交；
- 被新版本替换或主动淘汰的版本所独占的 blob 可通过**安全的两阶段垃圾回收**释放，回收不挡读写、不删在用数据、失败重试不多删。

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
├── quarantine/
│     └── <sha256>.dat.q<ts>-<n>       # GC 两阶段隔离区（在 blobs/ 之外，不会被算成 blob）
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
  "contentChecksum" : "9f2f4a3c…(对组装后的原始字节计算的 SHA-256，与分块无关)",
  "partSizes" : [ 13, 14 ],
  "dedupBasis" : "new version: assembled SHA-256 9f2f4a3c… (27 bytes) differs from current v1 (…)"
}
```

字段说明：

- `checksum`：**分块布局相关**的对象校验和 `SHA256(concat(partHex...))`，历史数据沿用不变；
- `contentChecksum`：对**组装后的完整字节**计算的 SHA-256，与这次上传怎么切分块无关，是重复上传判定的依据；
- `partSizes`：每个引用分片的字节长度，用于跨分块范围读取的偏移计算；
- `deduplicated` / `dedupBasis`：本次完成是否判定为重复，以及一句可复现的判定说明。

---

## 2. 版本与去重规则

### 版本判定（version decision）

完成上传（complete）时，服务在对象锁内执行如下**确定性判定**：

1. 校验清单：分片必须按 `1..N` 连续编号、无重复、每项都带校验和；
2. 校验引用完整性：清单引用的每个分片都必须在**本上传会话**中存在，且其旁路校验和与清单一致，
   否则返回 `400 invalid_manifest`；
3. 计算对象级（分块布局相关）校验和：`objectChecksum = SHA256( concat(part1Hex, part2Hex, …) )`
   （每个分片校验和是定长 64 位小写十六进制，按顺序拼接无歧义；**顺序不同即不同对象**），
   与清单声称值不一致则返回 `422 checksum_mismatch`；
4. 计算**内容校验和** `contentChecksum = SHA256(组装后的完整字节)`——直接把各分片文件按序流式拼接后
   取摘要，与分块数量、每块大小完全无关。清单若携带可选的 `contentChecksum`，服务会强校验，不符返回
   `422 checksum_mismatch`；不携带则以服务端计算值为准；
5. 与当前版本比较（**只看字节内容**）：
   - 若 `size 相等 且 contentChecksum 相同` → **不产生新版本、不新增存储**，复用当前版本号及其 blob，
     响应中 `deduplicated=true`，`dedupBasis` 写明依据；
   - 否则 → 物化所需 blob，分配 `当前最大版本号 + 1`，原子发布新版本，`deduplicated=false`。

因此：

- **相同内容、任意重新分块重复上传**：版本号不变、blob 不重复（无论这次切成 1 块还是 100 块、
  每块大小与上次是否一致）。判定只依赖组装字节的 SHA-256 与长度，可离线复现：
  `sha256(完整文件字节)` 相同即重复；
- **内容真的变了**（哪怕只差 1 个 bit、长度相同）：`contentChecksum` 不同 → 版本严格 +1，绝不会误判重复；
- **不同内容上传**：版本号严格 +1；旧的 `vN.json` 与旧 blob 永不修改、永不删除（直到主动淘汰 + GC），
  历史版本在被淘汰前一直可读。

### 读取与范围（reads and byte ranges）

- **读取的是一个不可变版本快照**：一次读取先在共享读闸（read gate）下解析出**唯一一份**版本元数据，
  随后流出的每个字节都来自该版本引用的 blob。读取过程中即使有并发写入/发布新版本：
  响应头里的 `X-Object-Version` 与字节必然属于同一版本，不会出现版本号与内容对不上、半截内容、
  或反而读回旧内容；新版本只影响之后发起的读取。
- **整读校验**：流式读取时对**每一个**引用 blob 重新计算 SHA-256 并与其内容寻址名比对，
  blob 被篡改则读取以 `checksum_mismatch` 失败，而不是返回错误字节。
- **范围读取**：`Range: bytes=…` 支持单段范围，偏移零基、跨分片按拼接后的全局字节计算：

  | 写法 | 含义 | 例（对象长 100 字节） |
  |---|---|---|
  | `bytes=START-END` | 闭区间 `[START, END]` | `bytes=10-20` → 11 字节 |
  | `bytes=START-` | 从 START 到最后一个字节 | `bytes=90-` → 10 字节 |
  | `bytes=-N` | 末尾 N 个字节（N>对象长时从 0 开始） | `bytes=-25` → 25 字节 |

  - 合法范围返回 `206 Partial Content`，带 `Content-Range: bytes START-END/总长度`、`Accept-Ranges: bytes`、
    `Content-Length`（=范围字节数）以及版本/校验和响应头；当前版本与历史版本端点都接受 `Range`。
  - 范围语法非法（不是 `bytes=`、多段、非数字、负数、`end<start`、空规格等）返回
    **416 `invalid_range`**；范围起点/终点超出该版本字节数、空对象上带范围、`bytes=-0` 同样返回
    **416 `invalid_range`**。服务端**不做**“夹取成空”或“退化为整个对象”的静默处理。
  - **跨分块完整性不跳过**：即使只取某个分片中间的一小段，该范围触及的**每一个分片都会整体读全并
    校验 SHA-256**（被跳过的前缀/尾部也计入摘要，在流关闭时 drain 校验），任何一块损坏都会让这次
    范围读取失败。

### 完整性校验（三道关）

1. **上传分片时**：流式计算 SHA-256，与 `X-Checksum-SHA256` 头比对，不匹配则丢弃临时文件并拒绝；
2. **完成清单时**：校验分片编号连续性、引用完整性、分片旁路校验和；
3. **物化 blob 前**：再次对流式分片计算 SHA-256，确认与目标 blob 名一致后才原子落盘。

错误原因可区分：

| 场景 | HTTP 状态 | 响应 `error` |
|---|---|---|
| 对象/上传会话/历史版本不存在 | 404 | `not_found` |
| 分片校验和不匹配 / 对象总校验和不匹配 / 内容校验和不匹配 / 读取时 blob 校验失败 | 422 | `checksum_mismatch` |
| 清单缺失、编号不连续、引用了未上传/不一致的分片 | 400 | `invalid_manifest` |
| Range 语法非法、范围超出版本字节数、空对象带范围 | 416 | `invalid_range` |
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
- **读一致性快照（与读闸）**：服务维护一个与线程无关的读者/回收者门控 `ReadGate`。
  每次读取（含范围读、历史版本读）在**打开流之前**获取一个读许可、在**流关闭时**释放；
  版本元数据只解析一次，流内字节全部来自该快照。许可可以跨异步 MVC 的请求线程/流式输出线程
  传递，因此在整个流式传输期间都受保护。垃圾回收运行时需要独占：一旦 GC 开始等待，新读许可不再
  发放，已发放的许可全部归还后 GC 才动手——GC 绝不会移动任何正在被读取的 blob；反过来 GC 也不会
  被持续流量饿死（公平等待）。
- **失败/取消的原子性**：complete 在 blob 物化、版本元数据、指针翻转**全部完成之后**才删除 staging；
  此前任何异常（校验失败、IO 故障、显式 abort）都只影响 staging，已提交的当前版本原样可读。
- **崩溃恢复**：分片先写 `*.tmp`，校验通过才原子改名；JVM 崩溃残留的 `*.tmp` 不构成任何分片或 blob，
  应用重启时自动清扫。客户端可通过状态接口查询已落地分片，对缺失分片续传后完成。

> 说明：对象锁保证单 JVM（本服务的部署模型）内同对象的写顺序；`ReadGate` 协调“读/发布”与“回收”；
> 跨进程的可见性由“临时文件 + 原子 rename + 只追加版本号 + 不可变 blob”这些文件系统原语保证。

---

## 3a. 版本淘汰与存储回收（Prune & Garbage Collection）

新版本发布或上传被删除后，旧 blob 不会被立即删除——可能有历史版本或其它对象仍在引用。回收分两步，
且都设计为**可重试、不删在用数据**：

1. **版本淘汰（prune，只动元数据）**：`POST /api/objects/{name}/prune?retain=N`
   保留该对象最新的 N 个版本（当前版本永远在保留窗口内，且禁止 `retain<1`），删除其余**版本元数据
   文件**。响应明确给出：
   - `retainedVersions`：淘汰后**仍然可读**的版本号；
   - `removedVersions`：已不可读（再读返回 404）的版本号；
   - `currentVersion`：当前指针版本。
   此步**不删任何 blob**——blob 可能被保留的版本、其它对象共享引用。
2. **垃圾回收（GC，两阶段回收 blob）**：`POST /api/objects/admin/gc[?minAgeMillis=…]`
   一次完整 pass 在 `ReadGate` 独占锁内执行（期间没有读者/发布者在动 blob，文件移动不会挡在读写中间，
   只是等待打开的流关闭）：
   - **活引用扫描**：枚举所有 `objects/*/versions/vN.json`，取其 `partChecksums` 的并集作为“存活集”。
     只要有任何一个还能读到的版本（任意对象）引用某 blob，它就是活的；
   - **阶段 A 隔离（只 rename，绝不 unlink）**：`blobs/` 中不在存活集、且 mtime 距今超过
     `minAgeMillis`（默认 10 秒宽限期；测试/手动可传 0 立即处理）的候选，原子移动到
     `quarantine/<sha>.dat.q<时间戳>-<序号>`。崩溃只可能留下“已隔离但未删除”的文件，绝无“已删但还被
     引用”的窗口；
   - **阶段 B 对账与清扫**：每个隔离项再次对照存活集——若该 checksum 又被引用（例如崩溃前的旧 pass
     误隔离、或宽限期内又有相同内容发布），则**还原**回 `blobs/<sha>.dat`；确认无引用且满足年龄条件
     才真正删除。隔离文件名带 checksum，任何阶段崩溃后重跑都能对账恢复。

安全与重试保证：

- **不挡正在进行的读写**：GC 只在独占点等待打开的流关闭；读者持有 blob 期间 blob 不会被移动；
- **不删在用数据**：活引用集跨所有对象，跨对象共享的 blob 在任一引用存活期间绝不删除；
- **失败重试不多删/删错**：隔离不是删除；重复执行 GC，已删除的不会重复计数、被重新引用的会被还原，
  报告里的 `quarantined/deleted/restored` 在第二次幂等 pass 上均为 0（没有新工作）；
- 删除整个对象 `DELETE /api/objects/{name}` 同样只删元数据/指针/staging，其 blob 交给下一次 GC。

返回的 `GcReport` 字段：`blobsExamined`（检查的 blob 数）、`liveReferences`（存活引用数）、
`candidatesIdentified`、`quarantined`（阶段 A 隔离数）、`restored`（对账还原数）、
`deleted`（实际删除数）、`staleQuarantineSwept`（清理上轮遗留隔离项数）、`durationMillis`。

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
| GET    | `/{name}` | 读取当前版本（流式；支持 `Range`；响应头带 `X-Object-Version`、`X-Checksum-SHA256`、`X-Content-Checksum-SHA256`） |
| GET    | `/{name}/meta` | 当前版本元数据（JSON） |
| GET    | `/{name}/versions` | 列出全部已提交版本（版本号升序） |
| GET    | `/{name}/versions/{version}` | 读取指定历史版本（支持 `Range`） |
| POST   | `/{name}/prune?retain=N` | 淘汰历史版本，保留最新 N 个（当前版本必保留），只删元数据 |
| DELETE | `/{name}` | 删除整个对象（元数据+指针+staging），blob 交 GC |
| POST   | `/admin/gc[?minAgeMillis=]` | 执行一次安全、可重试的 blob 回收 pass，返回 `GcReport` |

读取响应：无 `Range` 时 `200` + `Content-Length: 全量字节数`；有合法范围时
`206` + `Content-Range: bytes START-END/总长` + `Content-Length: 范围字节数` + `Accept-Ranges: bytes`；
范围非法/不满足时 `416 {"error":"invalid_range", …}`。

完成清单请求体：

```json
{
  "checksum": "<对象级 SHA-256：SHA256(各分片校验和十六进制按序拼接)>",
  "contentChecksum": "<可选：对组装后完整字节的 SHA-256，与分块无关；携带则强校验>",
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
- `ByteRangeTest`：三种 Range 写法（`起-止`/`起-`/`-尾长`）解析正确；语法非法与超界各自可区分；
- `RangeReadTest`（读取恢复与新增读取能力）：
  - 当前版/历史版整读返回完整正确字节；历史版缺失、对象不存在 → 404；
  - 三种范围写法、跨 2~3 个分块边界的范围精确；`RangeReadResult` 携带同一版本快照；
  - 范围非法/超界 → `InvalidRangeException`（不返回空、不吐整个对象）；
  - **跨分块完整性**：篡改范围内触及的某块 → 读取在关闭时以 `checksum_mismatch` 失败；
    篡改整读的最后一块同样失败；恢复 blob 后读取恢复正常；
  - 钉住 v1 快照后并发发布 v3，快照字节与版本号仍是 v1，新读才看到 v3；
- `ContentDedupTest`（重复上传判定口径）：
  - 同一批字节分别切成 1/3/7 块上传三次 → 始终复用 v1、blob 数不增、`deduplicated=true`，
    日志打印每轮的 `dedupBasis`（含组装字节 SHA-256）；
  - 只改 1 bit（等长）→ 发布 v2、`deduplicated=false`、`dedupBasis` 写明 differs；
  - 清单谎报 `contentChecksum` → `422 checksum_mismatch`（结论必须真实）；
- `GcReclamationTest`（淘汰与回收）：
  - prune 的 retained/removed/current 与“谁还能读、谁已 404”完全一致；
  - GC 只回收无任何可读版本引用的 blob，第二次 pass 幂等（不多删）；
  - 跨对象共享 blob 在另一对象仍引用时绝不删；
  - **读流打开时 GC 被读闸挡住**，流关闭后才回收；删除对象后其 blob 被回收、存活对象字节不变；
- `ConcurrentReadWriteTest`：6 个写者持续发布期间，整读读者与“历史版本+范围”读者的每次结果
  版本号与字节都来自同一完整版本（日志打印通过的快照/范围校验次数与样例）；
- `ObjectStorageServiceTest`：
  - 首次发布版本并按字节读回；
  - 相同内容重复上传 → 版本不变、blob 数不增（打印去重判定）；
  - 新版本发布后 v1 仍按字节可读（打印版本判定依据）；
  - 分片校验和错误 → `422` 拒绝；清单引用缺失分片 → `400` 拒绝；对象总校验和不符 → 拒绝且指针停在 v1；
  - 中断后按状态续传、重复分片幂等；abort 不影响已提交版本；
- `ConcurrentWritersTest`（原有并发用例，保持全绿）：8 个写者并发上传**不同内容** + 1 个持续读取者，断言
  - 读者读到的永远是某个完整版本（按版本元数据快照校验字节）、观察到的版本号单调不回退；
  - 最终产生恰好 8 个版本、24 个唯一分片 = 24 个 blob；
  - 再顺序写入一个“哨兵”内容，断言**最终版本确定为 v9**、字节确定（可复现）；
  - 6 个写者并发上传**相同内容** → 收敛为唯一 v1、仅 2 个 blob；
  - 日志打印每个写者的版本判定、最终 `FINAL decision`、blob 去重数与读取者版本轨迹；
- `InterruptionRecoveryTest`（原中断恢复用例，保持全绿）：模拟分片 2 写到一半崩溃（残留孤儿 `.tmp`），断言
  - 无半成品 blob/版本、当前仍为 v1 且字节不变；
  - “重启”后的新服务实例只识别已完整提交的分片，续传后发布字节精确的 v2，且 v1 依旧可读；
- `ObjectStorageControllerTest`：经真实 Spring MVC 的端到端生命周期、去重、四类错误码区分
  （含 416 `invalid_range`）、abort 后旧版本仍可读；206 范围响应（`Content-Range`/三种写法）、
  版本列表、prune、GC（含第二次幂等）全链路。

**“版本判定”和“是否重复上传”两个结论怎么算出来的**（日志与响应字段都可直接核对）：

- 版本判定：`新版本号 = versions/ 目录里最大编号 + 1`（对象锁内计算，只增不退）；是否走到发布取决于
  下面的重复判定；
- 重复判定：服务端对**按序拼接后的完整字节**计算 SHA-256，并与当前版本比较
  `size 相等 && contentChecksum 相等`。成立即复用当前版本（`deduplicated=true`），否则发布新版本
  （`deduplicated=false`）。响应里的 `dedupBasis` 与日志 `Duplicate upload …` /
  `Published version …` 会把两个 contentChecksum、字节数和结论逐字打印，可离线用
  `sha256sum 完整文件` 复现。

测试日志中的判定依据形如：

```
[range] forms accepted: ByteRange[start=10, end=20] / ByteRange[start=90, end=99] / ByteRange[start=75, end=99]
[read] tampered part inside range rejected: Read-time verification failed for part 3 ...
[dedup-chunk] round=2 chunkCount=7 decision=duplicate version=1 basis=duplicate: SHA-256 over assembled 53 bytes is … identical to current v1; ...
[dedup-chunk] decision=new version: new version: assembled SHA-256 … differs from current v1 (…)
[gc] prune retain=2: retained=[3, 4] removed=[1, 2] current=v4
[gc] pass1 examined=3 quarantined=2 deleted=2 restored=0 blobs=3->1
[gc] pass2 (retry) examined=1 quarantined=0 deleted=0 restored=0 (idempotent)
[gc] reader open -> blobs=2 retained; GC waits on the read gate
[rwx] 12345 consistent snapshot/range checks; samples=[v1 bytes=10-59, ...]; final current=v6
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
#    内容校验和（对完整字节，与分块无关；可选携带，携带则强校验）
CONTENTCS=$(cat /tmp/p1 /tmp/p2 | sha256sum | cut -d' ' -f1)
curl -s -XPOST -H 'Content-Type: application/json' \
  -d "{\"checksum\":\"$OBJCS\",\"contentChecksum\":\"$CONTENTCS\",\"parts\":[{\"partNumber\":1,\"checksum\":\"$CS1\"},{\"partNumber\":2,\"checksum\":\"$CS2\"}]}" \
  http://localhost:8080/api/objects/$NAME/uploads/$UP/complete

# 5) 读取 / 查看元数据 / 读历史版本
curl -i http://localhost:8080/api/objects/$NAME
curl -s http://localhost:8080/api/objects/$NAME/meta
curl -s http://localhost:8080/api/objects/$NAME/versions/1

# 5b) 范围读取（三种写法；注意 -i 看 206/Content-Range）
curl -i -H 'Range: bytes=2-4'   http://localhost:8080/api/objects/$NAME   # 闭区间
curl -i -H 'Range: bytes=6-'    http://localhost:8080/api/objects/$NAME   # 从偏移 6 到末尾
curl -i -H 'Range: bytes=-3'    http://localhost:8080/api/objects/$NAME   # 最后 3 字节
curl -i -H 'Range: bytes=0-3'   http://localhost:8080/api/objects/$NAME/versions/1  # 历史版本范围
curl -i -H 'Range: bytes=999-1000' http://localhost:8080/api/objects/$NAME          # 超界 -> 416 invalid_range

# 6) 用相同内容但换一种分块再传一次（例如整块作为一个 part）-> 版本仍是 1，deduplicated=true
cat /tmp/p1 /tmp/p2 > /tmp/all
CS_ALL=$(sha256sum /tmp/all | cut -d' ' -f1)
UP2=$(curl -s -XPOST http://localhost:8080/api/objects/$NAME/uploads | sed -E 's/.*"uploadId":"([^"]+)".*/\1/')
curl -s -XPUT -H "X-Checksum-SHA256: $CS_ALL" -H 'Content-Type: application/octet-stream' \
  --data-binary @/tmp/all http://localhost:8080/api/objects/$NAME/uploads/$UP2/parts/1
OBJCS_ALL=$(printf "%s" "$CS_ALL" | sha256sum | cut -d' ' -f1)
curl -s -XPOST -H 'Content-Type: application/json' \
  -d "{\"checksum\":\"$OBJCS_ALL\",\"contentChecksum\":\"$CONTENTCS\",\"parts\":[{\"partNumber\":1,\"checksum\":\"$CS_ALL\"}]}" \
  http://localhost:8080/api/objects/$NAME/uploads/$UP2/complete   # -> "version":1, "deduplicated":true

# 7) 版本列表 / 淘汰历史版本 / 回收 blob
curl -s http://localhost:8080/api/objects/$NAME/versions
curl -s -XPOST 'http://localhost:8080/api/objects/'$NAME'/prune?retain=1'   # 只留最新版本
curl -s -XPOST 'http://localhost:8080/api/objects/admin/gc?minAgeMillis=0' # 隔离并删除无引用 blob
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
│   ├── ByteRange.java               # bytes= 范围解析（三种写法 + 非法/超界区分）
│   ├── ReadGate.java                # 跨线程读许可/GC 独占门控（读者↔回收协调）
│   ├── StorageLayout.java           # 磁盘路径布局与安全校验（含 quarantine 命名/对账）
│   ├── ObjectStorageService.java    # 核心：分块写入、内容级去重、原子版本发布、快照读/范围读、prune/delete
│   └── StorageReclamationService.java # 两阶段（隔离→对账删除）安全可重试 GC
└── web/
    ├── ObjectStorageController.java # HTTP 入口（含 Range、versions、prune、admin/gc）
    └── StorageExceptionHandler.java # 异常 -> 可区分状态码（含 416 invalid_range）
```
