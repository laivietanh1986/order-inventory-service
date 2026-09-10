# Mục 18 — Ba cách sửa lost update trong cùng một project

Tương ứng mục 18 trong [`Lo_trinh_hoc_JPA.md`](../Lo_trinh_hoc_JPA.md), tiếp
tục trực tiếp từ bug đã tái hiện ở [mục 17](17-lost-update.md).

## Mục tiêu

Ba cách sửa độc lập cho đúng một bug, đo trade-off thật giữa chúng:

1. **Optimistic `@Version`** — bắt `OptimisticLockException`, thêm retry loop,
   đo tỉ lệ retry khi tăng lên 50 thread.
2. **Pessimistic `PESSIMISTIC_WRITE`** — xem `SELECT ... FOR UPDATE` trong
   log, đo throughput giảm, thử thêm `NOWAIT` và `SKIP LOCKED`.
3. **Atomic conditional update** —
   `UPDATE inventory SET available = available - :q WHERE product_id = :id
   AND available >= :q`, kiểm tra affected rows.

## File đã tạo

| File | Vai trò |
|---|---|
| [`OptimisticInventory.java`](../src/main/java/com/example/orderinventory/concurrency/OptimisticInventory.java) | Entity RIÊNG có `@Version` (lý do tách riêng — xem phần dưới) |
| [`OptimisticInventoryRepository.java`](../src/main/java/com/example/orderinventory/concurrency/OptimisticInventoryRepository.java) | `JpaRepository` cho entity trên |
| [`OptimisticInventoryReservationService.java`](../src/main/java/com/example/orderinventory/concurrency/OptimisticInventoryReservationService.java) | Cách 1: đọc-trừ-save + retry loop khi va `OptimisticLockingFailureException` |
| [`V13__create_optimistic_inventory_table.sql`](../src/main/resources/db/migration/V13__create_optimistic_inventory_table.sql) | Bảng `optimistic_inventory` (cột `version`) |
| [`InventoryRepository.java`](../src/main/java/com/example/orderinventory/inventory/InventoryRepository.java) | Thêm `findByIdForUpdate`, `findByIdForUpdateNoWait`, `findByIdForUpdateSkipLocked`, `decrementIfAvailable` |
| [`PessimisticInventoryReservationService.java`](../src/main/java/com/example/orderinventory/concurrency/PessimisticInventoryReservationService.java) | Cách 2: `SELECT ... FOR UPDATE` + biến thể NOWAIT/SKIP LOCKED |
| [`AtomicUpdateInventoryReservationService.java`](../src/main/java/com/example/orderinventory/concurrency/AtomicUpdateInventoryReservationService.java) | Cách 3: một câu `UPDATE` atomic duy nhất |
| [`OptimisticLockingFixTest.java`](../src/test/java/com/example/orderinventory/concurrency/OptimisticLockingFixTest.java) | Test cách 1 |
| [`PessimisticLockingFixTest.java`](../src/test/java/com/example/orderinventory/concurrency/PessimisticLockingFixTest.java) | Test cách 2 |
| [`AtomicConditionalUpdateFixTest.java`](../src/test/java/com/example/orderinventory/concurrency/AtomicConditionalUpdateFixTest.java) | Test cách 3 |

Chạy: `mvn test -Dtest=OptimisticLockingFixTest,PessimisticLockingFixTest,AtomicConditionalUpdateFixTest`

## Vì sao cách 1 dùng một entity RIÊNG thay vì thêm `@Version` vào `Inventory`

Thử đầu tiên: thêm thẳng `@Version` vào entity `Inventory` (dùng chung cho cả
3 cách + mục 17). Hậu quả: **test của mục 17 (`LostUpdateConcurrencyTest`) bắt
đầu fail** — không phải vì bug đã hết mà vì Hibernate tự động kiểm tra
`version` cho **mọi** entity có `@Version`, bất kể service có "naive" đến đâu.
Bản naive ở mục 17 vô tình được "sửa" luôn, làm sai mục đích minh hoạ của bài
đó.

Fix: tách thành entity `OptimisticInventory` (bảng `optimistic_inventory`)
riêng biệt hoàn toàn với `Inventory` (bảng `inventory`, dùng cho mục 17 +
cách 2 + cách 3 + mục 19) — đúng theo quy ước đã có sẵn trong project này
(xem `BagFetchFix*`, `*CascadeOrder*`, `BatchSizeOrder`/`SubselectOrder`...):
mỗi kỹ thuật mapping khác nhau cho cùng một khái niệm nghiệp vụ được tách
thành entity riêng, để annotation của kỹ thuật này không vô tình ảnh hưởng
tới kỹ thuật khác.

## Cách 1 — Optimistic locking (`@Version` + retry loop)

```java
@Version
private Long version;
```

