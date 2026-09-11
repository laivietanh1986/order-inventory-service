# Mục 21 — State machine an toàn dưới concurrency

Tương ứng mục 21 trong [`Lo_trinh_hoc_JPA.md`](../Lo_trinh_hoc_JPA.md), khép
lại Cấp 4.

## Mục tiêu

Confirm và cancel cùng một order đồng thời:

- **Bản sai**: `if (order.getStatus() == CREATED) { ... }` → cả hai cùng
  qua, `status_history` có 2 row cho một hành động đáng lẽ chỉ được phép xảy
  ra đúng một lần.
- **Bản đúng**: `UPDATE orders SET status = 'CONFIRMED' WHERE id = ? AND
  status = 'CREATED'` rồi kiểm tra affected rows.
- Thêm test idempotency: gọi cancel 2 lần phải cho cùng kết quả.

## File đã tạo

| File | Vai trò |
|---|---|
| [`OrderRepository.java`](../src/main/java/com/example/orderinventory/order/OrderRepository.java) | Thêm `updateStatusIfCurrentlyIs` (atomic conditional update) và `findStatusById` |
| [`OrderStatusHistoryRepository.java`](../src/main/java/com/example/orderinventory/order/OrderStatusHistoryRepository.java) | `JpaRepository` cho entity `OrderStatusHistory` có sẵn từ mục 11 |
| [`NaiveOrderStateMachineService.java`](../src/main/java/com/example/orderinventory/order/NaiveOrderStateMachineService.java) | Bản SAI: check-then-act trong bộ nhớ JVM |
| [`OrderStateMachineService.java`](../src/main/java/com/example/orderinventory/order/OrderStateMachineService.java) | Bản ĐÚNG: điều kiện nằm trong `UPDATE`, đồng thời tạo tính idempotent |
| [`OrderStateMachineConcurrencyTest.java`](../src/test/java/com/example/orderinventory/order/OrderStateMachineConcurrencyTest.java) | Toàn bộ thực nghiệm mục 21, trên PostgreSQL thật (Testcontainers) |

Chạy: `mvn test -Dtest=OrderStateMachineConcurrencyTest`

## Bản sai: check-then-act trong bộ nhớ JVM

```java
private boolean transitionIfCreated(Long orderId, String newStatus, Runnable afterRead) {
    Order order = orderRepository.findById(orderId).orElseThrow();
    boolean canTransition = "CREATED".equals(order.getStatus());   // "kiem tra" - chi trong bo nho
    afterRead.run();
    if (!canTransition) return false;
    order.setStatus(newStatus);
    orderRepository.save(order);
    orderStatusHistoryRepository.save(new OrderStatusHistory(newStatus) /* + setOrder(order) */);
    return true;
}
```

Test ép `confirm()` và `cancel()` chạy đồng thời trên cùng một order đang
`CREATED`, dùng đúng kỹ thuật `CountDownLatch` đã thấy từ mục 17-19 để buộc
cả hai đều đọc xong `status = CREATED` trước khi bên nào kịp ghi:

```
[LESSON21-NAIVE] confirm thanh cong=true, cancel thanh cong=true,
  trang thai cuoi=CANCELLED, so dong status_history=2
```

Cả `confirm()` và `cancel()` đều báo "thành công" — hai hành động **mâu
thuẫn nhau về mặt nghiệp vụ** (một đơn hàng không thể vừa được xác nhận vừa
bị huỷ) nhưng cả hai đều đi qua được điều kiện `if`, vì điều kiện đó chỉ được
kiểm tra trên một bản sao dữ liệu đã đọc vào bộ nhớ JVM, không có gì ngăn hai
transaction cùng nhìn thấy `CREATED` cùng một lúc. Hậu quả cụ thể:
`status_history` có **2 dòng** cho một đơn hàng lẽ ra chỉ được chuyển trạng
thái đúng một lần, và trạng thái cuối cùng (`CANCELLED` ở lần chạy này) hoàn
toàn phụ thuộc vào việc `UPDATE` nào tới sau — "last write wins" một cách
ngẫu nhiên, không phải do logic nghiệp vụ quyết định.

## Bản đúng: điều kiện nằm trong `UPDATE`

```java
@Modifying
@Query("update Order o set o.status = :toStatus where o.id = :id and o.status = :fromStatus")
int updateStatusIfCurrentlyIs(@Param("id") Long id, @Param("fromStatus") String fromStatus,
        @Param("toStatus") String toStatus);
```

```java
private boolean transitionIfCreated(Long orderId, String targetStatus) {
    int affectedRows = orderRepository.updateStatusIfCurrentlyIs(orderId, "CREATED", targetStatus);
    if (affectedRows == 1) {
        orderStatusHistoryRepository.save(new OrderStatusHistory(targetStatus) /* + setOrder(ref) */);
    }
    return targetStatus.equals(orderRepository.findStatusById(orderId));
}
```

Chạy lại đúng kịch bản đồng thời confirm/cancel:

```
[LESSON21-FIXED] confirm tra ve=true, cancel tra ve=false,
  trang thai cuoi=CONFIRMED, so dong status_history=1
```

