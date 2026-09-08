# 3. Cascade và orphanRemoval

Tương ứng mục 3 trong [`Lo_trinh_hoc_JPA.md`](../Lo_trinh_hoc_JPA.md).

## Mục tiêu

Thử 4 kịch bản: xoá `Order` khi không cascade, khi `CascadeType.REMOVE`, khi `orphanRemoval = true` nhưng chỉ `items.remove(0)`, và khi cả hai.

Cố tình đặt `CascadeType.ALL` trên `OrderItem → Product` rồi xoá order → quan sát `Product` bị xoá theo.

Khái niệm cần nắm: `REMOVE` chỉ kích hoạt khi xoá parent, `orphanRemoval` kích hoạt cả khi tách phần tử khỏi collection, cascade chỉ đúng với quan hệ lifecycle-dependent.

## Vì sao cần 4 cặp entity riêng biệt

Cascade là một **thuộc tính khai báo tĩnh trên annotation**, không phải thứ có thể bật/tắt lúc runtime theo từng test. Muốn so sánh 4 cấu hình cạnh nhau mà không cấu hình nào ảnh hưởng cấu hình kia, mỗi kịch bản cần một cặp entity/bảng độc lập:

| File | Cấu hình | Bảng |
| :--- | :--- | :--- |
| [`NoCascadeOrder`](../src/main/java/com/example/orderinventory/cascade/NoCascadeOrder.java) / [`NoCascadeOrderItem`](../src/main/java/com/example/orderinventory/cascade/NoCascadeOrderItem.java) | không cascade, không orphanRemoval | `no_cascade_orders` / `no_cascade_order_items` |
| [`CascadeRemoveOrder`](../src/main/java/com/example/orderinventory/cascade/CascadeRemoveOrder.java) / [`CascadeRemoveOrderItem`](../src/main/java/com/example/orderinventory/cascade/CascadeRemoveOrderItem.java) | `cascade = CascadeType.REMOVE` | `cascade_remove_orders` / `cascade_remove_order_items` |
| [`OrphanRemovalOrder`](../src/main/java/com/example/orderinventory/cascade/OrphanRemovalOrder.java) / [`OrphanRemovalOrderItem`](../src/main/java/com/example/orderinventory/cascade/OrphanRemovalOrderItem.java) | `cascade = PERSIST, orphanRemoval = true` | `orphan_removal_orders` / `orphan_removal_order_items` |
| `Order` / `OrderItem` (mục 2, tái sử dụng) | `cascade = ALL, orphanRemoval = true` | `orders` / `order_items` |
| [`BadCascadeOrder`](../src/main/java/com/example/orderinventory/cascade/BadCascadeOrder.java) / [`BadCascadeOrderItem`](../src/main/java/com/example/orderinventory/cascade/BadCascadeOrderItem.java) | anti-pattern: `OrderItem.product` có `cascade = ALL` | `bad_cascade_orders` / `bad_cascade_order_items` |

Migration: [`V3__create_cascade_demo_tables.sql`](../src/main/resources/db/migration/V3__create_cascade_demo_tables.sql).
Test: [`CascadeAndOrphanRemovalTest.java`](../src/test/java/com/example/orderinventory/cascade/CascadeAndOrphanRemovalTest.java).

## Cách chạy

```bash
./mvnw test -Dtest=CascadeAndOrphanRemovalTest
```

## Kịch bản 1 — không cascade gì cả

```java
NoCascadeOrder order = new NoCascadeOrder("Nguyen Van A");
NoCascadeOrderItem item = new NoCascadeOrderItem("SKU-100");
order.addItem(item);

entityManager.persist(order);
entityManager.persist(item); // KHÔNG cascade PERSIST -> phải tự persist cả hai

...

assertThatThrownBy(() -> {
    entityManager.remove(reloaded);
    entityManager.flush();
}).isInstanceOf(PersistenceException.class);
```

