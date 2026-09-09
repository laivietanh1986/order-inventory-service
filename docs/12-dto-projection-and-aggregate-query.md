# 12. DTO projection và aggregate query

Tương ứng mục 12 trong [`Lo_trinh_hoc_JPA.md`](../Lo_trinh_hoc_JPA.md).

## Mục tiêu

Ba cách trả danh sách order: load entity rồi map, constructor expression `select new`, interface-based projection. So sánh số cột `SELECT` và thời gian.

Viết `customer order-summary` bằng `SELECT count(*), sum(...), max(...)` thay vì load list rồi `stream().reduce()`. Seed 1 khách có 50.000 order để thấy khác biệt.

Khái niệm cần nắm: khi nào không cần entity, `@Transactional(readOnly = true)` tắt dirty checking và bỏ snapshot.

## Các file liên quan

| File | Vai trò |
| :--- | :--- |
| [`OrderItemSummaryDto`](../src/main/java/com/example/orderinventory/order/OrderItemSummaryDto.java) | Đích của constructor expression |
| [`OrderItemSummaryView`](../src/main/java/com/example/orderinventory/order/OrderItemSummaryView.java) | Interface-based projection |
| [`CustomerOrderSummaryDto`](../src/main/java/com/example/orderinventory/order/CustomerOrderSummaryDto.java) | Kết quả aggregate query |
| [`Order.totalAmount`](../src/main/java/com/example/orderinventory/order/Order.java) | Cột mới phục vụ `SUM`/`MAX` |
| [`V11__add_total_amount_to_orders.sql`](../src/main/resources/db/migration/V11__add_total_amount_to_orders.sql) | Migration thêm cột |
| [`OrderItemProjectionTest.java`](../src/test/java/com/example/orderinventory/order/OrderItemProjectionTest.java) | So sánh 3 cách projection |
| [`CustomerOrderSummaryTest.java`](../src/test/java/com/example/orderinventory/order/CustomerOrderSummaryTest.java) | Aggregate query vs load-all, seed 50.000 order |
| [`ReadOnlyDisablesDirtyCheckingTest.java`](../src/test/java/com/example/orderinventory/order/ReadOnlyDisablesDirtyCheckingTest.java) | `readOnly` tắt dirty checking |

## Cách chạy

```bash
./mvnw test -Dtest=OrderItemProjectionTest,CustomerOrderSummaryTest,ReadOnlyDisablesDirtyCheckingTest
```

## Ba cách trả về danh sách tóm tắt

Bài toán: chỉ cần `productSku` và `quantity` của mỗi `OrderItem`, không cần gì khác.

### Cách 1 — load entity rồi map ở tầng Java

```java
List<OrderItem> items = entityManager.createQuery("SELECT i FROM OrderItem i", OrderItem.class)
        .getResultList();
List<OrderItemSummaryDto> summaries = items.stream()
        .map(i -> new OrderItemSummaryDto(i.getProductSku(), i.getQuantity()))
        .toList();
```

Log Hibernate (2 câu, không phải 1):

```sql
select oi1_0.id, oi1_0.order_id, oi1_0.product_sku, oi1_0.quantity
from order_items oi1_0

select o1_0.id, o1_0.customer_name, o1_0.status, o1_0.total_amount
from orders o1_0 where o1_0.id=?
```

Hai phát hiện quan trọng:

1. **4 cột được SELECT** cho `OrderItem` (`id`, `order_id`, `product_sku`, `quantity`) — nhiều hơn 2 cột thực sự cần dùng, vì JPQL `SELECT i FROM OrderItem i` luôn lấy đủ mọi cột ánh xạ của entity, không có cách nào chỉ lấy một phần khi query trả về chính entity.
2. **Một câu SELECT thứ hai, riêng biệt**, để nạp `Order` — vì `OrderItem.order` là `@ManyToOne` mặc định EAGER (mục 5). Đây là phát hiện bất ngờ: JPQL không tự động biến EAGER thành `JOIN` trong cùng câu SQL — nó chỉ đảm bảo association được nạp *ngay sau đó*, bằng một **câu query riêng** (giống hệt hình hài N+1 ở mục 8, chỉ khác N ở đây bằng 1 vì cả 2 item cùng chung 1 order, đã có sẵn trong persistence context nhờ identity map).

### Cách 2 — constructor expression (`SELECT new ...`)

