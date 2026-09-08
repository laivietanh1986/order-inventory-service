# 5. Fetch type mặc định là cái bẫy

Tương ứng mục 5 trong [`Lo_trinh_hoc_JPA.md`](../Lo_trinh_hoc_JPA.md).

## Mục tiêu

Khai báo `@ManyToOne` và `@OneToOne` không kèm gì, load 1 `OrderItem` và đếm query.

Thử `@OneToOne(fetch = LAZY)` ở phía inverse giữa `Product` và `Inventory` → quan sát nó vẫn EAGER.

Khái niệm cần nắm: default fetch của 4 loại association, tại sao lazy `@OneToOne` inverse không hoạt động nếu không bật bytecode enhancement, `LazyInitializationException`.

## Fetch type mặc định của 4 loại association (JPA spec)

| Association | Default fetch |
| :--- | :---: |
| `@ManyToOne` | **EAGER** |
| `@OneToOne` | **EAGER** |
| `@OneToMany` | LAZY |
| `@ManyToMany` | LAZY |

Cái bẫy: hai loại quan hệ "to-one" (số lượng nhỏ, tưởng như vô hại) lại mặc định EAGER, còn hai loại "to-many" (dễ phình to) lại mặc định LAZY. Rất nhiều codebase declare `@ManyToOne` khắp nơi mà không ai để ý rằng mỗi lần `find()` một entity có `@ManyToOne` là kéo theo toàn bộ entity ở đầu kia, dù phần lớn use-case không cần đến nó.

## Các file liên quan

| File | Vai trò |
| :--- | :--- |
| [`OrderItem.order`](../src/main/java/com/example/orderinventory/order/OrderItem.java) | `@ManyToOne` không khai báo fetch (mục 2) — dùng lại cho bài này |
| [`Inventory.java`](../src/main/java/com/example/orderinventory/inventory/Inventory.java) | `@OneToOne` **owning side**, không khai báo fetch |
| [`Product.inventory`](../src/main/java/com/example/orderinventory/product/Product.java) | `@OneToOne` **inverse side** (`mappedBy`), khai báo `fetch = LAZY` |
| [`V5__create_inventory_table.sql`](../src/main/resources/db/migration/V5__create_inventory_table.sql) | bảng `inventory`, FK + UNIQUE tới `products` |
| [`DefaultFetchTypeTest.java`](../src/test/java/com/example/orderinventory/fetch/DefaultFetchTypeTest.java) | 4 test minh hoạ |

## Cách chạy

```bash
./mvnw test -Dtest=DefaultFetchTypeTest
```

## 1. `@ManyToOne` mặc định EAGER — load `OrderItem` kéo theo cả `Order`

```java
statistics().clear();
OrderItem loaded = entityManager.find(OrderItem.class, item.getId());

assertThat(Hibernate.isInitialized(loaded.getOrder())).isTrue(); // đã có dữ liệu thật, không phải proxy
assertThat(statistics().getPrepareStatementCount()).isEqualTo(1);
```

Log Hibernate cho thấy Hibernate **không cần 2 câu SELECT** — nó gộp luôn vào 1 câu bằng `LEFT JOIN`, vì cả FK (`order_id`) lẫn bảng cha (`orders`) đều nằm trong tầm với ngay từ câu query đầu tiên:

```sql
select oi1_0.id, o1_0.id, o1_0.customer_name, o1_0.status, oi1_0.product_sku, oi1_0.quantity
from order_items oi1_0
left join orders o1_0 on o1_0.id = oi1_0.order_id
where oi1_0.id = ?
```

Chỉ 1 statement, nhưng cột trả về đã phình to hơn — nếu `Order` có thêm nhiều cột hoặc quan hệ `@ManyToOne` khác lồng nhau, chuỗi JOIN này lớn dần theo cấp số nhân mà không ai chủ đích yêu cầu.

## 2. `@OneToOne` owning side mặc định EAGER — tương tự `@ManyToOne`

```java
Inventory loaded = entityManager.find(Inventory.class, inventory.getId());
assertThat(Hibernate.isInitialized(loaded.getProduct())).isTrue();
assertThat(statistics().getPrepareStatementCount()).isEqualTo(1); // 1 SELECT có JOIN
```

```sql
select i1_0.id, i1_0.product_id, p1_0.id, p1_0.name, p1_0.price, p1_0.quantity, p1_0.sku, i1_0.quantity_on_hand
from inventory i1_0
join products p1_0 on p1_0.id = i1_0.product_id
where i1_0.id = ?
```

Ở owning side, Hibernate biết chắc FK (`product_id`) nằm ngay trong bảng `inventory` — nên dù có khai báo LAZY ở đây, việc tạo lazy proxy vẫn khả thi về mặt kỹ thuật (không cần đụng tới bytecode enhancement), vì Hibernate chỉ cần giá trị FK để tạo một proxy `Product` rỗng.

## 3. `@OneToOne` inverse side khai báo LAZY — vẫn EAGER

```java
@OneToOne(mappedBy = "product", fetch = FetchType.LAZY)
private Inventory inventory;
```

```java
Product loaded = entityManager.find(Product.class, product.getId());

assertThat(Hibernate.isInitialized(loaded.getInventory())).isTrue(); // LAZY nhung van da duoc init san!
assertThat(statistics().getPrepareStatementCount()).isEqualTo(2);
```