Không có cascade nghĩa là **mọi thao tác vòng đời phải tự làm qua đúng entity đó**, Hibernate không "suy luận hộ" gì cả — kể cả `persist()`. Khi xoá `order` trong lúc `item` vẫn còn FK trỏ tới nó, DB chặn lại bằng lỗi ràng buộc khoá ngoại:

```
Referential integrity constraint violation: "... FOREIGN KEY(ORDER_ID) REFERENCES ... NO_CASCADE_ORDERS(ID) ..."
```

Đây là hành vi **đúng và an toàn** theo nghĩa "fail-fast" — dữ liệu không bao giờ bị mất một cách âm thầm, nhưng đổi lại code tầng service phải tự lo xoá con trước khi xoá cha.

## Kịch bản 2 — chỉ `CascadeType.REMOVE`

### 2a. Tách khỏi collection — KHÔNG có gì xảy ra

```java
reloaded.getItems().remove(0); // chỉ tách khỏi collection, KHÔNG xoá order
entityManager.flush();

assertThat(entityManager.find(CascadeRemoveOrderItem.class, itemId)).isNotNull(); // vẫn còn
```

`cascade = REMOVE` chỉ lắng nghe **một sự kiện duy nhất**: `entityManager.remove()` được gọi trực tiếp trên entity cha. Việc gọi `list.remove(...)` trong Java hoàn toàn không đi qua sự kiện đó — nó chỉ thay đổi state trong bộ nhớ của collection, y hệt bài học `mappedBy` ở mục 2: **owning side (`item.order`) mới quyết định SQL**, và ở đây ta còn chưa hề đụng tới field đó.

### 2b. Xoá chính order — cascade kích hoạt, xoá sạch toàn bộ item

```java
entityManager.remove(reloaded); // xoá CHÍNH order
entityManager.flush();

assertThat(entityManager.find(CascadeRemoveOrderItem.class, item1Id)).isNull();
assertThat(entityManager.find(CascadeRemoveOrderItem.class, item2Id)).isNull();
```

Log Hibernate xác nhận: 1 `SELECT` để Hibernate nạp toàn bộ `items` hiện có trong DB (nó cần biết chính xác những gì cần cascade), sau đó 2 `DELETE` cho từng item, rồi mới `DELETE` cho order.

## Kịch bản 3 — chỉ `orphanRemoval = true` (cascade chỉ có `PERSIST`)

```java
reloaded.getItems().remove(0); // KHÔNG xoá order, chỉ tách 1 phần tử
entityManager.flush();

assertThat(entityManager.find(OrphanRemovalOrderItem.class, item1Id)).isNull(); // đã bị xoá
assertThat(entityManager.find(OrphanRemovalOrder.class, order.getId())).isNotNull(); // order vẫn còn
```

Đây là điểm khác biệt cốt lõi so với `CascadeType.REMOVE`: `orphanRemoval` không quan tâm entity cha có bị xoá hay không, nó theo dõi trực tiếp **collection của entity đang managed** — hễ phát hiện một phần tử "biến mất" khỏi collection ở lần flush kế tiếp (so với snapshot trước đó), nó hiểu phần tử đó đã bị "mồ côi" và tự xoá luôn khỏi DB.

## Kịch bản 4 — cả hai (`Order`/`OrderItem` thật, `cascade = ALL` + `orphanRemoval = true`)

```java
// (a) tách 1 item khỏi collection -> orphanRemoval tự xoá nó
reloaded.getItems().remove(0);
entityManager.flush();
assertThat(entityManager.find(OrderItem.class, item1Id)).isNull();

// (b) xoá cả order -> REMOVE cascade xoá nốt item còn lại
entityManager.remove(reloaded);
entityManager.flush();
assertThat(entityManager.find(OrderItem.class, item2Id)).isNull();
```

Hai cơ chế này **cộng dồn, không loại trừ nhau**: `orphanRemoval` lo trường hợp "tách khỏi collection", `REMOVE` (nằm trong `CascadeType.ALL`) lo trường hợp "xoá cả cha". Đây là cấu hình đúng cho một aggregate mà `OrderItem` không có lý do gì để tồn tại độc lập ngoài `Order` sở hữu nó (đúng tinh thần DDD: `Order` là aggregate root, `OrderItem` là phần tử bên trong aggregate).

