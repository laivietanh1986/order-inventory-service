# Mục 14 — Composite index và leftmost prefix rule

## Mục tiêu

Query mục tiêu xuyên suốt bài:

```sql
SELECT * FROM orders
WHERE customer_name = ? AND status = ? AND created_at BETWEEN ? AND ?
ORDER BY created_at DESC
```

Tạo LẦN LƯỢT (cộng dồn, không xoá cái trước) 3 index rồi đo từng cái:
`(status)` → `(status, customer_name)` → `(customer_name, status, created_at)`.
Sau đó thử một query KHÁC chỉ lọc `status` (không có `customer_name`) để xem
index composite ba cột bị bỏ qua như thế nào.

**Ghi chú về tên cột**: lộ trình gốc dùng `customer_id`, nhưng schema project
từ trước tới giờ dùng `customer_name` (String) làm định danh khách hàng —
không có cột `customer_id` riêng. Bản chất bài học (một cột **cardinality
cao** dùng làm điều kiện lọc bằng) không đổi dù tên cột là gì, nên bài này
dùng thẳng `customer_name` thay vì thêm một cột mới không cần thiết.

## File đã tạo/sửa

| File | Vai trò |
|---|---|
| [`Order.java`](../src/main/java/com/example/orderinventory/order/Order.java) | Thêm field `createdAt` (Instant, mặc định `Instant.now()`) |
| [`V12__add_created_at_to_orders.sql`](../src/main/resources/db/migration/V12__add_created_at_to_orders.sql) | `ALTER TABLE orders ADD COLUMN created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP` |
| [`CompositeIndexLeftmostPrefixTest.java`](../src/test/java/com/example/orderinventory/index/CompositeIndexLeftmostPrefixTest.java) | Toàn bộ thực nghiệm mục 14, trên PostgreSQL thật (Testcontainers) |

`created_at` cần `DEFAULT CURRENT_TIMESTAMP` ở tầng DB (không chỉ ở tầng
Java) vì nhiều test có sẵn từ các mục trước (`CustomerOrderSummaryTest`,
`SeedAndExplainAnalyzeTest`) insert thẳng bằng JDBC, không biết cột mới này
tồn tại — nếu không có default ở DB, các `INSERT` đó sẽ vi phạm `NOT NULL`
và toàn bộ suite cũ sẽ gãy. Sau khi thêm, `mvn clean test` chạy lại xác nhận
đủ **55/55 test pass** (50 test cũ + 5 test mới của mục này).

## Cạm bẫy: JUnit không đảm bảo thứ tự chạy method

5 bước của bài này **cộng dồn index** (mỗi bước tạo thêm 1 index, không xoá
index bước trước) — nghĩa là thứ tự chạy CHÍNH XÁC 0 → 1 → 2 → 3 → 4 là bắt
buộc để mỗi bước phản ánh đúng trạng thái index nó muốn minh hoạ. Lần chạy
đầu tiên KHÔNG khai báo thứ tự, và JUnit 5 chạy các method theo một thứ tự
nội bộ không liên quan gì tới thứ tự khai báo trong file — log thực tế cho
thấy nó chạy theo trình tự `buoc_0, buoc_2, buoc_1, buoc_3, buoc_4`. Hậu quả:
lúc `buoc_1` (dự định "chỉ có index trên status") chạy, `idx_orders_status_customer`
của `buoc_2` đã tồn tại sẵn từ trước — nên plan đo được của `buoc_1` không hề
phản ánh đúng ý đồ (index status ĐỨNG MỘT MÌNH).

**Fix**: `@TestMethodOrder(MethodOrderer.OrderAnnotation.class)` ở class,
`@Order(0)`..`@Order(4)` trên từng method — ép đúng thứ tự cộng dồn dự định.
Chạy lại, số liệu bên dưới là của lần chạy ĐÃ SỬA (đáng tin cậy).

## Dữ liệu seed