```java
@Query("SELECT new com.example.orderinventory.order.OrderItemSummaryDto(i.productSku, i.quantity) FROM OrderItem i")
List<OrderItemSummaryDto> findAllAsConstructorExpression();
```

```sql
select oi1_0.product_sku, oi1_0.quantity from order_items oi1_0
```

Đúng **1 câu SQL, đúng 2 cột**, không đụng gì đến bảng `orders` — vì query khai báo tường minh cần gì, Hibernate không có lý do gì để "suy luận" thêm việc nạp association nào khác.

### Cách 3 — interface-based projection

```java
public interface OrderItemSummaryView {
    String getProductSku();
    Integer getQuantity();
}

@Query("SELECT i.productSku AS productSku, i.quantity AS quantity FROM OrderItem i")
List<OrderItemSummaryView> findAllAsInterfaceProjection();
```

Cùng kết quả với cách 2: 1 câu SQL, 2 cột. Khác biệt chỉ ở phía Java — Spring Data tự sinh proxy hiện thực interface, không cần viết class DTO tay. Về mặt SQL, cách 2 và cách 3 **hoàn toàn tương đương**; lựa chọn giữa chúng thuần tuý là phong cách code (constructor expression cho phép logic trong constructor, interface projection gọn hơn khi chỉ cần map phẳng 1-1).

## So sánh

| Cách | Số câu SQL | Số cột SELECT | Ghi chú |
| :--- | :---: | :---: | :--- |
| Load entity rồi map | 2 | 4 (+ 4 của Order) | Kéo theo cả association EAGER không cần dùng |
| Constructor expression | 1 | 2 | Đúng những gì cần |
| Interface projection | 1 | 2 | Đúng những gì cần, ít code hơn |

So sánh thời gian tuyệt đối ở quy mô nhỏ (2 item) không có ý nghĩa — sai khác nằm trong nhiễu đo lường. Khác biệt thời gian **thực sự đáng kể** chỉ lộ ra ở quy mô lớn, chính là lý do phần B của bài học này chuyển sang seed 50.000 dòng.

## Aggregate query: `customer order-summary`

```java
@Query("SELECT new com.example.orderinventory.order.CustomerOrderSummaryDto(" +
        "COUNT(o), SUM(o.totalAmount), MAX(o.totalAmount)) " +
        "FROM Order o WHERE o.customerName = :customerName")
CustomerOrderSummaryDto findCustomerOrderSummary(@Param("customerName") String customerName);
```

So với cách "ngây thơ":

```java
List<Order> allOrders = orderRepository.findByCustomerName(customerName);
long count = allOrders.size();
BigDecimal total = allOrders.stream().map(Order::getTotalAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
BigDecimal max = allOrders.stream().map(Order::getTotalAmount).max(BigDecimal::compareTo).orElse(BigDecimal.ZERO);
```

Seed 1 khách hàng với 50.000 order (giá trị `total_amount` từ 1 đến 50.000, seed bằng `JdbcTemplate.batchUpdate` cho nhanh — bản thân việc seed không phải nội dung bài học này, để JPA/Hibernate persist từng entity một sẽ chậm không cần thiết):

```java
CustomerOrderSummaryDto summary = orderRepository.findCustomerOrderSummary(CUSTOMER_NAME);
// summary.getOrderCount() == 50_000
// summary.getTotalAmount() == 1_250_025_000 (= 50000*50001/2)
// summary.getMaxOrderAmount() == 50_000
```

Log Hibernate của aggregate query:

```sql
select count(o1_0.id), sum(o1_0.total_amount), max(o1_0.total_amount)
from orders o1_0 where o1_0.customer_name=?
```

**Đúng 1 row trả về từ DB**, bất kể có 50.000 hay 5 triệu order đứng sau — công việc tính toán do **chính DB đảm nhiệm** (nó đã tối ưu để quét và cộng dồn số liệu, không cần vật chất hoá 50.000 object Java).

Ngược lại, cách "ngây thơ" phải:
1. Truyền tải **toàn bộ 50.000 row**, mỗi row đủ 4 cột, qua kết nối JDBC.
2. Vật chất hoá **50.000 object `Order`** trong heap.
3. Chạy 3 lượt `stream()` (hoặc gộp thành 1 lượt) trên 50.000 phần tử ở tầng Java.