```java
public int decrementWithRetry(Long inventoryId, int maxAttempts) {
    int attempts = 0;
    while (true) {
        attempts++;
        try {
            decrementOnce(inventoryId);   // doc, tru, save - Hibernate tu them "and version = ?"
            return attempts;
        } catch (OptimisticLockingFailureException e) {
            if (attempts >= maxAttempts) throw e;
        }
    }
}
```

Hibernate tự động thêm `AND version = ?` vào câu `UPDATE` và tăng `version`
lên 1 sau mỗi lần ghi thành công. Nếu một transaction khác đã ghi (và tăng
`version`) trước, affected rows = 0 và Hibernate ném
`OptimisticLockingFailureException` — **khác hẳn mục 17**: lỗi được báo RÕ
RÀNG thay vì âm thầm ghi đè.

**Test deterministic (2 thread, cùng ép đọc version 0)**:

```
[LESSON18-OPTIMISTIC] thread1 attempts=1, thread2 attempts=2
```

Một thread thắng ngay lần đầu, thread kia phải retry đúng 1 lần — sau retry,
kết quả cuối là `-1` (đúng), không phải `0` (sai) như mục 17.

**Đo tỉ lệ retry ở 50 thread cùng tranh chấp 1 row**:

```
[LESSON18-OPTIMISTIC] 50 thread, tong so lan thu = 473, tong so lan RETRY = 423 (~8.46 retry/thread)
```

Cả 50 thread đều thành công (không lần trừ nào bị mất), nhưng phải trả giá
bằng **423 lần retry phụ trội** (~8.46 lần/thread trung bình) — mỗi lần
retry là một round-trip `SELECT` + `UPDATE` mới hoàn toàn, bị lãng phí vì
version đã đổi. Đây chính là điểm yếu của optimistic locking: **rẻ khi ít
tranh chấp, đắt khi tranh chấp cao** (tăng tuyến tính, hoặc tệ hơn, theo số
thread cùng đụng một row).

## Cách 2 — Pessimistic locking (`SELECT ... FOR UPDATE`)

```java
@Lock(LockModeType.PESSIMISTIC_WRITE)
@Query("select i from Inventory i where i.id = :id")
Optional<Inventory> findByIdForUpdate(@Param("id") Long id);
```

Transaction thứ hai gọi `findByIdForUpdate` trên **cùng row** sẽ bị **CHẶN**
(block) ngay tại câu `SELECT`, cho tới khi transaction thứ nhất commit hoặc
rollback — nó không bao giờ đọc phải giá trị "cũ", nên **không cần retry**
như cách 1:

```
Tests run: 4 -- hai_thread_cung_tru_qua_pessimistic_lock_khong_mat_cap_nhat_nao: quantityOnHand = -1 (dung)
```

### Throughput giảm: thêm thread không làm nhanh hơn

```
[LESSON18-PESSIMISTIC] 20 thao tac TUAN TU: 194 ms (103.1 ops/s)
[LESSON18-PESSIMISTIC] 20 thao tac qua 8 thread DONG THOI tren CUNG 1 row: 171 ms (117.0 ops/s)
```

Chạy 20 thao tác bằng **1 thread tuần tự** so với 20 thao tác qua **8 thread
đồng thời trên cùng 1 row** cho thời gian gần như nhau (~103 vs ~117 ops/s) —
vì lock là `EXCLUSIVE` trên đúng 1 row, mọi thread khác **buộc phải xếp
hàng**, bất kể thread pool lớn cỡ nào. Thêm thread không tăng throughput trên
một tài nguyên đang bị tranh chấp độc quyền — khác hẳn cách 1, nơi các thread
ít nhất được "thử" song song (dù có thể lãng phí do retry).

### `NOWAIT`: thất bại ngay thay vì chờ

```java
@Lock(LockModeType.PESSIMISTIC_WRITE)
@QueryHints(@QueryHint(name = "jakarta.persistence.lock.timeout", value = "0"))
@Query("select i from Inventory i where i.id = :id")
Optional<Inventory> findByIdForUpdateNoWait(@Param("id") Long id);
```

```
[LESSON18-PESSIMISTIC] NOWAIT that bai NGAY voi:
  org.springframework.dao.PessimisticLockingFailureException:
  ... [ERROR: could not obtain lock on row in relation "inventory"] ...
```

Khi row đang bị khoá bởi transaction khác, thay vì chờ, `NOWAIT` báo lỗi
**ngay lập tức** (`PSQLException: could not obtain lock` → Spring dịch thành
`PessimisticLockingFailureException`). Dùng khi thà thất bại nhanh và để
caller tự quyết định (retry, báo lỗi cho user...) còn hơn giữ một connection
chờ vô thời hạn.

### `SKIP LOCKED`: bỏ qua thay vì chờ hoặc lỗi

```java
@Query(value = "select * from inventory where id = :id for update skip locked", nativeQuery = true)
Optional<Inventory> findByIdForUpdateSkipLocked(@Param("id") Long id);
```

```
[LESSON18-PESSIMISTIC] SKIP LOCKED tra ve: Optional.empty
```