1.000.000 đơn "nền" (JDBC batch, như mục 13) với:
- `customer_name`: 50.000 giá trị phân biệt (`"Customer " + i % 50000`) →
  cardinality CAO, ~20 đơn/khách.
- `status`: lệch hẳn — 70% `CREATED`, 20% `CONFIRMED`, 8% `SHIPPED`, 2%
  `CANCELLED` → cardinality THẤP (chỉ 4 giá trị), và ngay cả giá trị hiếm
  nhất cũng chiếm tới 20.000/1.000.000 dòng.
- `created_at`: trải trong 365 ngày kể từ 2025-01-01.

Cộng thêm 4 dòng riêng cho `"Target Customer"` (không trùng với 50.000
khách "nền") — chỉ **đúng 1 dòng** khớp cả 3 điều kiện của query mục tiêu
(`status = CONFIRMED`, `created_at` trong tháng 6/2025), 3 dòng còn lại cố ý
sai status hoặc sai tháng. Nhờ vậy `actual rows` của mọi plan trong bài này
đều là một con số CHẮC CHẮN (1), không phụ thuộc dữ liệu ngẫu nhiên.

Gọi `ANALYZE orders` ngay sau khi seed — để loại yếu tố may rủi "autovacuum
kịp chạy hay chưa" đã gặp ở mục 13; bài này cần plan ổn định để so sánh công
bằng giữa các bước.

## Bước 0 — chưa có index nào (ngoài PK)

```
Sort  (cost=18679.48..18679.48 rows=1 width=42) (actual time=71.802..79.226 rows=1 loops=1)
  Sort Key: created_at DESC
  ->  Gather  (cost=1000.00..18679.47 rows=1 width=42) (actual time=71.532..79.157 rows=1 loops=1)
        Workers Planned: 2
        Workers Launched: 2
        ->  Parallel Seq Scan on orders  (actual time=54.456..54.458 rows=0 loops=3)
              Filter: (created_at >= ... AND created_at <= ... AND customer_name = 'Target Customer' AND status = 'CONFIRMED')
              Rows Removed by Filter: 333334
Execution Time: 79.226 ms (~92ms o lan chay khac)
```

Không có index nào dùng được → Parallel Seq Scan toàn bộ 1.000.004 dòng
(chia cho 2 worker, mỗi worker loại ~333.334 dòng không khớp), cộng thêm một
node `Sort` riêng cho `ORDER BY created_at DESC` vì Seq Scan không trả kết
quả theo thứ tự nào cả. **~79-93 ms.**

## Bước 1 — index `(status)` đứng một mình

```sql
CREATE INDEX idx_orders_status ON orders (status);
```

```
Sort  (cost=14132.43..14132.43 rows=1 width=42) (actual time=29.315..34.040 rows=1 loops=1)
  ->  Gather  (actual time=29.115..34.025 rows=1 loops=1)
        Workers Planned: 2
        ->  Parallel Bitmap Heap Scan on orders  (actual time=25.182..25.183 rows=0 loops=3)
              Recheck Cond: (status = 'CONFIRMED')
              Filter: (created_at BETWEEN ... AND customer_name = 'Target Customer')
              Rows Removed by Filter: 66667
              Heap Blocks: exact=3432
              ->  Bitmap Index Scan on idx_orders_status  (actual time=9.317..9.318 rows=200002 loops=1)
                    Index Cond: (status = 'CONFIRMED')
Execution Time: 34.040 ms
```

Postgres CÓ dùng `idx_orders_status` (Bitmap Index Scan → Bitmap Heap Scan),
nhưng vì `status = 'CONFIRMED'` khớp tới **200.002/1.000.004 dòng (~20%)**,
index chỉ thu hẹp được từ "toàn bộ bảng" xuống "200.000 dòng cần đọc và lọc
tiếp bằng tay (`Filter`) để tìm ra đúng 1 dòng". Có nhanh hơn bước 0
(34ms so với 79-93ms, ~2.3 lần), nhưng vẫn phải đọc 3.432 heap block và loại
bỏ 66.667 dòng mỗi worker — **đúng như lộ trình dự đoán: index trên MỘT
cột ít giá trị, đứng một mình, gần như vô dụng** khi giá trị đó không đủ
hiếm (ở đây hiếm nhất trong 4 giá trị cũng đã 20%).

