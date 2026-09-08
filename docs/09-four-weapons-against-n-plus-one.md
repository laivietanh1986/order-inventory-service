# 9. Bốn vũ khí chống N+1 và giới hạn của từng cái

Tương ứng mục 9 trong [`Lo_trinh_hoc_JPA.md`](../Lo_trinh_hoc_JPA.md).

## Mục tiêu

Lần lượt áp dụng `JOIN FETCH`, `@EntityGraph`, `@BatchSize(size = 20)`, `@Fetch(SUBSELECT)` lên đúng query ở mục 8. Ghi lại số query và hình dạng SQL sinh ra.

Khái niệm cần nắm: `JOIN FETCH` sinh cartesian product, `@BatchSize` biến N thành N/20, `SUBSELECT` giữ query gốc làm subquery — không có cách nào thắng tuyệt đối.

## Vì sao cần entity riêng cho 2 trong 4 kỹ thuật

`JOIN FETCH` và `@EntityGraph` là kỹ thuật **ở tầng query** — chỉ cần thêm method mới vào repository, không đụng đến entity mapping, nên áp dụng thẳng lên `Order`/`OrderItem` thật, không ảnh hưởng gì tới test `isEqualTo(101)` đã chốt ở mục 8 (nó chỉ gọi `findAll()`, không đổi).

`@BatchSize` và `@Fetch(SUBSELECT)` ngược lại là annotation gắn **lên chính mapping của collection** — nếu gắn trực tiếp lên `Order.items`, mọi lazy-load của `items` (kể cả trong test mục 8) sẽ tự động đổi hành vi, phá vỡ assertion `101` đã chốt. Vì vậy hai kỹ thuật này dùng cặp entity demo riêng (`BatchSizeOrder`/`BatchSizeOrderItem`, `SubselectOrder`/`SubselectOrderItem`), cùng hình dạng dữ liệu (100 order × 2 item) để so sánh công bằng.

## Các file liên quan

| File | Vai trò |
| :--- | :--- |
| [`OrderRepository`](../src/main/java/com/example/orderinventory/order/OrderRepository.java) | Thêm `findAllWithItemsJoinFetch`, `findAllWithItemsJoinFetchNoDistinct`, `findAllWithItemsEntityGraph` |
| [`BatchSizeOrder`](../src/main/java/com/example/orderinventory/nplusone/BatchSizeOrder.java) | `@OneToMany` + `@BatchSize(size = 20)` |
| [`SubselectOrder`](../src/main/java/com/example/orderinventory/nplusone/SubselectOrder.java) | `@OneToMany` + `@Fetch(FetchMode.SUBSELECT)` |
| [`V8__create_nplusone_demo_tables.sql`](../src/main/resources/db/migration/V8__create_nplusone_demo_tables.sql) | Bảng cho 2 entity demo |
| [`JoinFetchAndEntityGraphTest.java`](../src/test/java/com/example/orderinventory/order/JoinFetchAndEntityGraphTest.java) | Vũ khí 1 và 2 |
| [`BatchSizeAndSubselectTest.java`](../src/test/java/com/example/orderinventory/nplusone/BatchSizeAndSubselectTest.java) | Vũ khí 3 và 4 |

## Cách chạy

```bash
./mvnw test -Dtest=JoinFetchAndEntityGraphTest,BatchSizeAndSubselectTest
```

## Vũ khí 1 — `JOIN FETCH`: 1 query, nhưng là cartesian product

```java
@Query("SELECT DISTINCT o FROM Order o JOIN FETCH o.items")
List<Order> findAllWithItemsJoinFetch();
```

```java
List<Order> orders = orderRepository.findAllWithItemsJoinFetch();
assertThat(orders).hasSize(100);
assertThat(statistics().getPrepareStatementCount()).isEqualTo(1);
```

Chỉ 1 statement — N+1 biến mất hoàn toàn. Nhưng "1 query" không đồng nghĩa "rẻ": bản chất SQL sinh ra là một `LEFT JOIN` giữa `orders` và `order_items`, và một phép join giữa bảng cha (100 row) với bảng con (2 row/cha) luôn sinh ra **100 × 2 = 200 tổ hợp (row)** ở tầng quan hệ — chứng minh trực tiếp bằng một query độc lập:

```java
Long joinRowCount = ((Number) entityManager.getEntityManager()
        .createNativeQuery("SELECT COUNT(*) FROM order_items i JOIN orders o ON o.id = i.order_id")
        .getSingleResult()).longValue();

assertThat(joinRowCount).isEqualTo(200); // dung 200, khong phai 100
```

