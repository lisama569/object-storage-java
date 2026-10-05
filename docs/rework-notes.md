# 本次返工说明（读取恢复 / 内容级去重 / 安全回收）

本文档记录这一版返工的行为口径与兼容范围。README 是完整使用手册，本篇聚焦“改了什么、怎么判定、
哪些保持兼容”。

## 1. 读取：恢复整读，并补齐历史版本 + 范围读取

- **恢复**：按对象名读最新内容、读指定历史版本重新稳定返回完整正确字节（此前读链路为空实现，
  直接返回 null / 空）。
- **一次读取 = 一个不可变版本快照**：先在 `ReadGate` 读许可下解析一次版本元数据，再只从该版本的
  blob 出字节；响应头 `X-Object-Version` 与 body 永远来自同一版本。读取进行中并发发布新版本，
  不影响这次读取；新版本只影响之后发起的读取。流关闭前读许可不释放，GC 无法回收其 blob。
- **范围读取**（当前版本与历史版本端点都支持，请求头 `Range: bytes=…`，单段）：
  - `bytes=START-END`（闭区间）、`bytes=START-`（到末尾）、`bytes=-N`（末尾 N 字节）；
  - 偏移零基、按“各分片拼接后的全局字节”计数；跨分片范围透明支持；
  - 合法 → `206`，响应头 `Content-Range: bytes START-END/总长`、`Content-Length: 范围字节数`、
    `Accept-Ranges: bytes`；
  - 语法非法或超出该版本字节数 → `416 {"error":"invalid_range"}`，**不**返回空、**不**退化为整对象；
  - 范围触及的每个分片（含被跳过的前缀/尾部字节）都会整体读全并做 SHA-256 校验，跨分块不跳过
    完整性；损坏 → `422 checksum_mismatch`。

## 2. 重复上传：只按字节内容判定，与分块无关

- 判定式：`size 相等 && SHA256(按序拼接后的完整字节) 相等` ⇒ 重复。
- 因此同一份字节，无论这次切成几块、每块多大（哪怕和上次完全不同），都**复用当前版本与已有 blob，
  不新增版本、不新增存储**；staging 在完成后整体清理，不残留 blob。
- 内容真变了（1 bit 也算）⇒ 必定发布新版本，`deduplicated=false`。
- 判定依据同时落在完成响应的 `contentChecksum` / `deduplicated` / `dedupBasis` 字段与服务日志中，
  可用 `sha256sum 完整文件` 离线复现。
- 清单新增**可选**字段 `contentChecksum`：携带时服务端强校验（谎报 → 422）；不携带时以服务端对
  组装字节的计算值为准。
- 老的分块布局相关 `checksum = SHA256(concat(partHex…))` 仍照常校验、照常返回，历史元数据与既有
  接口字段不变。

## 3. 回收：先淘汰版本（元数据），再两阶段回收 blob

- `POST /{name}/prune?retain=N`：只删除被淘汰版本的 `vN.json`；响应明确列出保留（仍可读）与删除
  （再读 404）的版本号；当前版本永远保留，`retain<1` 拒绝。
- `POST /admin/gc?minAgeMillis=…`：在 `ReadGate` 独占锁内做一次
  “活引用扫描 → 隔离（rename 到 quarantine/）→ 对账（重新引用则还原，否则删除）”的 pass。
- 安全性：
  - 活引用 = 所有对象所有仍存在的版本元数据引用的分片并集；跨对象共享 blob 在任一引用存活时不删；
  - 读流打开期间 GC 只等待、不动 blob，不阻塞正常读写原语（不持对象锁做 IO）；
  - 隔离是 rename 不是 delete，崩溃/失败重试不会多删或删错；第二次 GC 对同一批数据零删除（幂等）；
  - 宽限期 `minAgeMillis`（默认 10000ms，手动/测试可传 0）用于避免与刚发布的 blob 竞态。
- 删除对象 `DELETE /{name}` 只删元数据/指针/staging，blob 同样交给 GC。

## 4. 兼容范围

- 既有上传/分片/完成/abort 接口与请求格式保持可用；旧清单（不带 `contentChecksum`）仍被接受；
- 旧版本元数据缺少 `contentChecksum/partSizes` 时，服务端按 blob 现场补齐读取所需信息，不要求迁移；
- 既有错误码（404/400/422/500）语义不变，新增 416 `invalid_range` 专用于范围问题；
- 存储布局仅新增顶层 `quarantine/` 目录（放在 `blobs/` 之外，不影响既有 blob 计数与运维扫描）；
- 原有并发写入、中断恢复用例继续保持全绿；新增读取/去重/回收/并发读写一致性用例见
  `ByteRangeTest`、`RangeReadTest`、`ContentDedupTest`、`GcReclamationTest`、`ConcurrentReadWriteTest`。

## 5. 本地验证

```bash
mvn test                     # 全量单测，日志按 [range]/[read]/[dedup-chunk]/[gc]/[rwx] 分块打印判定依据
mvn spring-boot:run          # 手动验证见 README“启动服务并用 curl 手动验证”
```