## Bước 2 — index `(status, customer_name)`

```sql
CREATE INDEX idx_orders_status_customer ON orders (status, customer_name);
```

```
Sort  (cost=18.62..18.62 rows=1 width=42) (actual time=0.067..0.068 rows=1 loops=1)
  ->  Index Scan using idx_orders_status_customer on orders  (actual time=0.050..0.052 rows=1 loops=1)
        Index Cond: (status = 'CONFIRMED' AND customer_name = 'Target Customer')
        Filter: (created_at BETWEEN ...)
        Rows Removed by Filter: 1
Execution Time: 0.110 ms
```

Cost giảm từ **~34ms xuống 0.07ms** — nhanh hơn **~490 lần** so với bước 1.
Lý do: dù `status` đứng TRƯỚC `customer_name` trong khai báo index (thứ tự
không tối ưu, vì `customer_name` mới là cột chọn lọc mạnh hơn), B-tree vẫn
dùng được CẢ HAI cột làm `Index Cond` cùng lúc — vì cả hai đều là điều kiện
BẰNG (`=`). Điều kiện bằng trên nhiều cột trong cùng một composite index có
thể kết hợp bất kể thứ tự khai báo, miễn chúng là một dãy liên tục từ cột
đầu tiên. Tuy nhiên `created_at` không có trong index này, nên vẫn cần một
node `Sort` riêng để thoả `ORDER BY created_at DESC`.

## Bước 3 — index đúng thứ tự: `(customer_name, status, created_at)`

```sql
CREATE INDEX idx_orders_customer_status_created
    ON orders (customer_name, status, created_at);
```

```
Index Scan Backward using idx_orders_customer_status_created on orders
    (cost=0.42..8.45 rows=1 width=42) (actual time=0.035..0.036 rows=1 loops=1)
  Index Cond: (customer_name = 'Target Customer' AND status = 'CONFIRMED'
               AND created_at >= '2025-06-01' AND created_at <= '2025-06-30')
Execution Time: 0.055 ms
```

**Không còn node `Sort` nào cả** — đây là điểm khác biệt cốt lõi so với bước
2. Cột `created_at` đứng CUỐI CÙNG trong index, sau hai cột điều kiện bằng
(`customer_name`, `status`) — nên trong phạm vi đã được hai cột đầu thu hẹp,
các dòng CÒN LẠI trong index vốn đã được sắp xếp sẵn theo `created_at`. Vì
`ORDER BY ... DESC`, Postgres chỉ cần đọc index theo chiều NGƯỢC
(`Index Scan Backward`) thay vì đọc xuôi rồi sort — không tốn thêm chi phí
sắp xếp nào. Đây chính là quy tắc **"equality columns trước, range/sort
column sau"**: `customer_name` và `status` là điều kiện bằng nên đứng đầu,
`created_at` vừa là điều kiện range (`BETWEEN`) vừa là cột `ORDER BY` nên
đứng cuối để tận dụng thứ tự vật lý của chính index.

## Tổng hợp thời gian 4 bước đầu (cùng 1 query, càng lúc càng thu hẹp)

| Bước | Index | Chiến lược | Execution Time |
|---|---|---|---|
| 0 | không có | Parallel Seq Scan + Sort | ~79-93 ms |
| 1 | `(status)` | Bitmap Heap Scan (200.000 ứng viên) + Sort | ~34 ms |
| 2 | `(status, customer_name)` | Index Scan (đúng 1 ứng viên) + Sort | ~0.11 ms |
| 3 | `(customer_name, status, created_at)` | Index Scan Backward, không Sort | ~0.055 ms |

Từ bước 0 đến bước 3: nhanh hơn **~1.400-1.700 lần** cho CÙNG một query.