Row đang bị khoá → trả về **rỗng ngay lập tức**, không chờ, không lỗi. Đây là
chìa khoá cho **job queue pattern**: nhiều worker cùng chạy
`SELECT ... FOR UPDATE SKIP LOCKED` để tự động "chia" các row chưa ai xử lý
cho nhau — worker nào tới trước lấy được row, worker khác tự động bỏ qua row
đó và lấy row tiếp theo, không cần một cơ chế điều phối trung tâm nào khác.

## Cách 3 — Atomic conditional update

```java
@Modifying
@Query("update Inventory i set i.quantityOnHand = i.quantityOnHand - :qty "
        + "where i.id = :id and i.quantityOnHand >= :qty")
int decrementIfAvailable(@Param("id") Long id, @Param("qty") int qty);
```

```java
public boolean tryDecrement(Long inventoryId, int quantity) {
    return inventoryRepository.decrementIfAvailable(inventoryId, quantity) == 1;
}
```

Không load entity vào bộ nhớ, không `@Version`, không `SELECT ... FOR UPDATE`
tường minh — điều kiện đủ hàng nằm NGAY TRONG `WHERE` của câu `UPDATE`. Bản
thân câu `UPDATE` đã atomic ở tầng database (Postgres tự khoá row trong lúc
thực thi), nên đọc-kiểm tra-ghi không bao giờ tách rời nhau được.

```
[LESSON18-ATOMIC] thread1 thanh cong=false, thread2 thanh cong=true
[LESSON18-ATOMIC] 50 thread, so lan thanh cong = 50
```

Với tồn kho chỉ còn 1, 2 thread tranh nhau: **đúng 1** thread nhận affected
rows = 1 (thành công), thread kia nhận 0 (hết hàng) — không exception nào cả,
affected rows là tín hiệu đủ. Và ở 50 thread cùng tranh chấp (tồn kho = 50):
**cả 50 đều thành công ngay lần gọi đầu tiên, không cần retry** — khác hẳn
cách 1, vì không có khái niệm "đọc giá trị cũ rồi ghi đè" để mà xung đột.

## So sánh tổng quan

| Tiêu chí | Optimistic (`@Version`) | Pessimistic (`FOR UPDATE`) | Atomic `UPDATE ... WHERE` |
|---|---|---|---|
| Cách phát hiện xung đột | Sau khi ghi (affected rows = 0) | Trước khi đọc (block tại SELECT) | Ngay trong 1 câu SQL |
| Cần retry ở tầng app? | **Có** — bắt buộc | Không | Không |
| Chi phí khi ít tranh chấp | Rất thấp (gần như free) | Thấp | Rất thấp |
| Chi phí khi tranh chấp cao | Tăng mạnh (nhiều round-trip lãng phí, đo được ~8.46 retry/thread ở 50 thread) | Thread khác phải CHỜ (throughput không tăng theo thread) | Thấp nhất — 1 round-trip/thao tác dù tranh chấp cỡ nào |
| Cần thay đổi schema? | Có (thêm cột `version`) | Không | Không |
| Phù hợp nhất khi | Đọc nhiều/sửa ít, ít tranh chấp thực tế (ví dụ sửa profile người dùng) | Cần đảm bảo thứ tự xử lý nghiêm ngặt, sẵn sàng đổi throughput lấy đơn giản (không cần retry logic) | Thao tác đơn giản dạng "tăng/giảm một số có điều kiện" (trừ tồn kho, trừ số dư) — không cần load cả entity |

**Không có cách nào thắng tuyệt đối**: optimistic đánh đổi khả năng chạy song
song lấy rủi ro phải retry; pessimistic đánh đổi throughput lấy sự đơn giản
(không cần vòng lặp retry); atomic update đơn giản và rẻ nhất về chi phí,
nhưng chỉ áp dụng được cho các thao tác có thể diễn đạt bằng MỘT câu SQL điều
kiện (không phù hợp cho logic nghiệp vụ phức tạp cần đọc nhiều trường trước
khi quyết định).

## Khái niệm

- **Trade-off thật, đo được**: không phải lý thuyết suông — số retry ở 50
  thread (423 lần), độ chênh throughput tuần tự/song song của pessimistic
  (~103 vs ~117 ops/s trên 1 row), và việc atomic update không cần retry lần
  nào dù ở 50 thread, đều là số đo trực tiếp từ PostgreSQL thật.
- **`SKIP LOCKED` là chìa khoá cho job queue pattern**: nhiều consumer cùng
  poll một bảng "công việc cần làm", mỗi consumer tự động nhận được các row
  chưa ai khoá, không cần leader election hay coordination service riêng.

## Tổng kết

```
Tests run: 8 -- OptimisticLockingFixTest (2) + PessimisticLockingFixTest (4) + AtomicConditionalUpdateFixTest (2)
Failures: 0, Errors: 0, Skipped: 0
```

`mvn clean test` (toàn bộ project): **74/74 test pass** trên PostgreSQL thật
qua Testcontainers.