Đây chính là ý nghĩa "**JOIN FETCH sinh cartesian product**": số row JDBC phải truyền qua mạng và được Hibernate xử lý tỉ lệ với **tích** của số lượng hai phía join, không phải tổng. Với một order có 2 item thì chưa đáng ngại, nhưng nếu `JOIN FETCH` đồng thời 2 collection (`items` và một collection khác), số row sẽ nhân chồng lên nhau — đây cũng là gốc rễ của `MultipleBagFetchException` sẽ gặp ở mục 11.

### Có cần `DISTINCT` không?

```java
List<Order> withoutDistinct = orderRepository.findAllWithItemsJoinFetchNoDistinct(); // SELECT o FROM Order o JOIN FETCH o.items
List<Order> withDistinct = orderRepository.findAllWithItemsJoinFetch();              // SELECT DISTINCT o FROM Order o JOIN FETCH o.items

assertThat(withoutDistinct).hasSize(100);
assertThat(withDistinct).hasSize(100);
```

Cả hai đều trả về đúng 100 phần tử `Order` — **không hề bị lặp** dù bản không có `DISTINCT`. Đây là hành vi của **Hibernate 6**: nó tự động loại trùng entity gốc ở tầng object dựa trên identity trong persistence context, bất kể có từ khoá `DISTINCT` hay không (khác với Hibernate 5 cũ, nơi thiếu `DISTINCT` sẽ trả về 200 phần tử `Order` bị lặp lại trong `List`). Dù vậy, `DISTINCT` vẫn nên giữ trong code vì đó là hành vi được đặc tả chính thức bởi JPA (không phụ thuộc vào một phiên bản Hibernate cụ thể), và ở tầng SQL nó không hề làm giảm số row JOIN thực tế (`joinRowCount` vẫn là 200 trong cả hai trường hợp).

## Vũ khí 2 — `@EntityGraph`: cùng bản chất, khai báo gọn hơn

```java
@EntityGraph(attributePaths = "items")
@Query("SELECT o FROM Order o")
List<Order> findAllWithItemsEntityGraph();
```

```java
List<Order> orders = orderRepository.findAllWithItemsEntityGraph();
assertThat(statistics().getPrepareStatementCount()).isEqualTo(1);
```

`@EntityGraph` là lớp trừu tượng của Spring Data đặt trên method repository, được dịch thành một **fetch graph hint** gắn vào query — về bản chất Hibernate vẫn sinh ra một `LEFT JOIN` y hệt `JOIN FETCH` tường minh, chỉ khác cách khai báo (không cần viết JPQL tay, dùng được cả với derived query method). Nó **thừa hưởng đúng giới hạn của `JOIN FETCH`**: vẫn là 1 query, vẫn cùng kiểu cartesian product 200 row nói trên — chỉ là cú pháp khai báo khác, không phải cơ chế khác.

## Vũ khí 3 — `@BatchSize(size = 20)`: biến N thành N/20

```java
@OneToMany(mappedBy = "order")
@BatchSize(size = 20)
private List<BatchSizeOrderItem> items = new ArrayList<>();
```

```java
List<BatchSizeOrder> orders = batchSizeOrderRepository.findAll(); // 1 query
for (BatchSizeOrder order : orders) {
    order.getItems().size(); // lazy-load, nhung theo LO 20
}

assertThat(statistics().getPrepareStatementCount()).isEqualTo(6); // 1 + ceil(100/20)
```

Log Hibernate:

```sql
select bso1_0.id, bso1_0.customer_name from batch_size_orders bso1_0     -- 1: findAll()

select i1_0.order_id, i1_0.id, i1_0.product_sku from batch_size_order_items i1_0
where i1_0.order_id in (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)  -- lo 1 (20 id)
... (lap lai 5 lan, moi lan 20 id) ...
```

Collection vẫn LAZY như bình thường, nhưng khi phần tử lazy **đầu tiên** trong lô cần khởi tạo, Hibernate không chỉ nạp riêng cho một `order` — nó gom tối đa 20 `order_id` đang "chờ nạp items" trong cùng persistence context vào một câu `WHERE order_id IN (...)`. Với 100 order, đúng `⌈100/20⌉ = 5` lô, cộng thêm 1 query `findAll()` ban đầu = **6 query** thay vì 101. Đây chính là ý nghĩa "**biến N thành N/20**" — không xoá bỏ hoàn toàn N+1, chỉ **chia nhỏ hệ số N** theo kích thước lô cấu hình.

Giới hạn: batch size là con số cố định chọn trước (ở đây 20) — chọn quá nhỏ thì vẫn còn nhiều round-trip, chọn quá lớn thì mỗi câu `IN (...)` lại phình to số tham số bind, và một số DB có giới hạn cứng về số phần tử trong mệnh đề `IN`.

