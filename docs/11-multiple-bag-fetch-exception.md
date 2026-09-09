# 11. MultipleBagFetchException

Tương ứng mục 11 trong [`Lo_trinh_hoc_JPA.md`](../Lo_trinh_hoc_JPA.md).

## Mục tiêu

`JOIN FETCH` đồng thời `items` và `statusHistory` (cả hai là `List`) → lỗi ngay lúc khởi động.

Sửa bằng 2 cách: đổi sang `Set`, hoặc tách thành 2 query để Hibernate tự merge vào persistence context.

Khái niệm cần nắm: Hibernate không phân biệt được duplicate row của hai bag trong cùng result set.

## Entity mới: `OrderStatusHistory`

Đây là lần đầu domain model đụng tới `OrderStatusHistory` (đã liệt kê từ đầu lộ trình nhưng chưa cài đặt). Thêm vào `Order` một collection thứ hai, cũng là `List` (bag) như `items`:

```java
@OneToMany(mappedBy = "order", cascade = CascadeType.ALL, orphanRemoval = true)
private List<OrderItem> items = new ArrayList<>();

// Lich su trang thai la nhat ky (audit trail): chi PERSIST, khong REMOVE/
// orphanRemoval - khong ai duoc phep "xoa" mot dong lich su da ghi.
@OneToMany(mappedBy = "order", cascade = CascadeType.PERSIST)
private List<OrderStatusHistory> statusHistory = new ArrayList<>();
```

`OrderStatusHistory` chỉ `cascade = PERSIST` (không `REMOVE`/`orphanRemoval`) vì bản chất là nhật ký — một dòng lịch sử đã ghi thì không có lý do gì để bị xoá cùng lúc với các thao tác khác trên `Order` (khác với `OrderItem`, thứ thực sự "sống chết theo" `Order`, xem mục 3).

## Các file liên quan

| File | Vai trò |
| :--- | :--- |
| [`OrderStatusHistory.java`](../src/main/java/com/example/orderinventory/order/OrderStatusHistory.java) | Entity mới |
| [`Order.statusHistory`](../src/main/java/com/example/orderinventory/order/Order.java) | Bag thứ hai + helper `addStatusHistory()` |
| [`V9__create_order_status_history_table.sql`](../src/main/resources/db/migration/V9__create_order_status_history_table.sql) | Bảng `order_status_history` |
| [`MultipleBagFetchExceptionTest.java`](../src/test/java/com/example/orderinventory/order/MultipleBagFetchExceptionTest.java) | Tái hiện lỗi + 2 cách sửa, dùng entity thật |
| [`BagFetchFixParent`](../src/main/java/com/example/orderinventory/bagfetch/BagFetchFixParent.java) + `BagFetchFixItemA`/`BagFetchFixItemB` | Entity demo cho Fix 1 |
| [`V10__create_bagfetch_fix_tables.sql`](../src/main/resources/db/migration/V10__create_bagfetch_fix_tables.sql) | Bảng cho demo Fix 1 |

## Cách chạy

```bash
./mvnw test -Dtest=MultipleBagFetchExceptionTest
```

## Tái hiện lỗi

```java
EntityManager em = entityManager.getEntityManager();

var query = em.createQuery(
        "SELECT o FROM Order o JOIN FETCH o.items JOIN FETCH o.statusHistory");

assertThatThrownBy(query::getResultList)
        .isInstanceOf(IllegalArgumentException.class)
        .hasRootCauseInstanceOf(MultipleBagFetchException.class)
        .hasStackTraceContaining("cannot simultaneously fetch multiple bags");
```

Điểm đáng chú ý: `em.createQuery(...)` **thành công** — lỗi chỉ nổ ra khi gọi `getResultList()`, tức là lúc Hibernate thực sự biên dịch JPQL thành kế hoạch thực thi (query plan), không phải lúc parse cú pháp JPQL ban đầu.

```
org.hibernate.loader.MultipleBagFetchException: cannot simultaneously fetch multiple bags:
[com.example.orderinventory.order.Order.items, com.example.orderinventory.order.Order.statusHistory]
```

### Vì sao "lỗi ngay lúc khởi động" chỉ đúng cho `@NamedQuery`

