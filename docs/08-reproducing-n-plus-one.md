# 8. Tái hiện N+1 và đo được nó

Tương ứng mục 8 trong [`Lo_trinh_hoc_JPA.md`](../Lo_trinh_hoc_JPA.md).

## Mục tiêu

Load 100 order rồi lặp qua `order.getItems()`. Đếm query bằng `SessionFactory.getStatistics().getPrepareStatementCount()` và viết assertion `isEqualTo(1)` — test này phải **fail** lúc đầu.

Khái niệm cần nắm: cơ chế 1 + N, và quan trọng hơn là cách biến N+1 thành thứ đo được thay vì cảm tính.

## File đã tạo

[`NPlusOneTest.java`](../src/test/java/com/example/orderinventory/order/NPlusOneTest.java) — dùng lại `Order`/`OrderItem` thật từ mục 2 (`@OneToMany` mặc định LAZY, đúng theo mục 5), không cần entity hay migration mới.

## Cách chạy

```bash
./mvnw test -Dtest=NPlusOneTest
```

## Bước 1 — viết assertion ngây thơ và để nó fail

```java
@BeforeEach
void seed100Orders() {
    for (int i = 0; i < 100; i++) {
        Order order = new Order("Customer " + i, "CREATED");
        order.addItem(new OrderItem("SKU-" + i + "-1", 1));
        order.addItem(new OrderItem("SKU-" + i + "-2", 2));
        entityManager.persist(order);
    }
    entityManager.flush();
    entityManager.clear();
}

@Test
void load_100_order_roi_duyet_items_sinh_ra_101_query_khong_phai_1() {
    statistics().clear();

    List<Order> orders = orderRepository.findAll(); // 1 query lấy 100 order

    int totalItems = 0;
    for (Order order : orders) {
        totalItems += order.getItems().size(); // mỗi order lazy-load riêng
    }

    assertThat(totalItems).isEqualTo(200);
    assertThat(statistics().getPrepareStatementCount()).isEqualTo(1); // <- ky vong ngay tho
}
```

Chạy lần đầu:

```
org.opentest4j.AssertionFailedError:
expected: 1L
 but was: 101L
```

Đây chính xác là điều đề bài yêu cầu: **để test fail trước**, vì assertion `isEqualTo(1)` phản ánh kỳ vọng trực giác sai lầm ("tôi chỉ gọi `findAll()` một lần, chắc chỉ tốn 1 query"). Con số `101L` trả về từ chính `Statistics` của Hibernate — không phải suy đoán, không phải đọc log rồi đếm bằng mắt — là bằng chứng đo được, khách quan, tái lập được ở bất kỳ máy nào chạy lại test này.

## Bước 2 — sửa assertion để nó phản ánh đúng thực tế đo được

```java
assertThat(statistics().getPrepareStatementCount()).isEqualTo(101);
```

Từ đây test trở thành **xanh (green)**, nhưng ý nghĩa của nó đã thay đổi: nó không còn kiểm tra "hệ thống hoạt động đúng" theo nghĩa thông thường, mà kiểm tra **"vấn đề N+1 vẫn còn tồn tại đúng như đã đo được"**. Đây là một dạng test hồi quy đặc biệt: nếu ai đó ở mục 9 áp dụng `JOIN FETCH`/`@EntityGraph`/`@BatchSize` mà quên cập nhật con số `101` trong test này, test sẽ **fail** và báo động ngay — chính là tinh thần "Mỗi mục phải kết thúc bằng một test fail nếu bug quay lại" nêu ở cuối lộ trình.

## Cơ chế 1 + N

```sql
select o1_0.id, o1_0.customer_name, o1_0.status from orders o1_0          -- 1 query: findAll()

select oi1_0.order_id, oi1_0.id, oi1_0.product_sku, oi1_0.quantity        -- query #1 cho order[0]
from order_items oi1_0 where oi1_0.order_id=?

select oi1_0.order_id, oi1_0.id, oi1_0.product_sku, oi1_0.quantity        -- query #2 cho order[1]
from order_items oi1_0 where oi1_0.order_id=?

... (lặp lại 100 lần) ...
```

- **1**: một câu `SELECT` để lấy danh sách 100 `Order` (`findAll()`).
- **N**: vì `Order.items` là `@OneToMany` mặc định LAZY (mục 5), Hibernate không nạp sẵn `items` khi load `Order` — nó chỉ tạo một **collection proxy chưa khởi tạo** cho mỗi order. Gọi `order.getItems().size()` là hành động đầu tiên chạm vào proxy đó, buộc Hibernate phải chạy một `SELECT` riêng để lấy `items` **của đúng order đang được duyệt** — lặp lại N = 100 lần, mỗi lần một round-trip DB riêng biệt.

Tổng chi phí không phải "hơi chậm" mà là **tuyến tính theo số lượng order** — gấp đôi số order thì gấp đôi số query, hoàn toàn không phụ thuộc vào việc dữ liệu có thực sự cần dùng đến `items` hay không (nếu code chỉ cần `order.getCustomerName()`, N query kia là lãng phí thuần tuý).

## Vì sao "biến N+1 thành thứ đo được" quan trọng hơn bản thân con số 101

Cái bẫy phổ biến nhất khi làm việc với N+1 không phải là *không biết* nó tồn tại — hầu hết lập trình viên có kinh nghiệm đều "biết" ORM có thể gây N+1. Cái bẫy là:

1. **Cảm tính không mở rộng được**: nhìn log SQL bằng mắt để "đếm" chỉ khả thi với vài chục dòng; với hệ thống thật, log production có hàng nghìn dòng mỗi giây, không ai ngồi đếm tay.
2. **Không có gì ngăn N+1 quay lại**: một đoạn code hôm nay dùng `JOIN FETCH` đúng, nhưng một refactor sau này (thêm điều kiện lọc, đổi cách gọi repository) có thể vô tình làm mất `JOIN FETCH` đó — nếu không có test khẳng định con số, không ai phát hiện ra cho đến khi hiệu năng production giảm.
3. **`Statistics.getPrepareStatementCount()` biến "cảm giác chậm" thành một con số cụ thể, assert được, chạy lại được** — đúng bản chất của kỹ thuật: đo trước, tối ưu sau, và có bằng chứng cho cả hai bước.

Kỹ thuật đếm câu lệnh SQL bằng Hibernate `Statistics` đã được dùng xuyên suốt các bài trước (mục 2, 3, 6, 7) để xác minh số lượng `INSERT`/`UPDATE`/`DELETE`; ở đây nó lần đầu được áp dụng để **định lượng một anti-pattern** thay vì xác nhận một hành vi đúng — cùng một công cụ, hai mục đích khác nhau.

## Bước tiếp theo

Mục 9 sẽ áp dụng lần lượt 4 kỹ thuật khắc phục (`JOIN FETCH`, `@EntityGraph`, `@BatchSize`, `@Fetch(SUBSELECT)`) lên đúng bài toán này, đo lại số query sau mỗi kỹ thuật, và so sánh đánh đổi giữa chúng — chứ không có kỹ thuật nào "thắng tuyệt đối".

## Kết quả chạy

```
Tests run: 1, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```
