# Mục 15 — Partial index và covering index

## Mục tiêu

Bảng 1 triệu order, trong đó **95% là `COMPLETED`**, chỉ **5% là
`CONFIRMED`** — workload lệch nặng. Ba việc:
1. Tạo `CREATE INDEX ... WHERE status = 'CONFIRMED'` (partial index), so
   sánh dung lượng với index đầy đủ.
2. Thêm `INCLUDE (total_amount)` để đạt Index Only Scan.
3. Quan sát Index Only Scan phụ thuộc `VACUUM` (visibility map) như thế nào.

## File đã tạo

| File | Vai trò |
|---|---|
| [`PartialAndCoveringIndexTest.java`](../src/test/java/com/example/orderinventory/index/PartialAndCoveringIndexTest.java) | Toàn bộ thực nghiệm mục 15, trên PostgreSQL thật (Testcontainers, tiếp tục mục 13-14) |

Không có thay đổi nào ở entity/migration — bảng `orders` đã có đủ cột cần
thiết (`status`, `total_amount`, cùng `created_at` từ mục 14).

## Đo dung lượng bằng SQL thay vì `\di+`

`\di+` là lệnh meta của `psql`, không gọi được qua JDBC. Dùng tương đương SQL
thuần: `pg_relation_size('ten_index')` (bytes) và `pg_size_pretty(...)` (chuỗi
dễ đọc) — đúng con số mà `\di+` hiển thị ở cột "Size".

## Bước 0 — index đầy đủ trên `status`

```sql
CREATE INDEX idx_orders_status_full ON orders (status);
```

```
[LESSON15] idx_orders_status_full: 6904 kB (7069696 bytes)
```

Index này phải chứa **cả 1.000.000 dòng** — kể cả 95% mang giá trị
`COMPLETED` mà gần như không bao giờ hữu ích để lọc riêng (giống bài học
"index trên giá trị áp đảo gần vô dụng" ở mục 14).

## Bước 1 — partial index chỉ 5% dữ liệu

```sql
CREATE INDEX idx_orders_status_confirmed_partial
    ON orders (status) WHERE status = 'CONFIRMED';
```

```
[LESSON15] full=6904 kB, partial(chi CONFIRMED)=360 kB
```

**Nhỏ hơn ~19 lần** so với index đầy đủ (360 kB so với 6904 kB) — đúng theo
tỉ lệ 5% dữ liệu nó thực sự chứa (~1/20). Chạy đúng query mà predicate của
partial index khớp:

```
Index Scan using idx_orders_status_confirmed_partial on orders
    (cost=0.29..8780.70 rows=52533 width=43) (actual time=0.044..20.582 rows=50000 loops=1)
Execution Time: 23.096 ms
```

Postgres dùng thẳng partial index này — nhỏ, gọn, đủ dùng cho đúng workload
lệch (đa số truy vấn thực tế trong hệ thống order thường tìm các đơn CHƯA
hoàn tất, tức nhóm thiểu số, không phải nhóm `COMPLETED` áp đảo).

## Bước 2 — điều kiện không khớp predicate: index bị loại hoàn toàn

```sql
SELECT * FROM orders WHERE status = 'COMPLETED'
```

```
Seq Scan on orders  (cost=0.00..21846.00 rows=947467 width=43) (actual time=0.012..258.405 rows=950000 loops=1)
  Filter: (status = 'COMPLETED')
  Rows Removed by Filter: 50000
Execution Time: 305.741 ms
```

Kết quả đáng chú ý: Postgres chọn **Seq Scan**, không dùng `idx_orders_status_confirmed_partial`
(dễ hiểu — predicate của nó là `WHERE status = 'CONFIRMED'`, hoàn toàn không
liên quan đến điều kiện `status = 'COMPLETED'` của query này) **và cũng
không dùng luôn cả `idx_orders_status_full`** dù index đó bao phủ toàn bộ
bảng kể cả `COMPLETED`. Lý do: `COMPLETED` chiếm tới 95% bảng — dùng index
để lấy ra 950.000/1.000.000 dòng rồi truy heap từng dòng một sẽ ĐẮT HƠN quét
tuần tự toàn bộ bảng một lần. Đây chính là mặt trái của partial index: nó
giải quyết tốt truy vấn trên nhóm THIỂU SỐ, nhưng nhóm ĐA SỐ vẫn phải seq
scan dù có bao nhiêu index đi nữa — vì bản chất vấn đề (chọn phần lớn bảng)
không phải là thứ index B-tree giúp được.

## Bước 3 — covering index với `INCLUDE (total_amount)`, trước `VACUUM`

**Cạm bẫy gặp phải**: chạy `mvn clean test` cho cả project (không chỉ riêng
file này) một vài lần liên tiếp để double-check thì bài này thỉnh thoảng
FAIL — plan bước 3 đôi khi dùng `idx_orders_status_confirmed_partial`
(Index Scan thường, không phải covering index) thay vì
`idx_orders_status_confirmed_covering` như kỳ vọng. Lý do: trước khi
`VACUUM` chạy, chi phí ước tính của một Index Only Scan (chưa biết trước sẽ
tốn bao nhiêu `Heap Fetches`) đôi khi XẤP XỈ chi phí của một Index Scan
thường qua index partial không-covering đã tạo ở bước 1 — hai index cùng
tồn tại song song khiến planner chọn khác nhau giữa các lần chạy. **Fix**:
xoá cả `idx_orders_status_full` (bước 0) lẫn
`idx_orders_status_confirmed_partial` (bước 1) ngay trước khi tạo covering
index, để bước 3-4 chỉ còn đúng MỘT lựa chọn cho cột `status` — cô lập đúng
chủ đề đang khảo sát (`VACUUM`/visibility map) khỏi một quyết định chi phí
khác đang được so sánh song song. Cách này hợp lý cả về mặt thực tế: một
khi đã có covering index, index partial không-covering trở nên dư thừa
(covering index luôn làm được mọi việc nó làm, cộng thêm khả năng Index
Only Scan) — không có lý do gì giữ cả hai trong production.