Đo thời gian thực tế trong test (H2 in-memory, máy phát triển — con số tuyệt đối sẽ khác trên môi trường khác, nhưng tỉ lệ chênh lệch luôn theo hướng này):

```
aggregate  ≈ 478 ms
load-all   ≈ 1514 ms   (~3 lần chậm hơn)
```

Test chỉ assert `aggregateDurationNanos < loadAllDurationNanos` (không ép một tỉ lệ cụ thể) — đủ để chứng minh chiều hướng mà không bị flaky vì dao động môi trường CI.

## `@Transactional(readOnly = true)` tắt dirty checking

Nhắc lại mục 1: mặc định, sửa field trên một entity đang managed rồi để transaction flush/commit sẽ tự sinh `UPDATE`, dù không gọi `save()` — vì Hibernate giữ lại một **snapshot** lúc load để so sánh (dirty checking).

```java
Order loaded = entityManager.find(Order.class, order.getId());
loaded.setStatus("CONFIRMED"); // khong goi save()
entityManager.flush();

assertThat(statistics().getEntityUpdateCount()).isEqualTo(1); // dung nhu muc 1
```

Khi transaction là `readOnly = true` (ở tầng Hibernate, tương đương `Session.setDefaultReadOnly(true)` — chính là điều Spring's `JpaTransactionManager` thiết lập khi thấy `@Transactional(readOnly = true)`):

```java
Session session = entityManager.getEntityManager().unwrap(Session.class);
session.setDefaultReadOnly(true);

Order loaded = entityManager.find(Order.class, order.getId());
loaded.setStatus("CONFIRMED"); // van sua field
entityManager.flush();

assertThat(statistics().getEntityUpdateCount()).isZero(); // KHONG con UPDATE nao
```

### Vì sao — không phải "bỏ qua" mà là "không còn gì để so sánh"

Ở chế độ read-only, Hibernate **không tạo snapshot** cho entity ngay từ lúc load. Dirty checking về bản chất là "so sánh trạng thái hiện tại với snapshot lúc load" — không có snapshot thì không có phép so sánh nào để thực hiện, nên `flush()` không tìm thấy gì "dirty" để tạo `UPDATE`, bất kể field đã thực sự bị sửa trong bộ nhớ. Đây không phải Hibernate "cố tình bỏ qua thay đổi" — nó đơn giản là không còn cơ chế nào để *phát hiện* thay đổi đó nữa.

Lợi ích kép:
- **Đúng ngữ nghĩa**: một thao tác đọc dữ liệu (báo cáo, danh sách hiển thị, `GET` endpoint) không có lý do gì để vô tình ghi dữ liệu xuống DB chỉ vì code lỡ gọi một setter ở đâu đó.
- **Tiết kiệm bộ nhớ**: không giữ snapshot nghĩa là mỗi entity tốn ít bộ nhớ hơn — đáng kể khi load một danh sách lớn chỉ để hiển thị.

## Khi nào không cần entity

Tổng kết cả bài: đa số các luồng đọc dữ liệu trong một ứng dụng thực tế — danh sách hiển thị, báo cáo, dashboard, export — **không cần entity đầy đủ**, chỉ cần đúng vài trường hoặc một con số tổng hợp. Dùng entity cho những trường hợp này kéo theo ba cái giá không cần thiết: cột dư thừa trong SELECT, association EAGER âm thầm kéo theo query phụ, và bộ máy dirty-checking/snapshot chạy vô ích cho dữ liệu sẽ không bao giờ bị sửa. DTO projection (constructor expression hoặc interface-based) và aggregate query giải quyết đúng vấn đề "chỉ cần đọc" mà không phải trả giá của "có thể sẽ ghi" — còn `@Transactional(readOnly = true)` là công tắc dành cho trường hợp vẫn cần load entity thật (ví dụ để tái sử dụng logic nghiệp vụ trên object đó) nhưng chắc chắn không sửa gì.

## Kết quả chạy

```
Tests run: 3, Failures: 0, Errors: 0, Skipped: 0   -- OrderItemProjectionTest
Tests run: 1, Failures: 0, Errors: 0, Skipped: 0   -- CustomerOrderSummaryTest
Tests run: 2, Failures: 0, Errors: 0, Skipped: 0   -- ReadOnlyDisablesDirtyCheckingTest
BUILD SUCCESS
```
