# 2. Bidirectional trap — FK bị null

Tương ứng mục 2 trong [`Lo_trinh_hoc_JPA.md`](../Lo_trinh_hoc_JPA.md).

## Mục tiêu

`Order` ↔ `OrderItem` với `@OneToMany(mappedBy = "order")`. Cố tình chỉ gọi `order.getItems().add(item)` mà không set `item.setOrder(order)`.

Quan sát: `order_id` null hoặc có thêm một câu `UPDATE` thừa. Sau đó thêm helper method `addItem()` và đếm lại số SQL.

Khái niệm cần nắm: owning side giữ foreign key, `mappedBy` chỉ đánh dấu inverse side và không sinh SQL, tại sao helper method là bắt buộc.

## Các file đã tạo

| File | Vai trò |
| :--- | :--- |
| [`Order.java`](../src/main/java/com/example/orderinventory/order/Order.java) | Inverse side (`@OneToMany(mappedBy = "order")`) + helper `addItem()`/`removeItem()` |
| [`OrderItem.java`](../src/main/java/com/example/orderinventory/order/OrderItem.java) | Owning side (`@ManyToOne` + `@JoinColumn(name = "order_id")`) |
| [`OrderRepository.java`](../src/main/java/com/example/orderinventory/order/OrderRepository.java) / [`OrderItemRepository.java`](../src/main/java/com/example/orderinventory/order/OrderItemRepository.java) | `JpaRepository` cho hai entity |
| [`V2__create_orders_and_order_items_tables.sql`](../src/main/resources/db/migration/V2__create_orders_and_order_items_tables.sql) | Flyway migration, cột `order_id` cố tình để nullable |
| [`OrderItemBidirectionalTest.java`](../src/test/java/com/example/orderinventory/order/OrderItemBidirectionalTest.java) | 3 test minh hoạ: bug FK null, bug UPDATE thừa, bản đúng |

## Cách chạy

```bash
./mvnw test -Dtest=OrderItemBidirectionalTest
```

## Owning side vs inverse side

```
Order (INVERSE side)                    OrderItem (OWNING side)
┌─────────────────────────┐             ┌──────────────────────────┐
│ @OneToMany(mappedBy=     │             │ @ManyToOne                │
│   "order")               │◄────────────┤ @JoinColumn(name=         │
│ List<OrderItem> items    │   không FK  │   "order_id")             │
│                          │   ở đây     │ Order order  ── giữ FK    │
└─────────────────────────┘             └──────────────────────────┘
     chỉ để ĐỌC, không                     giá trị field này quyết
     ảnh hưởng SQL insert/update            định order_id lúc flush
```

Quy tắc cốt lõi: trong một quan hệ bidirectional, **luôn có đúng một phía owning** — phía có `@ManyToOne`/`@JoinColumn` (hoặc bên được chỉ định trong `@JoinTable` với `@ManyToMany`). Chỉ owning side quyết định giá trị cột FK khi Hibernate sinh SQL. Phía còn lại đánh dấu `mappedBy = "<tên field owning>"` để trở thành **inverse side** — thuần tuý là tiện ích để duyệt ngược quan hệ trong Java, Hibernate hoàn toàn **bỏ qua nó khi tính SQL**.

Hệ quả trực tiếp: sửa `order.getItems().add(item)` không có tác dụng gì tới cột `order_id` trong DB nếu `item.getOrder()` vẫn `null`.

## Ba kịch bản trong test

### 1. Bug — chỉ sửa inverse side → `order_id` null

```java
Order order = new Order("Nguyen Van A", "CREATED");
OrderItem item = new OrderItem("SKU-001", 2);

order.getItems().add(item); // quên item.setOrder(order)

orderRepository.save(order); // cascade = ALL -> item cũng được persist theo
entityManager.flush();
```

Log Hibernate: đúng 2 câu `INSERT`, không có gì khác — vì `mappedBy` không sinh SQL nên Hibernate không có cơ hội nào để "tự sửa" `order_id`:

```sql
insert into orders (customer_name, status, id) values (?, ?, default)
insert into order_items (order_id, product_sku, quantity, id) values (?, ?, ?, default)
--                        ^ giá trị NULL vì item.order chưa được set
```