```sql
DROP INDEX idx_orders_status_full;
DROP INDEX idx_orders_status_confirmed_partial;
CREATE INDEX idx_orders_status_confirmed_covering
    ON orders (status) INCLUDE (total_amount) WHERE status = 'CONFIRMED';
```

`total_amount` không nằm trong khoá B-tree (không dùng để tìm kiếm/sắp
xếp) — nó chỉ được "cõng theo" ở lá của index để trả thẳng ra mà không cần
quay lại heap, miễn là query chỉ cần đúng những cột có trong index
(`status`, `total_amount`).

```sql
SELECT status, total_amount FROM orders WHERE status = 'CONFIRMED'
```

```
Index Only Scan using idx_orders_status_confirmed_covering on orders
    (cost=0.41..6186.80 rows=49367 width=14) (actual time=0.039..14.556 rows=50000 loops=1)
  Heap Fetches: 33389
Execution Time: 16.892 ms
```

Node đã là `Index Only Scan` ngay lập tức (không cần đợi `VACUUM` để
"nâng cấp" loại node), nhưng dòng `Heap Fetches: 33389` (trên tổng 50.000
dòng, ~67%) cho thấy: **có Index Only Scan không đồng nghĩa "không chạm vào
heap"**. Ngay sau khi bulk insert, visibility map của các trang dữ liệu
CHƯA được đánh dấu "all-visible" (Postgres chưa biết chắc mọi giao dịch khác
đều có thể nhìn thấy các dòng này mà không cần kiểm tra MVCC riêng) — nên
với 2/3 số dòng, Postgres vẫn phải mở heap ra kiểm tra visibility trực tiếp,
làm mất một phần lợi ích của covering index.

## Bước 4 — sau `VACUUM`: Index Only Scan thật sự, không còn heap fetch

```sql
VACUUM orders;
```

```
Index Only Scan using idx_orders_status_confirmed_covering on orders
    (cost=0.41..1516.90 rows=49366 width=14) (actual time=0.027..6.997 rows=50000 loops=1)
  Heap Fetches: 0
Execution Time: 9.264 ms
```

`VACUUM` quét qua bảng, xác nhận trang nào không còn dòng nào bị giao dịch
cũ nào cần thấy phiên bản khác, rồi đánh dấu "all-visible" trên visibility
map. Sau đó, cùng một query: **`Heap Fetches: 0`** — Postgres chỉ đọc đúng
index, không đụng đến heap một lần nào. Kết quả đo được:

| | Trước `VACUUM` | Sau `VACUUM` |
|---|---|---|
| Heap Fetches | 33.389 / 50.000 | **0** |
| Buffers (shared hit + read) | 6.242 + 194 = 6.436 | **195** |
| Cost ước tính | 0.41..6186.80 | **0.41..1516.90** (giảm ~4,1 lần) |
| Execution Time | 16.892 ms | **9.264 ms** |

Cùng một index, cùng một query — hiệu quả thực tế phụ thuộc hoàn toàn vào
việc `VACUUM` đã chạy hay chưa.

## Khái niệm

- **Partial index**: `CREATE INDEX ... WHERE <predicate>` chỉ index các dòng
  thoả `<predicate>`, không phải toàn bộ bảng. Cực kỳ hiệu quả cho workload
  LỆCH (một nhóm giá trị hiếm nhưng được truy vấn thường xuyên, như đơn
  "đang xử lý" giữa hàng triệu đơn "đã hoàn tất") — nhỏ hơn nhiều so với
  index đầy đủ, và Postgres chỉ dùng được nó khi điều kiện của query THEO
  ĐÚNG NGHĨA SUY RA (imply) được predicate của index; nếu không, nó bị bỏ
  qua hoàn toàn, không có "dùng một phần" nửa vời.
- **Covering index** (`INCLUDE (...)`): thêm cột vào lá của index (không
  phải khoá tìm kiếm) để một query CHỈ đọc các cột có trong index (khoá +
  `INCLUDE`) không cần quay lại heap fetch dữ liệu — điều kiện cho
  **Index Only Scan**.
- **Index Only Scan phụ thuộc visibility map, phụ thuộc `VACUUM`**: dù plan
  đã chọn node `Index Only Scan`, số `Heap Fetches` thực tế có thể vẫn lớn
  hơn 0 nếu các trang dữ liệu liên quan chưa được `VACUUM` đánh dấu
  "all-visible" — khi đó Postgres vẫn phải mở heap ra kiểm tra MVCC cho
  từng dòng, làm giảm phần lớn lợi ích của covering index. `Heap Fetches: 0`
  mới là bằng chứng cho một Index Only Scan "thật sự" không chạm heap.

## Tổng kết

`mvn clean test` → **60/60 test pass** (55 test của các mục 1–14 + 5 test
mới của mục 15, toàn bộ chạy trên PostgreSQL thật qua Testcontainers).