`UPDATE ... WHERE status = 'CREATED'` khiến bản thân câu SQL trở thành điểm
đồng bộ hoá: PostgreSQL khoá row trong lúc `UPDATE` thực thi, nên dù hai câu
lệnh chạy "đồng thời" ở tầng ứng dụng, chúng vẫn buộc phải xử lý **tuần tự**
ở tầng database — lệnh chạy trước khớp điều kiện (affected rows = 1) và đổi
`status`; lệnh chạy sau, khi tới lượt, nhìn thấy `status` **đã đổi** rồi (nó
không còn là `CREATED` nữa) nên điều kiện `WHERE` không khớp — affected rows
= 0, trả về `false` mà không cần bất kỳ lock tường minh hay `@Version` nào.
Đúng một trong hai request thành công, đúng một dòng lịch sử được ghi.

## Idempotency: gọi cancel 2 lần phải cho cùng kết quả

Điểm thiết kế quan trọng: `transitionIfCreated` không trả về "chính lệnh gọi
này có vừa thực hiện bước chuyển hay không" — nó trả về **trạng thái thực tế
sau cùng có đúng là trạng thái mong muốn hay không**
(`targetStatus.equals(findStatusById(orderId))`). Nhờ vậy:

```
[LESSON21-IDEMPOTENCY] lan 1=true, lan 2=true, so dong status_history=1
```

Gọi `cancel()` hai lần liên tiếp trên cùng một order: lần 1 thực sự chuyển
`CREATED → CANCELLED` (affected rows = 1, ghi 1 dòng lịch sử) và trả về
`true`; lần 2 không còn gì để chuyển (`status` đã là `CANCELLED`, affected
rows = 0) nhưng vẫn trả về `true` — vì trạng thái **thực tế** vẫn đúng là
`CANCELLED`, và **không ghi thêm dòng lịch sử nào**. Đây chính là ý nghĩa
"gọi 2 lần cho cùng kết quả": caller nhận được câu trả lời nhất quán bất kể
đây là lần gọi đầu hay một lần retry, mà không hề có tác dụng phụ nhân đôi
(duplicate audit row).

So sánh với trường hợp KHÔNG phải no-op idempotent — cancel một order **đã
`CONFIRMED`** (một trạng thái đích khác, không phải trạng thái đang cố đạt
tới):

```
[LESSON21-IDEMPOTENCY] cancel tren order da CONFIRMED tra ve=false
```

`false` ở đây là chính xác: order đang ở `CONFIRMED`, không phải
`CANCELLED`, nên "gọi cancel có đạt được trạng thái CANCELLED không" đúng
là `false` — khác về bản chất với "gọi cancel trên order đã CANCELLED",
nơi trạng thái mong muốn đã đạt được từ trước.

## Khái niệm

- **Check-then-act ở tầng application luôn là race condition**: bất kỳ mẫu
  hình nào có dạng "đọc dữ liệu vào bộ nhớ → kiểm tra điều kiện trên bản sao
  đó → ghi lại" đều có một khoảng hở giữa bước đọc và bước ghi mà không có
  gì đảm bảo dữ liệu chưa bị transaction khác thay đổi trong lúc đó — bất kể
  điều kiện được viết cẩn thận thế nào ở tầng Java. Đây chính là bản chất
  chung của lost update (mục 17) và bug ở mục này; chỉ khác nhau ở việc điều
  kiện đang bảo vệ là "giá trị số" hay "trạng thái enum/string".
- **Đẩy điều kiện xuống câu `UPDATE`**: biến điều kiện từ một câu `if` ở
  tầng Java thành một mệnh đề `WHERE` trong chính câu lệnh ghi — nhờ vậy
  "kiểm tra" và "ghi" trở thành **một thao tác nguyên tử duy nhất** ở tầng
  database, không có khoảng hở nào để transaction khác chen vào giữa.
  Affected rows chính là tín hiệu đáng tin cậy duy nhất về việc "điều kiện
  có còn đúng tại thời điểm ghi hay không" — đọc trạng thái ra rồi so sánh
  ở tầng Java không bao giờ cho câu trả lời đáng tin bằng.
- **Idempotency key**: kỹ thuật liên quan nhưng khác mục đích — thay vì chỉ
  dựa vào trạng thái hiện tại của resource (như `cancel()` ở đây), một
  idempotency key là một định danh do **client** sinh ra và gửi kèm mỗi
  request (thường dùng cho các API có tác dụng phụ không thể suy ra từ
  trạng thái, ví dụ "tạo một giao dịch thanh toán mới") — server lưu lại key
  đã xử lý, và nếu thấy key đã tồn tại thì trả về **kết quả đã lưu từ lần xử
  lý trước** thay vì xử lý lại từ đầu. Khác với cách tiếp cận "state-based"
  ở bài này (an toàn vì tự thân trạng thái đích đã là bất biến/idempotent),
  idempotency key cần thiết khi thao tác không thể diễn đạt bằng một điều
  kiện `WHERE` đơn giản trên trạng thái sẵn có.

## Tổng kết

```
Tests run: 4, Failures: 0, Errors: 0, Skipped: 0 -- OrderStateMachineConcurrencyTest
```

`mvn clean test` (toàn bộ project): **85/85 test pass** trên PostgreSQL thật
qua Testcontainers — khép lại Cấp 4 (mục 17-21: lost update, ba cách sửa,
deadlock, bốn bẫy `@Transactional`, state machine an toàn).