Reload lại `OrderItem` từ DB: `reloaded.getOrder()` là `null`, dù item rõ ràng nằm trong `order.getItems()` ở phía Java.

### 2. Bug biến thể — set FK sau khi đã flush lần đầu → 1 `UPDATE` thừa

```java
order.getItems().add(item);
orderRepository.save(order);
entityManager.flush();          // insert order, insert item VỚI order_id = NULL

item.setOrder(order);           // "vá" lại FK sau khi đã flush lần đầu
entityManager.flush();          // dirty checking thấy item.order đổi -> sinh UPDATE
```

Log Hibernate cho thấy chi phí thật của lỗi này — không chỉ sai dữ liệu tạm thời mà còn tốn thêm một round-trip:

```sql
insert into orders (...) values (...)
insert into order_items (order_id, ...) values (NULL, ...)
update order_items set order_id=?, product_sku=?, quantity=? where id=?
```

Đây chính là tình huống "**hoặc có thêm một câu UPDATE thừa**" nêu trong đề bài: nó xảy ra khi FK được set **muộn**, sau khi entity đã managed và đã flush ít nhất một lần — dirty checking sẽ tự dọn dẹp nhưng phải trả giá bằng một statement phụ.

### 3. Fix — helper method `addItem()` đồng bộ cả hai phía cùng lúc

```java
public void addItem(OrderItem item) {
    items.add(item);
    item.setOrder(this);
}
```

```java
order.addItem(item); // 1 lời gọi, không thể quên phía nào
orderRepository.save(order);
entityManager.flush();
```

```sql
insert into orders (...) values (...)
insert into order_items (order_id, ...) values (?, ...)  -- order_id ĐÚNG ngay từ đầu
```

## So sánh số lượng SQL statement (đếm bằng Hibernate `Statistics`)

| Kịch bản | Số statement | Dữ liệu đúng? |
| :--- | :---: | :---: |
| Chỉ sửa `items.add()`, quên `setOrder()` | 2 | ❌ `order_id` = null |
| Sửa `setOrder()` muộn, sau flush đầu | 3 | ✅ nhưng tốn 1 UPDATE thừa |
| Dùng helper `addItem()` | 2 | ✅ đúng ngay từ đầu |

Kết luận quan trọng: **helper method không hề làm tăng chi phí SQL** — vẫn 2 statement như trường hợp bug gốc — nó chỉ đảm bảo giá trị field `order` đúng tại đúng thời điểm Hibernate đọc nó (lúc flush). Vì vậy helper method luôn nên được viết ngay từ đầu cho mọi quan hệ bidirectional, không phải một optimization tuỳ chọn.

## Vì sao helper method là bắt buộc, không phải "nice to have"

- Java object graph và quan hệ trong DB là hai thứ khác nhau: thêm `item` vào `List<OrderItem> items` chỉ thay đổi state trong bộ nhớ của object `order`. Nó không tự động "lan truyền" sang `item.order` — đó là hai field độc lập, chỉ có convention (và kỷ luật lập trình) giữ chúng đồng bộ.
- Vì owning side quyết định SQL, quên set field ở owning side luôn là lỗi **im lặng**: không có exception nào ném ra, dữ liệu chỉ đơn giản là sai (hoặc tốn thêm round-trip nếu vá muộn).
- Gộp cả hai thao tác vào một method trên entity (thay vì để caller tự gọi cả hai dòng) loại bỏ hoàn toàn khả năng quên — đây là lý do sách vở (Vlad Mihalcea, *Java Persistence with Hibernate*) luôn khuyến nghị viết helper method `addX()`/`removeX()` cho **mọi** quan hệ bidirectional, không chỉ khi "nhớ ra".

## Ghi chú về schema

Migration hiện để `order_items.order_id` **nullable** — cố tình, để có thể tái hiện bug (nếu `NOT NULL`, Hibernate sẽ ném `DataIntegrityViolationException` ngay khi insert thay vì âm thầm lưu `NULL`). Đây thực ra là gợi ý cho một lớp phòng thủ tốt hơn: nếu nghiệp vụ đảm bảo mọi `OrderItem` luôn phải thuộc về một `Order`, đặt `NOT NULL` ở tầng DB biến bug logic này thành lỗi **fail-fast** thay vì dữ liệu rác âm thầm tồn tại.

## Kết quả chạy

```
Tests run: 3, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```