Log Hibernate cho thấy **2 câu SELECT** dù ta chưa hề gọi `loaded.getInventory()`:

```sql
select p1_0.id, p1_0.name, p1_0.price, p1_0.quantity, p1_0.sku
from products p1_0 where p1_0.id = ?

select i1_0.id, i1_0.product_id, p1_0.id, ..., i1_0.quantity_on_hand
from inventory i1_0
join products p1_0 on p1_0.id = i1_0.product_id
where i1_0.product_id = ?
```

### Vì sao `fetch = LAZY` bị Hibernate "bỏ ngoài tai" ở đây

Ở **inverse side** của `@OneToOne` (`mappedBy = "product"`), FK không nằm trong bảng của entity đang load (`products`) mà nằm ở bảng đối phương (`inventory`). Muốn tạo một lazy proxy cho field `inventory`, Hibernate phải trả lời trước một câu hỏi: *field này rốt cuộc sẽ là một proxy `Inventory`, hay là `null`?* — vì `@OneToOne` (khác `@OneToMany`) không cho phép "collection rỗng" làm phương án an toàn, nó chỉ có hai khả năng: có bản ghi liên kết, hoặc không có gì cả.

Để trả lời câu hỏi đó **mà không cần query trước**, Hibernate cần **bytecode enhancement** (Hibernate's build-time bytecode enhancement, bật qua plugin Maven/Gradle) để chèn thêm field đánh dấu và logic chặn truy cập vào chính class `Product` đã biên dịch. Nếu không bật enhancement này (mặc định là **không bật**), Hibernate chỉ còn một lựa chọn an toàn duy nhất: chạy ngay một `SELECT` phụ để biết chắc câu trả lời — tức là quay về EAGER, bất kể annotation nói gì.

Đây là lý do khái niệm "lazy `@OneToOne` inverse không hoạt động nếu không bật bytecode enhancement" — không phải Hibernate có bug, mà là giới hạn kỹ thuật cố hữu của việc lazy-load một quan hệ mà FK nằm ở phía bên kia.

## 4. `@OneToMany` mặc định LAZY + `LazyInitializationException`

```java
Order loaded = entityManager.find(Order.class, order.getId());
assertThat(Hibernate.isInitialized(loaded.getItems())).isFalse(); // chua init, khac han 3 vi du tren

entityManager.detach(loaded);

assertThatThrownBy(() -> loaded.getItems().size())
        .isInstanceOf(LazyInitializationException.class);
```

`@OneToMany` (và `@ManyToMany`) mặc định LAZY vì lý do ngược lại với `@OneToOne`: một collection rỗng luôn là giá trị mặc định an toàn để tạo proxy mà không cần query gì cả (không có tình huống mơ hồ "có hay không có" như `@OneToOne`).

Khi entity bị `detach()` (rời khỏi persistence context), Hibernate ngắt luôn tham chiếu tới `Session` bên trong proxy collection của riêng entity đó. Gọi `getItems().size()` lúc này không còn cách nào chạy câu `SELECT` còn thiếu — Hibernate ném `LazyInitializationException` thay vì âm thầm trả về dữ liệu sai hoặc collection rỗng giả. Đây là hành vi **đáng mong muốn**: thà lỗi rõ ràng ngay tại chỗ, còn hơn để bug trôi xuống tận UI dưới dạng "danh sách rỗng" khó hiểu.

## So sánh tổng quan

| Quan hệ | Default fetch | Có thể LAZY thật không (không bytecode enhancement)? |
| :--- | :---: | :---: |
| `@ManyToOne` | EAGER | ✅ có (đặt `fetch = LAZY` tường minh) |
| `@OneToOne` owning | EAGER | ✅ có |
| `@OneToOne` inverse (`mappedBy`) | EAGER | ❌ không — luôn bị ép EAGER |
| `@OneToMany` / `@ManyToMany` | LAZY | ✅ (đã là default) |

## Kết luận thực hành

- Luôn khai báo `fetch = LAZY` tường minh cho mọi `@ManyToOne` và `@OneToOne` **owning side** — đừng bao giờ dựa vào default. Đây gần như là quy tắc bắt buộc trong mọi dự án Hibernate nghiêm túc.
- Với `@OneToOne` **inverse side**, `fetch = LAZY` chỉ là "nguyện vọng" chứ không phải cam kết — nếu thực sự cần lazy ở đây, phải bật Hibernate bytecode enhancement (plugin `hibernate-enhance-maven-plugin` hoặc tương đương), nếu không thì nên cân nhắc thiết kế lại: ví dụ đảo owning side, hoặc chấp nhận EAGER và tối ưu bằng cách khác (DTO projection — sẽ gặp ở mục 12).
- `LazyInitializationException` không phải "lỗi cần fix bằng cách tắt lazy đi cho xong" — nó là tín hiệu cho thấy code đang cố truy cập dữ liệu ở nơi/khi không còn `Session`. Cách sửa đúng là load đủ dữ liệu cần thiết trong phạm vi transaction (qua `JOIN FETCH`, `@EntityGraph`, v.v. — mục 9), không phải đổi toàn bộ quan hệ sang EAGER.

## Kết quả chạy

```
Tests run: 4, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```
