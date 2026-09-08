# 4. equals / hashCode cho entity

Tương ứng mục 4 trong [`Lo_trinh_hoc_JPA.md`](../Lo_trinh_hoc_JPA.md).

## Mục tiêu

Cho `OrderItem` vào `HashSet` **trước khi** persist, persist xong gọi `set.contains(item)` → `false`.

Thử 3 cách: dùng `id`, dùng tất cả field, dùng business key hoặc UUID gán ở client.

Khái niệm cần nắm: tại sao `@GeneratedValue` phá hash contract, entity ở trạng thái detached so sánh thế nào.

## Vì sao cần 3 entity riêng biệt

`equals()`/`hashCode()` là code Java tĩnh gắn liền với từng class, không thể đổi qua lại theo test. Để so sánh 3 chiến lược cạnh nhau, mỗi chiến lược có một entity riêng, cùng hình dạng dữ liệu (`productSku`, `quantity`) để dễ đối chiếu:

| File | Chiến lược equals/hashCode | Bảng |
| :--- | :--- | :--- |
| [`IdEqualityItem`](../src/main/java/com/example/orderinventory/equality/IdEqualityItem.java) | dựa trên `id` (`@GeneratedValue`) | `id_equality_items` |
| [`AllFieldsEqualityItem`](../src/main/java/com/example/orderinventory/equality/AllFieldsEqualityItem.java) | dựa trên toàn bộ field nghiệp vụ | `all_fields_equality_items` |
| [`BusinessKeyEqualityItem`](../src/main/java/com/example/orderinventory/equality/BusinessKeyEqualityItem.java) | dựa trên `businessKey` (UUID gán ở client, không có setter) | `business_key_equality_items` |

Migration: [`V4__create_equality_demo_tables.sql`](../src/main/resources/db/migration/V4__create_equality_demo_tables.sql).
Test: [`EntityEqualsHashCodeTest.java`](../src/test/java/com/example/orderinventory/equality/EntityEqualsHashCodeTest.java).

## Cách chạy

```bash
./mvnw test -Dtest=EntityEqualsHashCodeTest
```

## Hợp đồng bị vi phạm là gì

Javadoc của `Object.hashCode()` nêu rõ: nếu một object được dùng làm phần tử của `HashSet`/khoá của `HashMap`, thì `hashCode()` của nó **phải giữ nguyên** trong suốt thời gian object còn nằm trong collection đó (miễn là các field dùng để tính `equals` không đổi). `HashSet` dùng `hashCode()` ngay lúc `add()` để chọn bucket lưu phần tử; sau đó `contains()`/`remove()` lại gọi `hashCode()` **lần nữa** trên object đưa vào để tính bucket cần tìm. Nếu hai lần tính ra hai giá trị khác nhau, `contains()` sẽ tìm sai bucket và trả về `false` dù object đó vẫn nằm trong set — nó chỉ đang "lạc" ở bucket cũ.

`@GeneratedValue` khiến `id` là `null` lúc entity còn transient và chỉ được gán giá trị thật tại thời điểm `INSERT` (flush). Nếu `equals`/`hashCode` phụ thuộc `id`, ta có chính xác kịch bản vi phạm hợp đồng trên.

## Cách 1 (sai) — dựa trên `id`

```java
@Override
public int hashCode() {
    return Objects.hash(id); // id = null lúc transient
}
```

```java
IdEqualityItem item = new IdEqualityItem("SKU-500", 3);

Set<IdEqualityItem> set = new HashSet<>();
set.add(item); // hashCode tính từ id = null -> chọn bucket A

assertThat(set.contains(item)).isTrue(); // trước persist: đúng

entityManager.persist(item);
entityManager.flush(); // id được DB sinh ra

assertThat(set.contains(item)).isFalse(); // SAU persist: contains() tính hashCode
                                           // MỚI (id != null) -> tìm bucket B -> không thấy
```

`item` vẫn là **chính object đó** (cùng reference) đang nằm trong `set` — nó không hề bị xoá đi đâu. Vấn đề thuần tuý là bucket nội bộ của `HashSet` được chọn một lần lúc `add()`, còn `contains()` tính lại từ đầu bằng giá trị field hiện tại.

### Vậy `id`-based equals dùng để làm gì?

Nó vẫn đúng và hữu ích cho một việc khác: **so sánh hai instance Java khác nhau xem có phải cùng một dòng dữ liệu hay không**, một khi cả hai đã có `id` thật:

```java
entityManager.persist(item);
entityManager.flush();
entityManager.clear(); // item trở thành DETACHED

IdEqualityItem reloaded = entityManager.find(IdEqualityItem.class, item.getId()); // MANAGED, object khác

assertThat(reloaded).isNotSameAs(item); // hai reference khác nhau trong bộ nhớ
assertThat(reloaded).isEqualTo(item);   // nhưng cùng id -> equals() nhận ra "cùng một dòng"
```

