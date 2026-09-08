# 6. ManyToMany và bag semantics

Tương ứng mục 6 trong [`Lo_trinh_hoc_JPA.md`](../Lo_trinh_hoc_JPA.md).

## Mục tiêu

`Product` ↔ `Tag` bằng `@ManyToMany` với `List`. Thêm 1 tag vào product đã có 5 tag → xem log: `DELETE` cả 5 row rồi `INSERT` lại 6.

Đổi `List` sang `Set`, sau đó refactor thành entity `ProductTag` tường minh có thêm `createdAt`, `sortOrder`.

Khái niệm cần nắm: bag không có định danh từng row, tại sao production hầu như luôn tách join entity.

## Các file đã tạo

| File | Vai trò |
| :--- | :--- |
| [`BagDemoProduct`](../src/main/java/com/example/orderinventory/manytomany/BagDemoProduct.java) / [`BagDemoTag`](../src/main/java/com/example/orderinventory/manytomany/BagDemoTag.java) | Kịch bản A: `@ManyToMany` với `List` |
| [`SetDemoProduct`](../src/main/java/com/example/orderinventory/manytomany/SetDemoProduct.java) / [`SetDemoTag`](../src/main/java/com/example/orderinventory/manytomany/SetDemoTag.java) | Kịch bản B: `@ManyToMany` với `Set` |
| [`Tag`](../src/main/java/com/example/orderinventory/tag/Tag.java) / [`ProductTag`](../src/main/java/com/example/orderinventory/tag/ProductTag.java) | Kịch bản C (production): join entity tường minh, có `createdAt`, `sortOrder` |
| [`Product.productTags`](../src/main/java/com/example/orderinventory/product/Product.java) | `@OneToMany(mappedBy = "product")` + helper `addTag()` |
| [`V6__create_manytomany_and_tag_tables.sql`](../src/main/resources/db/migration/V6__create_manytomany_and_tag_tables.sql) | Bảng cho cả 3 kịch bản |
| [`ManyToManyBagVsSetTest.java`](../src/test/java/com/example/orderinventory/manytomany/ManyToManyBagVsSetTest.java) | So sánh `List` vs `Set` |
| [`ProductTagJoinEntityTest.java`](../src/test/java/com/example/orderinventory/tag/ProductTagJoinEntityTest.java) | Kịch bản join entity |

## Cách chạy

```bash
./mvnw test -Dtest=ManyToManyBagVsSetTest,ProductTagJoinEntityTest
```

## Kịch bản A — `@ManyToMany` với `List` (bag)

```java
BagDemoProduct product = new BagDemoProduct("Wireless Mouse");
for (int i = 1; i <= 5; i++) {
    product.getTags().add(new BagDemoTag("tag-" + i));
}
entityManager.persist(product);
entityManager.flush();
entityManager.clear();

BagDemoProduct reloaded = entityManager.find(BagDemoProduct.class, product.getId());

statistics().clear();
reloaded.getTags().add(new BagDemoTag("tag-6")); // chỉ THÊM 1 tag
entityManager.flush();
```

Log Hibernate thực tế (sau bước reload, khi thêm tag thứ 6):

```sql
insert into bag_demo_tags (name, id) values (?, default)          -- entity BagDemoTag "tag-6" mới

delete from bag_demo_product_tags where product_id=?               -- XOÁ SẠCH cả 5 dòng cũ

insert into bag_demo_product_tags (product_id, tag_id) values (?, ?)  -- rồi INSERT lại
insert into bag_demo_product_tags (product_id, tag_id) values (?, ?)  -- toàn bộ 6 dòng
insert into bag_demo_product_tags (product_id, tag_id) values (?, ?)  -- (5 dòng cũ
insert into bag_demo_product_tags (product_id, tag_id) values (?, ?)  --  + 1 dòng mới)
insert into bag_demo_product_tags (product_id, tag_id) values (?, ?)
insert into bag_demo_product_tags (product_id, tag_id) values (?, ?)
```

Tổng cộng: 1 `INSERT` (entity tag mới) + 1 `DELETE` + 6 `INSERT` (bảng join) = **8 statement** — chỉ để thêm đúng 1 liên kết.

### Vì sao lại như vậy — bag không có định danh từng row

`List` trong JPA, khi không kèm một cột chỉ số (`@OrderColumn`), được Hibernate coi là một **bag**: một collection cho phép trùng lặp và không có khái niệm "vị trí" hay "định danh" gắn với từng phần tử trong bảng join. Khi flush, Hibernate cần biết: so với lần load trước, phần tử nào được thêm, phần tử nào bị xoá, phần tử nào giữ nguyên. Với một `List` không có index column, các dòng trong `bag_demo_product_tags` không mang thông tin gì để phân biệt "dòng số mấy" — chúng chỉ là các cặp `(product_id, tag_id)` không thứ tự đáng tin cậy giữa hai lần load. Chiến lược **duy nhất an toàn** mà Hibernate có thể áp dụng là: xoá sạch mọi dòng thuộc `product_id` này, rồi chèn lại toàn bộ danh sách hiện tại — bất kể chỉ một phần tử thay đổi.

## Kịch bản B — đổi `List` thành `Set`

```java
SetDemoProduct product = new SetDemoProduct("Mechanical Keyboard");
for (int i = 1; i <= 5; i++) {
    product.getTags().add(new SetDemoTag("tag-" + i));
}
entityManager.persist(product);
entityManager.flush();
entityManager.clear();

SetDemoProduct reloaded = entityManager.find(SetDemoProduct.class, product.getId());

statistics().clear();
reloaded.getTags().add(new SetDemoTag("tag-6"));
entityManager.flush();
```

Log Hibernate:

```sql
insert into set_demo_tags (name, id) values (?, default)              -- entity tag mới
insert into set_demo_product_tags (product_id, tag_id) values (?, ?)  -- CHỈ 1 dòng join mới
```

Chỉ **2 statement**, không đụng tới 5 dòng đã có. `Set` được Hibernate quản lý bằng `PersistentSet`, có khả năng **diff chính xác** giữa snapshot lúc load và trạng thái hiện tại dựa trên `equals`/`hashCode` (ở đây là identity mặc định, đã đủ dùng vì trong cùng một persistence context, cùng một `id` luôn trả về cùng một Java reference). Biết chính xác phần tử nào mới, Hibernate chỉ cần `INSERT` đúng dòng đó.

> Đổi `List` → `Set` giải quyết đúng vấn đề "xoá sạch rồi chèn lại", nhưng đánh đổi mất thứ tự phần tử (Set không đảm bảo thứ tự) và không thể gắn thêm dữ liệu cho riêng từng liên kết. Đó là lý do bước tiếp theo (kịch bản C) mới là giải pháp production thật sự.

## Kịch bản C (production) — tách thành join entity `ProductTag`

```java
@Entity
@Table(name = "product_tags", uniqueConstraints = @UniqueConstraint(columnNames = {"product_id", "tag_id"}))
public class ProductTag {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne @JoinColumn(name = "product_id") private Product product;
    @ManyToOne @JoinColumn(name = "tag_id") private Tag tag;
    private Instant createdAt;
    private Integer sortOrder;
}
```

```java
Product product = new Product("USB-C Cable", "SKU-TAG-001", 100, new BigDecimal("5.50"));
entityManager.persist(product);
for (int i = 1; i <= 5; i++) {
    Tag tag = new Tag("tag-" + i);
    entityManager.persist(tag);
    product.addTag(tag, i);
}
entityManager.flush();
entityManager.clear();

Product reloaded = entityManager.find(Product.class, product.getId());

statistics().clear();
Tag newTag = new Tag("tag-6");
entityManager.persist(newTag);
reloaded.addTag(newTag, 6);
entityManager.flush();
```

Log Hibernate:

```sql
insert into tags (name, id) values (?, default)
insert into product_tags (created_at, product_id, sort_order, tag_id, id) values (?, ?, ?, ?, default)
```

Đúng **2 statement**, giống hệt kết quả của `Set`, nhưng lần này quan hệ giữ được đầy đủ metadata:

```java
ProductTag sixth = ...;
assertThat(sixth.getSortOrder()).isEqualTo(6);
assertThat(sixth.getCreatedAt()).isNotNull();
```

`ProductTag` không còn là một dòng vô danh trong bảng join thuần tuý — nó là một **entity thật sự có `@Id` riêng**. Về bản chất, `@ManyToMany` giữa `Product` và `Tag` đã được thay bằng **hai quan hệ `@ManyToOne`** (từ `ProductTag` tới `Product` và tới `Tag`), đúng loại quan hệ mà Hibernate luôn xử lý bằng cách chèn/xoá **từng dòng riêng lẻ theo định danh của nó** — y hệt cách `Order`/`OrderItem` hoạt động ở mục 2 và 3.

## So sánh tổng quan

| Cách ánh xạ | Số statement khi thêm 1 liên kết (đã có 5) | Giữ thứ tự? | Có thể thêm metadata riêng cho từng liên kết? |
| :--- | :---: | :---: | :---: |
| `@ManyToMany` + `List` (bag) | 8 (1 delete + 7 insert) | không đảm bảo | ❌ |
| `@ManyToMany` + `Set` | 2 | ❌ (Set không có thứ tự) | ❌ |
| Join entity tường minh (`ProductTag`) | 2 | ✅ (`sortOrder`) | ✅ (`createdAt`, `sortOrder`, ...) |

## Vì sao production hầu như luôn tách join entity

1. **Hiệu năng**: bag semantics khiến chi phí một thao tác "thêm 1 phần tử" tỉ lệ thuận với **tổng kích thước collection**, không phải với số thay đổi thực tế — càng nhiều tag, thao tác thêm 1 tag càng đắt.
2. **Metadata**: một bảng join thuần tuý (`@JoinTable`) chỉ có đúng 2 cột FK — không có chỗ cho `createdAt`, `sortOrder`, `addedBy`, hay bất kỳ thuộc tính nào riêng của *liên kết* (khác với thuộc tính của hai entity hai đầu). Nhu cầu này xuất hiện gần như luôn luôn trong thực tế (ai gắn tag này, gắn lúc nào, thứ tự hiển thị ra sao).
3. **Nhất quán với các quan hệ khác**: một join entity tường minh chỉ là một cặp `@ManyToOne`/`@OneToMany` bình thường — cùng một tư duy, cùng một bộ công cụ (cascade, orphanRemoval, fetch) đã học ở các mục 2, 3, 5, không cần thêm khái niệm "bag" đặc thù nào của `@ManyToMany`.

Vì những lý do trên, `@ManyToMany` trực tiếp (dù là `List` hay `Set`) thường chỉ còn xuất hiện trong ví dụ giáo trình hoặc những quan hệ N-N thật sự đơn giản, không có nhu cầu mở rộng — còn trong hệ thống production dài hơi, tách thành join entity gần như luôn là lựa chọn mặc định ngay từ đầu, không đợi đến khi gặp sự cố hiệu năng mới sửa.

## Kết quả chạy

```
Tests run: 2, Failures: 0, Errors: 0, Skipped: 0   -- ManyToManyBagVsSetTest
Tests run: 1, Failures: 0, Errors: 0, Skipped: 0   -- ProductTagJoinEntityTest
BUILD SUCCESS
```