## So sánh tổng quan

| Cấu hình | Xoá parent | Tách khỏi collection (không xoá parent) |
| :--- | :--- | :--- |
| Không cascade | ❌ lỗi FK constraint | không đổi gì trong DB |
| `CascadeType.REMOVE` | ✅ xoá theo | không đổi gì trong DB |
| `orphanRemoval = true` | ✅ xoá theo (xem ghi chú) | ✅ tự xoá phần tử bị tách |
| Cả hai (`CascadeType.ALL` + `orphanRemoval`) | ✅ xoá theo | ✅ tự xoá phần tử bị tách |

> Ghi chú: về mặt hành vi, `orphanRemoval = true` một mình cũng khiến children bị xoá khi xoá parent (Hibernate coi việc xoá parent là "tách toàn bộ collection" — tương đương disassociate hàng loạt). Điểm khác biệt thật sự giữa `orphanRemoval` và `CascadeType.REMOVE` nằm ở cột thứ hai: chỉ `orphanRemoval` phản ứng với việc tách **một phần tử** ra khỏi collection.

## Anti-pattern: `CascadeType.ALL` từ `OrderItem` sang `Product`

```java
@ManyToOne(cascade = CascadeType.ALL)
@JoinColumn(name = "product_id", nullable = false)
private Product product;
```

```java
entityManager.remove(reloaded); // xoá order
entityManager.flush();

assertThat(entityManager.find(Product.class, productId)).isNull(); // Product cũng biến mất!
```

Log Hibernate cho thấy chuỗi cascade lan truyền qua **hai tầng quan hệ liên tiếp**:

```sql
delete from bad_cascade_order_items where id=?   -- cascade REMOVE (trong ALL) từ Order -> OrderItem
delete from products where id=?                  -- cascade REMOVE (trong ALL) từ OrderItem -> Product
delete from bad_cascade_orders where id=?
```

Xoá một đơn hàng khiến sản phẩm trong danh mục biến mất — đây gần như chắc chắn là một bug nghiêm trọng trong hệ thống thật (sản phẩm còn có thể nằm trong hàng chục đơn hàng khác, hoặc đơn giản là vẫn cần tồn tại trong catalog dù đơn hàng này đã bị xoá).

## Nguyên tắc rút ra: cascade chỉ đúng cho quan hệ lifecycle-dependent

Cascade (đặc biệt `REMOVE`/`ALL`) chỉ nên đặt trên quan hệ mà **con không có lý do tồn tại độc lập với cha** — quan hệ "phần-của" (part-of) đúng nghĩa aggregate trong DDD:

- `Order → OrderItem`: đúng. Một `OrderItem` tách rời khỏi `Order` là vô nghĩa.
- `OrderItem → Product`: sai. `Product` là entity độc lập, có vòng đời riêng, được nhiều `OrderItem`/`Order` khác tham chiếu tới. Quan hệ đúng ở đây chỉ nên là **tham chiếu** (không cascade gì, cùng lắm `cascade = {}` hoặc không khai báo), không phải sở hữu.

Câu hỏi cần tự đặt trước khi thêm `cascade = ALL` hoặc `orphanRemoval = true` vào bất kỳ `@ManyToOne`/`@OneToMany` nào: *"Nếu xoá cha, con có còn lý do gì để tồn tại không?"* — nếu câu trả lời là "có" (như `Product` vẫn cần tồn tại dù đơn hàng nào đó bị xoá), thì không cascade.

## Kết quả chạy

```
Tests run: 6, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

(Dòng `ERROR ... Referential integrity constraint violation` xuất hiện trong log khi chạy `mvn test` là log do chính Hibernate/H2 in ra cho kịch bản 1 — đây là lỗi được **cố tình** tạo ra và test assert đúng vào nó, không phải test thất bại.)
