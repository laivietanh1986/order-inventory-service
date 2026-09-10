# Mục 19 — Deadlock có chủ đích

Tương ứng mục 19 trong [`Lo_trinh_hoc_JPA.md`](../Lo_trinh_hoc_JPA.md).

## Mục tiêu

Order A đặt sản phẩm `[1, 2]`, order B đặt sản phẩm `[2, 1]` — thứ tự khoá
NGƯỢC NHAU — chạy song song → PostgreSQL báo `deadlock detected`. Sửa bằng
cách sắp xếp `productId` (ở đây là `inventoryId`) trước khi khoá.

## File đã tạo

| File | Vai trò |
|---|---|
| [`MultiInventoryReservationService.java`](../src/main/java/com/example/orderinventory/concurrency/MultiInventoryReservationService.java) | `reserveInGivenOrder` (BUG: khoá theo đúng thứ tự truyền vào) và `reserveDeadlockSafe` (FIX: tự sắp xếp id trước khi khoá) |
| [`DeadlockConcurrencyTest.java`](../src/test/java/com/example/orderinventory/concurrency/DeadlockConcurrencyTest.java) | Toàn bộ thực nghiệm mục 19, trên PostgreSQL thật (Testcontainers) |

Cả hai method đều dùng lại `InventoryRepository.findByIdForUpdate` (`SELECT
... FOR UPDATE`) đã có từ [mục 18](18-ba-cach-sua-lost-update.md).

Chạy: `mvn test -Dtest=DeadlockConcurrencyTest`

## Cơ chế tái hiện: deterministic, không phụ thuộc may rủi

Giống mục 17, chỉ start 2 thread cùng lúc thì deadlock **có thể** xảy ra
nhưng không **chắc chắn** — nếu một thread khoá và nhả xong cả 2 row trước
khi thread kia kịp bắt đầu, sẽ không có xung đột nào cả. Test dùng một
`CountDownLatch(2)` truyền vào tham số `afterFirstLock` của
`reserveInGivenOrder`:

```java
@Transactional
public void reserveInGivenOrder(List<Long> inventoryIds, Runnable afterFirstLock) {
    boolean first = true;
    for (Long id : inventoryIds) {
        inventoryRepository.findByIdForUpdate(id).orElseThrow();
        if (first) {
            afterFirstLock.run();   // <- diem dong bo hoa
            first = false;
        }
    }
}
```

`afterFirstLock` chạy NGAY sau khi khoá xong row ĐẦU TIÊN — ép cả hai
transaction phải cùng giữ được lock đầu tiên của mình trước khi bất kỳ bên
nào được phép xin lock thứ hai. Với order A giữ `id1` chờ `id2`, và order B
giữ `id2` chờ `id1` cùng một lúc, vòng chờ đợi vòng tròn (circular wait) chắc
chắn hình thành mỗi lần chạy.

## Kết quả đo được

```
[LESSON19] order A that bai: org.springframework.dao.CannotAcquireLockException:
  JDBC exception executing SQL [select ... from inventory ... where i1_0.id=? for no key update]
  [ERROR: deadlock detected
[LESSON19] order B that bai: khong
Tests run: 1, Failures: 0, Errors: 0
```

PostgreSQL phát hiện vòng chờ đợi vòng tròn và chủ động huỷ **một trong hai**
transaction (ở đây là order A) bằng lỗi `deadlock detected`, để order B được
tiếp tục và commit bình thường. Spring dịch lỗi Postgres thành
`org.springframework.dao.CannotAcquireLockException` (một subtype của
`PessimisticLockingFailureException`, cùng họ lỗi với `NOWAIT` ở mục 18 —
khác nhau ở nguyên nhân: `NOWAIT` là "row đang bận", còn đây là "phát hiện
vòng tròn chờ đợi").

## Fix: sắp xếp id trước khi khoá

```java
@Transactional
public void reserveDeadlockSafe(List<Long> inventoryIds) {
    List<Long> sortedIds = new ArrayList<>(inventoryIds);
    Collections.sort(sortedIds);
    for (Long id : sortedIds) {
        inventoryRepository.findByIdForUpdate(id).orElseThrow();
    }
}
```

Order A gọi với `[id1, id2]`, order B gọi với `[id2, id1]` — thứ tự **nghiệp
vụ** (thứ tự sản phẩm trong đơn hàng) vẫn ngược nhau như cũ, nhưng cả hai đều
tự sắp xếp lại thành cùng một thứ tự **khoá tuyệt đối** trước khi thực sự xin
lock. Kết quả:

```
[LESSON19] Ca hai order hoan tat KHONG deadlock sau khi sap xep id truoc khi khoa.
Tests run: 1, Failures: 0, Errors: 0
```

Cả hai transaction hoàn tất thành công, không transaction nào bị huỷ. Order
đến sau chỉ đơn giản **chờ** (block) tại đúng 1 điểm — id nhỏ hơn — rồi được
tiếp tục sau khi order kia commit và nhả lock.

## Khái niệm

- **Deadlock sinh ra từ thứ tự khoá không nhất quán**: bản thân việc "khoá
  nhiều tài nguyên trong một transaction" không có gì sai — vấn đề chỉ xảy ra
  khi hai transaction khác nhau khoá **cùng một tập tài nguyên theo hai thứ
  tự khác nhau**. Nếu A luôn khoá theo thứ tự `[X, Y]` và B cũng luôn khoá
  theo thứ tự `[X, Y]` (dù nghiệp vụ của B là xử lý Y trước), sẽ không bao
  giờ có deadlock giữa A và B — chỉ có chờ đợi tuần tự (một bên block, không
  phải cả hai cùng chờ nhau).
- **Mitigation chuẩn: sắp xếp deterministic**: quy ước toàn hệ thống "luôn
  khoá tài nguyên theo thứ tự tăng dần của khoá chính (hoặc bất kỳ tiêu chí
  nhất quán nào)" loại bỏ hoàn toàn khả năng hình thành vòng tròn chờ đợi —
  đây là cách các hệ thống thực tế (ví dụ chuyển tiền giữa 2 tài khoản) luôn
  khoá tài khoản có id nhỏ hơn trước.
- **`deadlock_timeout` và cơ chế tự phát hiện**: PostgreSQL không phát hiện
  deadlock ngay lập tức — nó chờ `deadlock_timeout` (mặc định 1 giây) kể từ
  khi một transaction bắt đầu chờ một lock, rồi mới chạy thuật toán phát hiện
  chu trình (cycle detection) trên đồ thị "ai đang chờ ai". Nếu tìm thấy chu
  trình, nó chọn một transaction (thường là transaction gây ra chu trình
  muộn nhất) làm "nạn nhân" và huỷ nó bằng lỗi `deadlock detected`, để các
  transaction còn lại trong chu trình được giải phóng. Đây là lý do
  `deadlock detected` luôn đến sau một khoảng trễ nhỏ (≥ `deadlock_timeout`),
  khác với `NOWAIT` (báo lỗi ngay lập tức, không chờ) đã thấy ở mục 18.

## Tổng kết

```
Tests run: 2, Failures: 0, Errors: 0, Skipped: 0 -- DeadlockConcurrencyTest
```

`mvn clean test` (toàn bộ project): **74/74 test pass** trên PostgreSQL thật
qua Testcontainers — khép lại Cấp 4 (mục 17-19: lost update, ba cách sửa,
deadlock).