## Vũ khí 4 — `@Fetch(FetchMode.SUBSELECT)`: giữ nguyên query gốc làm subquery

```java
@OneToMany(mappedBy = "order")
@Fetch(FetchMode.SUBSELECT)
private List<SubselectOrderItem> items = new ArrayList<>();
```

```java
List<SubselectOrder> orders = subselectOrderRepository.findAll(); // 1 query
for (SubselectOrder order : orders) {
    order.getItems().size();
}

assertThat(statistics().getPrepareStatementCount()).isEqualTo(2); // 1 + 1
```

Log Hibernate:

```sql
select so1_0.id, so1_0.customer_name from subselect_orders so1_0     -- 1: findAll()

select i1_0.order_id, i1_0.id, i1_0.product_sku
from subselect_order_items i1_0
where i1_0.order_id in (                                             -- 1 DUY NHAT cho TAT CA item
    select so1_0.id from subselect_orders so1_0                      -- CHINH LA query goc, lam subquery
)
```

Đây là kỹ thuật cho số query **thấp nhất trong 4 vũ khí**: chỉ 2 câu SQL bất kể có 100 hay 10.000 order. Cơ chế: khi collection lazy đầu tiên cần khởi tạo, thay vì gom theo lô cố định (như `@BatchSize`), Hibernate **chạy lại chính điều kiện của câu query đã dùng để nạp các entity cha** (ở đây là `findAll()`, không có `WHERE`), bọc nó thành một subquery để lọc `order_id`, và nạp **toàn bộ items của toàn bộ order đang có trong persistence context** trong một lần.

Giới hạn chính là câu "**giữ query gốc làm subquery**" theo đúng nghĩa đen: nếu query cha ban đầu phức tạp (nhiều `JOIN`, `WHERE` nặng, `ORDER BY` tốn kém), subquery đó bị lặp lại y hệt — không phải một `IN (list các id đã biết)` rẻ tiền, mà là toàn bộ chi phí của query gốc, chạy thêm một lần nữa. Với `findAll()` đơn giản như ở đây, cái giá này gần như miễn phí; với một query nghiệp vụ thật có nhiều điều kiện lọc, `SUBSELECT` có thể âm thầm nhân đôi chi phí của chính câu query đó.

## So sánh tổng quan (100 order × 2 item)

| Kỹ thuật | Số query | Đặc điểm SQL | Giới hạn chính |
| :--- | :---: | :--- | :--- |
| `JOIN FETCH` | 1 | 1 `LEFT JOIN`, nhưng trả về 200 row (cartesian) | row set phình to theo tích số lượng hai phía; nhân chồng nếu fetch nhiều collection cùng lúc |
| `@EntityGraph` | 1 | tương đương `JOIN FETCH`, khai báo qua annotation | thừa hưởng đúng giới hạn của `JOIN FETCH` |
| `@BatchSize(20)` | 6 (`1 + ⌈100/20⌉`) | nhiều `SELECT ... WHERE id IN (tối đa 20 giá trị)` | vẫn còn nhiều round-trip nếu batch size nhỏ so với N |
| `@Fetch(SUBSELECT)` | 2 (`1 + 1`) | 1 `SELECT ... WHERE id IN (subquery = chính query gốc)` | subquery lặp lại toàn bộ chi phí của query cha nếu query cha phức tạp |

**Không có kỹ thuật nào thắng tuyệt đối** — mỗi cách đánh đổi giữa số round-trip, kích thước dữ liệu truyền qua mạng, và chi phí thực thi lại một phần logic truy vấn:

- Cần **đúng 1 query** và chấp nhận băng thông lớn hơn (ít item/order) → `JOIN FETCH`/`@EntityGraph`.
- Query cha đơn giản, muốn **ít query nhất có thể** bất kể N lớn cỡ nào → `@Fetch(SUBSELECT)`.
- Query cha phức tạp/tốn kém (không muốn chạy lại), chấp nhận vài round-trip theo lô → `@BatchSize`.
- Không collection nào cần nạp trước cả (chỉ cần vài field của entity cha) → không dùng kỹ thuật nào trong 4 cái này, dùng DTO projection (mục 12).

## Kết quả chạy

```
Tests run: 3, Failures: 0, Errors: 0, Skipped: 0   -- JoinFetchAndEntityGraphTest
Tests run: 2, Failures: 0, Errors: 0, Skipped: 0   -- BatchSizeAndSubselectTest
BUILD SUCCESS
```