Nếu JOIN FETCH lỗi này được khai báo dưới dạng `@NamedQuery` trên entity (thay vì `@Query` inline trên method repository như phần lớn project này), Hibernate sẽ **biên dịch và validate TẤT CẢ named query ngay lúc bootstrap `SessionFactory`** — nghĩa là lỗi xuất hiện ngay khi ứng dụng khởi động (`EntityManagerFactory` bean creation thất bại), trước khi bất kỳ request nào được xử lý. Đây là hành vi Hibernate chính thức và được tài liệu hoá rõ ràng.

> **Ghi chú minh bạch**: khi thử tái hiện chính xác kịch bản này bằng một Spring context cô lập (`ApplicationContextRunner` + entity riêng, để không ảnh hưởng tới context dùng chung của cả project), kết quả lại **không ổn định giữa các lần chạy** — cùng một đoạn code, có lúc Hibernate báo đúng `MultipleBagFetchException` lúc bootstrap, có lúc lại báo nhầm `UnknownEntityException: Could not resolve root entity` cho một entity hoàn toàn hợp lệ. Đây nhiều khả năng là một vấn đề nội bộ của Hibernate 6.2.5 liên quan đến thứ tự nạp lớp/cache của trình phân tích ANTLR khi nhiều `SessionFactory` được dựng liên tiếp trong cùng một JVM, không liên quan đến đúng/sai trong cách viết `@NamedQuery`. Vì một test không ổn định còn tệ hơn không có test, kịch bản này **chỉ được ghi lại ở đây dưới dạng quan sát/tài liệu**, không được đưa vào bộ test tự động của project — đúng tinh thần "đo được thì mới tin, không đo được thì không khẳng định" xuyên suốt các bài trước.

## Vì sao lỗi xảy ra — bag không có định danh từng row

Nhắc lại từ mục 6: một `List` không có `@OrderColumn` được Hibernate coi là **bag** — không có thông tin gì để phân biệt "vị trí" hay "định danh" của từng phần tử trong kết quả trả về. Khi JOIN FETCH **hai** bag cùng lúc:

```sql
SELECT ... FROM orders o
JOIN order_items i ON i.order_id = o.id
JOIN order_status_history h ON h.order_id = o.id
```

Với một order có 2 `item` và 2 dòng `statusHistory`, phép JOIN kép này sinh ra `2 × 2 = 4` row. Hibernate phải "bóc" 4 row này ra để biết row nào ghép với `item` nào, row nào ghép với `statusHistory` nào — nhưng vì **cả hai collection đều là bag** (không có cột định danh vị trí), Hibernate **không có cách nào phân biệt** đâu là sự trùng lặp hợp lệ (do JOIN kép) và đâu là dữ liệu thật. Kết quả duy nhất an toàn là từ chối thẳng, ném `MultipleBagFetchException`, thay vì âm thầm trả về dữ liệu sai (ví dụ item bị nhân bản, hoặc statusHistory bị gán nhầm).

## Fix 2 — tách thành 2 query riêng biệt (đã áp dụng cho `Order` thật)

```java
// Query 1: chi JOIN FETCH items - an toan, khong co bag thu hai nao.
Order withItems = em.createQuery(
                "SELECT o FROM Order o JOIN FETCH o.items WHERE o.id = :id", Order.class)
        .setParameter("id", orderId)
        .getSingleResult();

// Query 2: chi JOIN FETCH statusHistory - cung an toan tuong tu.
Order withHistory = em.createQuery(
                "SELECT o FROM Order o JOIN FETCH o.statusHistory WHERE o.id = :id", Order.class)
        .setParameter("id", orderId)
        .getSingleResult();

assertThat(withItems).isSameAs(withHistory);
assertThat(withItems.getItems()).hasSize(2);
assertThat(withItems.getStatusHistory()).hasSize(2);
```

Mỗi query riêng lẻ chỉ JOIN FETCH **một** bag — hoàn toàn an toàn, không có gì để nhầm lẫn. Vì cả hai query cùng chạy trong **một persistence context** (cùng một `TestEntityManager`/transaction test), Hibernate áp dụng **identity map**: query 2 trả về id đã có sẵn trong context, nên Hibernate **không tạo object mới** mà trả lại đúng instance Java đã tồn tại từ query 1 (`withItems).isSameAs(withHistory)` xác nhận điều này) — chỉ bổ sung `statusHistory` (kết quả của query 2) vào **đúng object đó**. Sau hai query, `Order` object duy nhất trong bộ nhớ có cả `items` lẫn `statusHistory` đều đã được nạp đầy đủ, dù không query nào JOIN FETCH cả hai cùng lúc.

