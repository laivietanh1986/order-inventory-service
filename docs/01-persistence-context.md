# 1. Entity đầu tiên và persistence context

Tương ứng mục 1 trong [`Lo_trinh_hoc_JPA.md`](../Lo_trinh_hoc_JPA.md).

## Mục tiêu

Tạo `Product`, `ProductRepository`, thử `save()`, `findById()`, và sửa field sau khi load mà **không** gọi `save()` → quan sát Hibernate vẫn sinh `UPDATE`.

Khái niệm cần nắm: persistence context, dirty checking, ba trạng thái transient / managed / detached, thời điểm flush.

## Các file đã tạo

| File | Vai trò |
| :--- | :--- |
| [`src/main/java/.../product/Product.java`](../src/main/java/com/example/orderinventory/product/Product.java) | Entity JPA |
| [`src/main/java/.../product/ProductRepository.java`](../src/main/java/com/example/orderinventory/product/ProductRepository.java) | `JpaRepository<Product, Long>` |
| [`src/main/resources/db/migration/V1__create_products_table.sql`](../src/main/resources/db/migration/V1__create_products_table.sql) | Flyway migration tạo bảng `products` |
| [`src/main/resources/application.yaml`](../src/main/resources/application.yaml) | Thêm `spring.jpa.hibernate.ddl-auto: validate` |
| [`src/test/java/.../product/ProductPersistenceContextTest.java`](../src/test/java/com/example/orderinventory/product/ProductPersistenceContextTest.java) | 3 test minh hoạ persistence context |

Vì `flyway-core` đã có sẵn trong `pom.xml`, ta để Flyway quản lý schema (migration script) và cấu hình `ddl-auto: validate` để Hibernate chỉ **kiểm tra** mapping entity khớp với bảng thật, không tự ý tạo/sửa bảng — đây là cách làm chuẩn khi dùng chung Flyway với JPA (xem thêm mục 22 trong lộ trình).

## Cách chạy

```bash
./mvnw test -Dtest=ProductPersistenceContextTest
```

`show-sql=true` đã bật sẵn trong `application.yaml` nên console sẽ in ra từng câu SQL Hibernate thực thi.

## Ba trạng thái vòng đời entity

```
new Product(...)          save() / persist()          find() trong transaction khác
   TRANSIENT     ────────────────────────►   MANAGED   ◄─────────────────────────
   (chưa có id,                                 │
    Hibernate không biết)                        │ detach() / clear() / hết transaction
                                                  ▼
                                              DETACHED
                                       (có id, nhưng không còn
                                        bị persistence context theo dõi)
```

- **Transient**: object Java thuần tuý, chưa từng được gán vào persistence context, chưa có `id`.
- **Managed**: đang nằm trong persistence context của session/transaction hiện tại. Hibernate giữ một **snapshot** giá trị các field tại thời điểm load để so sánh sau này.
- **Detached**: từng managed nhưng đã rời khỏi persistence context (transaction kết thúc, gọi `clear()`, `detach()`, hoặc entity bị serialize ra ngoài). Vẫn có `id`, vẫn là entity hợp lệ, nhưng thay đổi trên nó **không** được Hibernate theo dõi nữa.

## Dirty checking — vì sao không gọi `save()` vẫn ra `UPDATE`

Persistence context không chỉ lưu entity, nó lưu kèm **snapshot** lúc load. Khi `flush()` xảy ra (thủ công hoặc do transaction commit), Hibernate duyệt qua toàn bộ entity đang managed, so sánh giá trị hiện tại với snapshot — nếu khác, tự sinh `UPDATE` cho đúng field đã đổi. Đây gọi là **dirty checking**, và nó là lý do vì sao trong một `@Transactional` method, chỉ cần:

```java
Product p = productRepository.findById(id).orElseThrow();
p.setQuantity(45); // không gọi save()
// method kết thúc → transaction commit → Hibernate tự flush → UPDATE
```

vẫn cập nhật được database.

## Thời điểm flush xảy ra

1. Khi transaction commit (phổ biến nhất trong ứng dụng thật dùng `@Transactional`).
2. Khi gọi `entityManager.flush()` thủ công.
3. Tự động trước khi chạy một JPQL/Criteria query có thể bị ảnh hưởng bởi thay đổi chưa flush (auto-flush mode mặc định là `AUTO`).

## Ba test minh hoạ

### 1. `save_dua_entity_tu_transient_sang_managed`

```java
Product product = new Product("Wireless Mouse", "SKU-001", 100, new BigDecimal("19.99"));
assertThat(product.getId()).isNull(); // transient

Product saved = productRepository.save(product); // transient -> managed
assertThat(saved.getId()).isNotNull();
assertThat(entityManager.getEntityManager().contains(saved)).isTrue();
```

### 2. `sua_field_tren_managed_entity_van_sinh_UPDATE_du_khong_goi_save` (trọng tâm)

```java
Product product = productRepository.save(new Product("Mechanical Keyboard", "SKU-002", 50, new BigDecimal("89.90")));
entityManager.flush();
entityManager.clear(); // xoá persistence context để chứng minh bước sau load lại thật từ DB

Product loaded = productRepository.findById(product.getId()).orElseThrow(); // managed
loaded.setQuantity(45); // KHÔNG gọi save()/saveAndFlush()

entityManager.flush(); // ép flush ngay để quan sát
```

Log Hibernate thực tế sinh ra (không có lệnh save nào chen giữa `setQuantity` và `flush`):

```sql
insert into products (name, price, quantity, sku, id) values (?, ?, ?, ?, default)
select p1_0.id, p1_0.name, p1_0.price, p1_0.quantity, p1_0.sku from products p1_0 where p1_0.id=?
update products set name=?, price=?, quantity=?, sku=? where id=?
```

### 3. `sua_field_tren_detached_entity_khong_sinh_UPDATE`

```java
Product product = productRepository.save(new Product("USB-C Cable", "SKU-003", 200, new BigDecimal("5.50")));
entityManager.flush();

entityManager.detach(product); // managed -> detached
product.setQuantity(999);      // sửa trên object đã detached

entityManager.flush(); // không có gì xảy ra với "product"
```

Reload lại từ DB, `quantity` vẫn là `200` — thay đổi trên entity detached bị "mất" vì Hibernate không còn theo dõi nó (muốn áp dụng lại phải gọi `merge()`).

## Kết quả chạy

```
Tests run: 3, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

## Điểm rút ra

- `save()` của Spring Data JPA thực chất gọi `persist()`/`merge()` tuỳ trạng thái entity — nó không phải điều kiện bắt buộc để có `UPDATE`.
- Muốn hiểu vì sao một `UPDATE` "tự nhiên xuất hiện" trong log, luôn nghĩ đến: entity đó có đang **managed** không, và **flush** đã xảy ra ở đâu.
- Detached entity là bẫy thường gặp khi entity được load ở tầng service rồi truyền qua nhiều lớp/thread, hoặc bị cache lại — sửa field trên nó tưởng đã lưu nhưng thực ra không có gì được ghi xuống DB.
