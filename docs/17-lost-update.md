# Mục 17 — Tái hiện lost update

Tương ứng mục 17 trong [`Lo_trinh_hoc_JPA.md`](../Lo_trinh_hoc_JPA.md).

## Mục tiêu

`ExecutorService` 2 thread + `CountDownLatch`, cùng đọc `availableQty = 1`,
cùng trừ 1, cùng save. Chạy ở `READ COMMITTED` — mức isolation mặc định của
PostgreSQL. Quan sát: cả hai lệnh save đều "thành công" (không exception nào
cả), nhưng kết quả cuối cùng sai — một trong hai lần trừ bị **mất**.

> ⚠️ Từ mục này trở đi, **test method tuyệt đối không đánh `@Transactional`**.
> Nếu có, Spring sẽ gói cả test vào một transaction, hai thread thực ra dùng
> chung một connection, và concurrency test sẽ pass giả tạo (không hề tái
> hiện được race condition thật). Dữ liệu được dọn thủ công ở `@AfterEach`
> thay vì dựa vào rollback.

## File đã tạo

| File | Vai trò |
|---|---|
| [`InventoryRepository.java`](../src/main/java/com/example/orderinventory/inventory/InventoryRepository.java) | `JpaRepository` cho entity `Inventory` có sẵn từ mục 5 |
| [`NaiveInventoryReservationService.java`](../src/main/java/com/example/orderinventory/concurrency/NaiveInventoryReservationService.java) | Phiên bản NAIVE cố ý: đọc `quantityOnHand` vào bộ nhớ, trừ 1, save — không `@Version`, không lock nào cả |
| [`LostUpdateConcurrencyTest.java`](../src/test/java/com/example/orderinventory/concurrency/LostUpdateConcurrencyTest.java) | Toàn bộ thực nghiệm mục 17, trên PostgreSQL thật (Testcontainers) |

Chạy: `mvn test -Dtest=LostUpdateConcurrencyTest`

## Cơ chế tái hiện: deterministic, không phụ thuộc may rủi của scheduler

Nếu chỉ start 2 thread cùng lúc bằng một `CountDownLatch` rồi để chúng tự
chạy, race condition **có thể** xảy ra nhưng không **chắc chắn** xảy ra ở mọi
lần chạy — phụ thuộc timing của JVM/OS scheduler. Test này ép buộc interleaving
chính xác bằng một tham số `afterRead: Runnable` trong service:

```java
@Transactional
public void decrementQuantity(Long inventoryId, Runnable afterRead) {
    Inventory inventory = inventoryRepository.findById(inventoryId).orElseThrow();
    int quantityAlreadyRead = inventory.getQuantityOnHand();
    afterRead.run();                                   // <- diem dong bo hoa
    inventory.setQuantityOnHand(quantityAlreadyRead - 1);
    inventoryRepository.save(inventory);
}
```

Test truyền vào `afterRead` một `CountDownLatch(2)`: mỗi thread đếm ngược rồi
`await()` — nghĩa là **cả hai thread bắt buộc phải đọc xong `quantityOnHand`
trước khi bất kỳ thread nào được phép tính giá trị mới và ghi**. Điều này tái
hiện đúng 100% mỗi lần chạy, thay vì thỉnh thoảng mới bắt được bug.

## Kết quả đo được

```
[LESSON17] quantityOnHand sau 2 lan tru tu 1 = 0 (dung ra phai la -1 neu khong mat cap nhat)
Tests run: 1, Failures: 0, Errors: 0, Skipped: 0
```

Cả hai thread đọc được `quantityOnHand = 1` (chưa thread nào commit), tính
`1 - 1 = 0`, rồi **cả hai đều save 0** — Hibernate sinh
`UPDATE inventory SET quantity_on_hand = 0 WHERE id = ?` cho cả hai, không
kiểm tra giá trị cũ. Kết quả đúng ra phải là `1 - 1 - 1 = -1` (hai lần trừ
thành công), nhưng thực tế là `0` — **một lần trừ bị mất hoàn toàn, không có
bất kỳ exception, log lỗi, hay tín hiệu nào báo hiệu**. Đây chính là điều nguy
hiểm nhất của lost update: nó không "crash", nó chỉ âm thầm sai.

## Khái niệm

- **Isolation level**: mức độ một transaction bị "nhìn thấy" bởi các thay đổi
  chưa/đã commit của transaction khác. SQL chuẩn định nghĩa 4 mức: `READ
  UNCOMMITTED`, `READ COMMITTED`, `REPEATABLE READ`, `SERIALIZABLE`.
- **Các anomaly kinh điển**:
  - *Dirty read*: đọc được dữ liệu **chưa commit** của transaction khác.
  - *Non-repeatable read*: đọc cùng một row 2 lần trong cùng transaction,
    ra 2 giá trị khác nhau vì transaction khác đã commit ở giữa.
  - *Phantom read*: chạy lại cùng một query có `WHERE`, ra thêm/bớt row vì
    transaction khác đã insert/delete ở giữa.
  - *Lost update*: hai transaction cùng đọc-sửa-ghi trên cùng dữ liệu, một
    trong hai lần ghi bị ghi đè mất — chính là bug ở mục này.
  - *Write skew*: hai transaction cùng đọc một tập dữ liệu, mỗi transaction
    chỉ sửa một PHẦN khác nhau của tập đó dựa trên điều kiện đã đọc, nhưng khi
    cả hai commit thì điều kiện ban đầu (đã dùng để quyết định) không còn
    đúng nữa — tinh vi hơn lost update vì không đụng chung 1 row.
- **Tại sao `READ COMMITTED` không ngăn được lost update**: `READ COMMITTED`
  chỉ đảm bảo mỗi câu lệnh (statement) không đọc phải dữ liệu chưa commit —
  nó không đảm bảo gì về khoảng thời gian **giữa** lúc đọc và lúc ghi trong
  cùng một transaction. Hai transaction có thể cùng đọc dữ liệu committed ở
  thời điểm T (giống hệt nhau), rồi cả hai tính toán trên giá trị đó một cách
  độc lập — không transaction nào biết transaction kia cũng đang làm việc
  trên cùng dữ liệu.
- **MVCC của Postgres so với gap lock của MySQL (InnoDB)**: Postgres dùng
  MVCC (Multi-Version Concurrency Control) cho **mọi** isolation level — mỗi
  transaction thấy một "snapshot" nhất quán, không cần lock để đọc (đọc không
  bao giờ chặn ghi và ngược lại). MySQL/InnoDB ở `REPEATABLE READ` (mặc định
  của MySQL, khác Postgres dùng `READ COMMITTED` làm mặc định) bổ sung thêm
  **gap lock** — khoá cả "khoảng trống" giữa các index key để chặn phantom
  read khi dùng range query, một cơ chế mà Postgres không có (Postgres ngăn
  phantom ở `REPEATABLE READ`/`SERIALIZABLE` bằng snapshot + serialization
  conflict detection, không bằng lock vật lý trên gap).