## Fix 1 — đổi collection sang `Set`

### Đổi một bên — hết lỗi nhưng dữ liệu sai

Thử nghiệm ban đầu (không giữ lại làm test chính thức, chỉ ghi lại phát hiện): đổi **một trong hai** collection (`bagList`) sang `Set`, giữ bên còn lại là `List`. `MultipleBagFetchException` biến mất — Hibernate không còn coi đây là "2 bag", vì `Set` có `equals`/`hashCode` để tự loại trùng. Nhưng kiểm tra kỹ kết quả:

```java
List<FixParent> result = em.createQuery(
        "SELECT DISTINCT p FROM FixParent p JOIN FETCH p.bagList JOIN FETCH p.bagSet",
        FixParent.class).getResultList();

result.get(0).getBagList(); // mong doi 2, thuc te la 4 (moi phan tu lap 2 lan!)
result.get(0).getBagSet();  // dung 2, vi Set tu loai trung
```

Dù đã thêm `DISTINCT` ở tầng JPQL, phía **List** vẫn bị nhân đôi thành 4 phần tử (mỗi phần tử lặp lại đúng 2 lần — bằng số phần tử của phía `Set`). Lý do: cartesian product 2×2=4 row ở tầng SQL vẫn xảy ra y hệt như JOIN FETCH thông thường (mục 9); `DISTINCT` ở đây chỉ khử trùng lặp cho **entity gốc** (`FixParent`, vẫn đúng 1), không hề dọn dẹp trùng lặp **bên trong** một collection kiểu `List` — vì `List` được phép chứa phần tử trùng, Hibernate không có cơ sở nào để coi đó là lỗi cần sửa. Chỉ riêng `Set` (nhờ `equals`/`hashCode`) mới tự loại trùng khi `add()` phần tử đã tồn tại.

### Đổi cả hai bên — mới thực sự triệt để

```java
@OneToMany(mappedBy = "parent", cascade = CascadeType.PERSIST)
private Set<BagFetchFixItemA> itemsA = new HashSet<>();

@OneToMany(mappedBy = "parent", cascade = CascadeType.PERSIST)
private Set<BagFetchFixItemB> itemsB = new HashSet<>();
```

```java
BagFetchFixParent reloaded = bagFetchFixParentRepository.findWithBothSetsById(parent.getId());

assertThat(reloaded.getItemsA()).hasSize(2); // dung
assertThat(reloaded.getItemsB()).hasSize(2); // dung
```

Khi **không còn bên nào là bag**, cả hai collection đều tự loại trùng đúng theo cartesian product 2×2=4 row — `itemsA` và `itemsB` đều về đúng kích thước thật (2 và 2), không cần thêm xử lý gì khác.

## So sánh 2 cách sửa

| Cách | Số query | Rủi ro còn lại |
| :--- | :---: | :--- |
| Đổi **một** collection sang `Set` | 1 | ❌ Collection còn lại (vẫn là `List`) bị nhân đôi phần tử theo cartesian product — chỉ hết exception, chưa hết bug dữ liệu |
| Đổi **cả hai** collection sang `Set` | 1 | ✅ không còn vấn đề gì, nhưng mất thứ tự phần tử (Set không đảm bảo thứ tự) |
| Tách thành 2 query | 2 | ✅ không vấn đề gì, giữ nguyên kiểu `List` (giữ thứ tự nếu cần) |

Kết luận thực hành: nếu **cả hai** collection thực sự cần là `List` có thứ tự (ví dụ hiển thị theo đúng trình tự thêm vào), **tách thành 2 query** (Fix 2) là lựa chọn an toàn hơn — không đánh đổi thứ tự để lấy số query ít hơn. Nếu thứ tự không quan trọng, đổi **cả hai** sang `Set` cho một query duy nhất.

## Kết quả chạy

```
Tests run: 3, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```
