# 10. HHH000104 — phân trang chết trong memory

Tương ứng mục 10 trong [`Lo_trinh_hoc_JPA.md`](../Lo_trinh_hoc_JPA.md).

## Mục tiêu

`JOIN FETCH` collection kết hợp `Pageable`, seed 10.000 order, tìm warning `firstResult/maxResults specified with collection fetch; applying in memory` và đo heap.

Sửa bằng two-query pattern: query 1 lấy page các `id` không join collection, query 2 `WHERE id IN (:ids) JOIN FETCH`.

Khái niệm cần nắm: tại sao Hibernate buộc phải phân trang trong JVM, đây là ranh giới rõ nhất giữa người dùng JPA và người hiểu JPA.

## Các file liên quan

| File | Vai trò |
| :--- | :--- |
| [`OrderRepository`](../src/main/java/com/example/orderinventory/order/OrderRepository.java) | Thêm `findAllWithItemsJoinFetchPaged` (anti-pattern), `findOrderIdsPaged` + `findAllWithItemsByIdIn` (two-query pattern) |
| [`OrderPageService.java`](../src/main/java/com/example/orderinventory/order/OrderPageService.java) | Ghép 2 query thành một `Page<Order>` |
| [`InMemoryPaginationTest.java`](../src/test/java/com/example/orderinventory/order/InMemoryPaginationTest.java) | Bắt warning bằng Logback `ListAppender`, đo số row thực tế bằng Hibernate `Statistics` |

## Cách chạy

```bash
./mvnw test -Dtest=InMemoryPaginationTest
```

Seed 10.000 `Order` (mỗi order 1 `OrderItem`) để con số "toàn bộ kết quả" (10.000) và "một trang" (20) khác biệt đủ rõ để đo được, không cần đoán.

## Tái hiện: `JOIN FETCH` + `Pageable`

```java
@Query(value = "SELECT o FROM Order o JOIN FETCH o.items",
        countQuery = "SELECT COUNT(o) FROM Order o")
Page<Order> findAllWithItemsJoinFetchPaged(Pageable pageable);
```

```java
Pageable pageable = PageRequest.of(0, 20);
Page<Order> page = orderRepository.findAllWithItemsJoinFetchPaged(pageable);

assertThat(page.getContent()).hasSize(20);        // "nhin ben ngoai" van dung
assertThat(page.getTotalElements()).isEqualTo(10_000);
```

`Page<Order>` trả về đúng 20 phần tử — nhìn qua tưởng như phân trang hoạt động bình thường. Nhưng bắt log của category `org.hibernate.orm.query` bằng một `ListAppender` (Logback) trong lúc chạy, ta thấy:

```
WARN org.hibernate.orm.query -- HHH90003004: firstResult/maxResults specified with collection fetch; applying in memory
```

> Ghi chú phiên bản: roadmap gọi mã cảnh báo này là `HHH000104` (đúng với Hibernate 5 cũ). Ở Hibernate 6 (`hibernate-core 6.2.5`, dùng trong project này), mã đã đổi thành `HHH90003004` do Hibernate đổi hệ thống đánh số message trong phiên bản 6 — nhưng **nội dung cảnh báo và cơ chế gây ra nó giữ nguyên**, vẫn đúng tinh thần bài học.

Và bằng chứng cụ thể hơn cả dòng log — đếm trực tiếp số row mà câu `SELECT` (không tính count query) thực sự trả về, qua Hibernate `Statistics`:

```java
String selectHql = "SELECT o FROM Order o JOIN FETCH o.items";
assertThat(statistics().getQueryStatistics(selectHql).getExecutionRowCount()).isEqualTo(10_000);
```

**10.000**, không phải 20.

> Về việc "đo heap": đo trực tiếp byte heap JVM (`Runtime.getRuntime().totalMemory() - freeMemory()`) trong một test JUnit rất nhiễu — phụ thuộc thời điểm GC chạy, JIT warm-up, các allocation không liên quan khác trong cùng tiến trình — nên không cho ra con số ổn định để `assert`. `getExecutionRowCount()` đo trực tiếp **số row JDBC thực sự được Hibernate xử lý** cho câu query đó — đây chính là đại lượng quyết định áp lực bộ nhớ (mỗi row tương ứng dữ liệu phải nạp thành object Java), tất định, tái lập được ở bất kỳ máy nào, nên được dùng thay cho phép đo heap thô.

Log Hibernate xác nhận không có `LIMIT`/`OFFSET` nào trong câu SQL sinh ra:

```sql
select o1_0.id, o1_0.customer_name, i1_0.order_id, i1_0.id, i1_0.product_sku, i1_0.quantity, o1_0.status
from orders o1_0
join order_items i1_0 on o1_0.id = i1_0.order_id
-- KHONG co LIMIT 20 OFFSET 0 o day
```

Hibernate đã âm thầm nạp **toàn bộ** 10.000 order (kèm item) vào bộ nhớ JVM, rồi mới cắt lấy 20 phần tử đầu để trả về `Page`. Việc "phân trang" chỉ còn là ảo giác ở tầng API — chi phí thực tế (thời gian query, băng thông, bộ nhớ) là của **toàn bộ tập kết quả**, bất kể `Pageable` yêu cầu 20 hay 20.000 phần tử.