Đây chính là ý nghĩa câu hỏi "entity ở trạng thái detached so sánh thế nào": `equals()` dựa trên `id` trả lời đúng câu hỏi *"đây có phải cùng một row trong DB không"*, bất kể entity đang ở trạng thái managed hay detached, hay đến từ hai lần query khác nhau. Nó chỉ sai riêng cho việc **đặt vào hash-based collection trước khi có `id`**.

## Cách 2 (tạm ổn) — dựa trên tất cả field nghiệp vụ

```java
@Override
public int hashCode() {
    return Objects.hash(productSku, quantity); // không có id
}
```

```java
AllFieldsEqualityItem item = new AllFieldsEqualityItem("SKU-600", 5);
Set<AllFieldsEqualityItem> set = new HashSet<>();
set.add(item);

entityManager.persist(item);
entityManager.flush(); // id thay đổi, nhưng hashCode KHÔNG phụ thuộc id

assertThat(set.contains(item)).isTrue(); // vẫn tìm thấy
```

Vì `hashCode` không phụ thuộc `id`, việc persist không ảnh hưởng gì. Nhưng đây chỉ là "tạm ổn" — lỗi tương tự vẫn xảy ra nếu **bất kỳ field nào được dùng trong `equals`/`hashCode` bị sửa** trong lúc object còn nằm trong set, kể cả khi chưa hề đụng tới `id`:

```java
AllFieldsEqualityItem item = new AllFieldsEqualityItem("SKU-601", 1);
Set<AllFieldsEqualityItem> set = new HashSet<>();
set.add(item);

item.setQuantity(99); // quantity nằm trong equals/hashCode

assertThat(set.contains(item)).isFalse(); // vỡ hợp đồng, không liên quan gì tới persist
```

Kết luận: cách này chỉ che giấu triệu chứng cụ thể ("id đổi sau persist"), chứ không giải quyết tận gốc nguyên nhân ("dùng field mutable trong hashCode").

## Cách 3 (đúng) — business key / UUID gán ở client

```java
public BusinessKeyEqualityItem(String productSku, Integer quantity) {
    this.businessKey = UUID.randomUUID().toString(); // gán MỘT LẦN lúc khởi tạo
    this.productSku = productSku;
    this.quantity = quantity;
}
// không có setter cho businessKey

@Override
public int hashCode() {
    return Objects.hash(businessKey);
}
```

```java
BusinessKeyEqualityItem item = new BusinessKeyEqualityItem("SKU-700", 2);
Set<BusinessKeyEqualityItem> set = new HashSet<>();
set.add(item);

assertThat(set.contains(item)).isTrue();

entityManager.persist(item);
entityManager.flush();
assertThat(set.contains(item)).isTrue(); // id đổi, businessKey không đổi

item.setQuantity(999); // sửa field nghiệp vụ khác, không nằm trong equals/hashCode
assertThat(set.contains(item)).isTrue();
```

`businessKey` được sinh **trước khi** entity chạm tới bất kỳ persistence context nào, không phụ thuộc DB, và không có setter — nó bất biến trong suốt vòng đời object (transient → managed → detached). Đây là lý do nó là lựa chọn an toàn duy nhất trong 3 cách để dùng entity trong `HashSet`/`HashMap` **trước khi persist**.

## So sánh tổng quan

| Chiến lược | An toàn trong `HashSet` trước persist? | Field dùng có thể đổi sau này? | Dùng để so sánh detached vs reloaded? |
| :--- | :---: | :---: | :---: |
| `id` (`@GeneratedValue`) | ❌ (id đổi lúc flush) | không (id bất biến sau khi có) | ✅ đúng mục đích |
| Tất cả field nghiệp vụ | ✅ nếu field không đổi | ⚠️ có, dễ vỡ nếu field đổi | ✅ nếu field không đổi |
| Business key/UUID gán ở client | ✅ luôn luôn | không (không có setter) | ✅ luôn luôn |

## Nguyên tắc rút ra

1. **Không bao giờ dùng field do DB sinh ra (`@GeneratedValue`) trong `equals`/`hashCode`** nếu entity có khả năng bị đưa vào `HashSet`/`HashMap` trước khi persist — đây là nguồn gốc trực tiếp của bug.
2. Rộng hơn: **không dùng bất kỳ field mutable nào** trong `equals`/`hashCode` cho một object sẽ sống trong hash-based collection — đây là quy tắc chung của Java, không riêng gì JPA.
3. Cách bền vững nhất cho entity là gán một **business key hoặc UUID bất biến ngay lúc khởi tạo ở tầng client** (trước khi chạm DB), dùng riêng nó cho `equals`/`hashCode`. Đây cũng là khuyến nghị chuẩn từ Vlad Mihalcea/Hibernate User Guide.
4. `id`-based equals không "sai tuyệt đối" — nó đúng cho bài toán khác: xác định hai đối tượng Java (một detached, một vừa load lại) có cùng đại diện cho một row hay không, một khi cả hai đã có `id`.

## Kết quả chạy

```
Tests run: 5, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```