## Bước 4 — leftmost prefix rule: đổi query, bỏ điều kiện `customer_name`

Cả 3 index vẫn còn nguyên, chạy một query KHÁC — chỉ lọc `status`:

```sql
SELECT * FROM orders WHERE status = 'CANCELLED'
```

```
Bitmap Heap Scan on orders  (cost=249.25..9874.00 rows=22300 width=42) (actual time=4.369..21.708 rows=20000 loops=1)
  Recheck Cond: (status = 'CANCELLED')
  Heap Blocks: exact=9346
  ->  Bitmap Index Scan on idx_orders_status  (actual time=2.511..2.511 rows=20000 loops=1)
        Index Cond: (status = 'CANCELLED')
Execution Time: 23.161 ms
```

`idx_orders_customer_status_created` (index composite ba cột, "tối ưu nhất"
ở bước 3) **hoàn toàn không xuất hiện trong plan này**. Đây chính là
**leftmost prefix rule**: index B-tree trên `(customer_name, status,
created_at)` chỉ dùng được cho các query có điều kiện bằng trên
`customer_name` (cột đầu tiên) — dù có thêm `status` hay `created_at` hay
không. Vì query này KHÔNG có điều kiện `customer_name`, Postgres không thể
"nhảy" thẳng vào giữa cây B-tree để bắt đầu từ `status` — nó buộc phải bỏ
qua index này hoàn toàn, bất kể index đó có tối ưu đến đâu cho các query
khác.

Đáng chú ý: `idx_orders_status_customer` (bước 2) CŨNG không được chọn dù
cột đầu của nó (`status`) khớp hoàn toàn với query — vì Postgres đánh giá
`idx_orders_status` (đơn giản hơn, nhỏ hơn) đã đủ tốt cho riêng việc lọc
`status`, không cần thêm `customer_name` "thừa" trong index. Postgres luôn
chọn phương án RẺ NHẤT trong số các index dùng được, không phải index "to
nhất"/"đầy đủ nhất".

## Khái niệm

- **Leftmost prefix rule**: composite index `(A, B, C)` chỉ hữu ích cho các
  query lọc theo `A`, hoặc `(A, B)`, hoặc `(A, B, C)` — một dãy LIÊN TỤC bắt
  đầu từ cột đầu tiên. Query chỉ lọc `B` hoặc `C` một mình (bỏ qua `A`)
  không tận dụng được index này.
- **Equality column trước, range/sort column sau**: đặt các cột lọc BẰNG
  (`=`) ở đầu index (thứ tự giữa chúng với nhau ít quan trọng, vì B-tree
  dùng được tổ hợp bất kỳ điều kiện bằng nào là prefix liên tục), và cột
  dùng cho RANGE (`BETWEEN`, `>`, `<`) hoặc `ORDER BY` ở cuối cùng — để
  index vừa lọc vừa trả kết quả đã sắp xếp sẵn trong phạm vi đó, tránh hẳn
  một node `Sort` riêng.
- **Selectivity**: tỉ lệ số dòng KHÁC BIỆT trên tổng số dòng. Index trên cột
  có ít giá trị phân biệt (như `status` ở đây, chỉ 4 giá trị) đứng MỘT MÌNH
  gần như vô dụng vì mỗi giá trị vẫn khớp một phần lớn của bảng (bước 1: 20%
  bảng, vẫn phải đọc 200.000 dòng ứng viên) — trong khi cột cardinality cao
  như `customer_name` (50.000 giá trị phân biệt) mới thực sự thu hẹp được
  xuống vài chục dòng. Kết hợp cả hai trong một composite index tận dụng
  được điểm mạnh của cột chọn lọc cao mà không cần tạo riêng một index khác.

## Tổng kết

`mvn clean test` → **55/55 test pass** (50 test của các mục 1–13 + 5 test
mới của mục 14, toàn bộ chạy trên PostgreSQL thật qua Testcontainers).