## Vì sao Hibernate buộc phải làm vậy — không phải bug, mà là bắt buộc về mặt toán học

`LIMIT`/`OFFSET` áp dụng ở tầng SQL hoạt động trên **row của kết quả JOIN**, không phải trên **entity cha**. Với `JOIN FETCH` một collection, mỗi order có N item sẽ sinh ra N row liên tiếp trong kết quả (đây chính là cartesian product đã thấy ở mục 9). Nếu Hibernate áp `LIMIT 20` trực tiếp ở tầng SQL, nó sẽ cắt đúng 20 **row**, hoàn toàn có thể rơi vào **giữa chừng** danh sách item của order thứ 20 — order đó xuất hiện trong kết quả với chỉ một phần con của nó, sai hoàn toàn về mặt dữ liệu (một collection "bị cụt").

Không có cách nào biết trước ở tầng SQL rằng cần bao nhiêu row để đủ "20 order trọn vẹn" mà không đếm trước — vì mỗi order có thể có số lượng item khác nhau. Trước sự bất khả thi này, Hibernate chọn phương án **an toàn về dữ liệu nhưng đắt về hiệu năng**: bỏ qua `LIMIT`/`OFFSET` ở SQL, nạp hết, rồi cắt trang bằng vòng lặp Java trên list đã đầy đủ (nơi ranh giới giữa các order rõ ràng, cắt ở đâu cũng đúng).

Đây là **ranh giới rõ nhất giữa người dùng JPA và người hiểu JPA**: người dùng JPA thấy `Page<Order>` trả về đúng, tưởng mọi thứ ổn; người hiểu JPA biết cách kiểm tra log/statistics để phát hiện Hibernate vừa âm thầm tải toàn bộ bảng vào RAM chỉ để phục vụ một trang 20 phần tử.

## Sửa bằng two-query pattern

```java
// Query 1: CHI id, khong JOIN FETCH gi ca -> Pageable ap dung LIMIT/OFFSET
// binh thuong o tang SQL vi khong co collection nao bi "cat cut" ca.
@Query("SELECT o.id FROM Order o ORDER BY o.id")
Page<Long> findOrderIdsPaged(Pageable pageable);

// Query 2: JOIN FETCH nhung KHONG dung Pageable/firstResult/maxResults - chi
// bi gioi han boi danh sach id (da phan trang tu query 1).
@Query("SELECT DISTINCT o FROM Order o JOIN FETCH o.items WHERE o.id IN :ids")
List<Order> findAllWithItemsByIdIn(@Param("ids") List<Long> ids);
```

```java
public Page<Order> findOrdersWithItemsPaged(Pageable pageable) {
    Page<Long> idPage = orderRepository.findOrderIdsPaged(pageable);       // query 1
    List<Long> ids = idPage.getContent();

    List<Order> orders = orderRepository.findAllWithItemsByIdIn(ids);      // query 2
    orders.sort(Comparator.comparing(order -> ids.indexOf(order.getId()))); // IN khong giu thu tu

    return new PageImpl<>(orders, pageable, idPage.getTotalElements());
}
```

```java
Page<Order> page = orderPageService.findOrdersWithItemsPaged(PageRequest.of(0, 20));

assertThat(page.getContent()).hasSize(20);
assertThat(page.getTotalElements()).isEqualTo(10_000);

String selectHql = "SELECT DISTINCT o FROM Order o JOIN FETCH o.items WHERE o.id IN :ids";
assertThat(statistics().getQueryStatistics(selectHql).getExecutionRowCount()).isEqualTo(20);

assertThat(logAppender.list)
        .noneMatch(event -> event.getFormattedMessage().contains(IN_MEMORY_PAGINATION_WARNING));
```

Lần này `getExecutionRowCount()` của câu JOIN FETCH trả về đúng **20** — không phải 10.000 — và không còn dòng cảnh báo nào. Bí quyết: tách "xác định trang nào" (một query đơn giản chỉ chọn `id`, hoàn toàn an toàn để `LIMIT`/`OFFSET` vì không có collection nào bị join) ra khỏi "nạp đầy đủ graph cho đúng những id đó" (một `JOIN FETCH` không hề dùng `Pageable`, nên không có gì để Hibernate phải lo lắng về việc cắt cụt — số lượng id đưa vào `WHERE id IN` đã tự nhiên giới hạn kết quả).

## So sánh

| Cách | Row thực tế xử lý cho 1 trang 20 phần tử | Cảnh báo HHH |
| :--- | :---: | :---: |
| `JOIN FETCH` + `Pageable` trực tiếp | 10.000 (toàn bộ bảng) | có |
| Two-query pattern | 20 (đúng bằng kích thước trang) | không |

Cái giá của two-query pattern: 2 round-trip DB thay vì 1 (cộng thêm 1 query đếm tổng cho `Page`, nên thực tế là 3) — nhưng mỗi round-trip đều **rẻ và có giới hạn rõ ràng** (`LIMIT 20` thật sự ở query 1, `IN` với tối đa 20 giá trị ở query 2), thay vì một round-trip duy nhất nhưng tải toàn bộ dữ liệu.

## Kết quả chạy

```
Tests run: 2, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```
