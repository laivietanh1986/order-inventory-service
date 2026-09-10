# Mục 16 — Deep offset pagination và keyset pagination

## Mục tiêu

Đo `OFFSET 0`, `OFFSET 10.000`, `OFFSET 500.000` (cùng `LIMIT 20`) để thấy
đường cong thời gian, rồi sửa bằng seek/keyset pagination:

```sql
WHERE (created_at, id) < (?, ?) ORDER BY created_at DESC, id DESC LIMIT 20
```

Đo riêng chi phí `COUNT(*)`, thử approximate count từ `pg_class.reltuples`,
và kỹ thuật "count up to N+1" (biết có trang kế tiếp mà không cần đếm cả
bảng).

## File đã tạo

| File | Vai trò |
|---|---|
| [`DeepOffsetVsKeysetPaginationTest.java`](../src/test/java/com/example/orderinventory/pagination/DeepOffsetVsKeysetPaginationTest.java) | Toàn bộ thực nghiệm mục 16, trên PostgreSQL thật (Testcontainers, tiếp tục mục 13-15) |

## Dữ liệu seed: vị trí phân trang suy ra được bằng công thức

1.000.000 dòng trên bảng trống, `id` là `IDENTITY` tăng dần 1..1.000.000,
`created_at = BASE_INSTANT.plusSeconds(i)` — tăng CÙNG CHIỀU với `id`. Nhờ
vậy, ở thứ tự `ORDER BY created_at DESC, id DESC`, dòng ở **vị trí P** (đếm
từ 0) chính là dòng có `i = 1.000.000 - P`. Test tận dụng công thức này để
tạo cursor cho các vị trí sâu MÀ KHÔNG CẦN chạy một query dò tìm nào — điều
này không "gian lận" so với thực tế: một client dùng keyset pagination thật
sự cũng không bao giờ tính vị trí kiểu này, họ chỉ lưu lại `(created_at, id)`
của dòng cuối cùng đã nhận được ở trang trước.

Tạo index hỗ trợ đúng `ORDER BY created_at DESC, id DESC`:
```sql
CREATE INDEX idx_orders_created_at_id ON orders (created_at, id);
```
để phép so sánh OFFSET vs keyset diễn ra ở **cùng điều kiện đã có index tối
ưu** — không bị nhiễu bởi chi phí Seq Scan + Sort riêng (như mục 14 đã thấy,
một index tăng dần vẫn phục vụ được `ORDER BY ... DESC` qua Index Scan
Backward).

## OFFSET: càng sâu càng chậm, dù đã có index

```sql
SELECT id, created_at FROM orders ORDER BY created_at DESC, id DESC LIMIT 20 OFFSET <N>
```

| OFFSET | Execution Time |
|---|---|
| 0 | 0.086 ms |
| 10.000 | 3.271 ms |
| 500.000 | **161.175 ms** |

Plan của `OFFSET 500.000`:

```
Limit  (cost=20570.36..20571.19 rows=20 width=16) (actual time=161.120..161.126 rows=20 loops=1)
  ->  Index Only Scan Backward using idx_orders_created_at_id on orders
        (cost=0.42..41140.30 rows=1000000 width=16) (actual time=0.050..141.691 rows=500020 loops=1)
        Heap Fetches: 500020
Execution Time: 161.175 ms
```

Dòng `rows=500020` ở node `Index Only Scan Backward` là chìa khoá: để trả
về 20 dòng SAU vị trí 500.000, Postgres phải **đọc qua đúng 500.020 dòng
đầu tiên trong index rồi vứt bỏ 500.000 dòng đó** — `OFFSET` không "nhảy"
tới vị trí, nó phải ĐẾM tới đó bằng cách đọc thật. Chi phí này tăng
**tuyến tính** theo giá trị OFFSET, bất kể có index hỗ trợ ORDER BY hay
không — index chỉ giúp tránh phải `Sort`, không giúp tránh phải "đọc rồi
bỏ".

## Keyset (seek) pagination: nhanh như trang đầu, bất kể "sâu" bao nhiêu

```sql
SELECT id, created_at FROM orders
WHERE (created_at, id) < ('2020-01-07 01:53:20', 500000)
ORDER BY created_at DESC, id DESC LIMIT 20
```

(cursor `('2020-01-07 01:53:20', 500000)` chính là dòng ở vị trí 500.000 —
tương đương "độ sâu" với `OFFSET 500.000` ở trên)

```
Limit  (cost=0.42..1.46 rows=20 width=16) (actual time=0.128..0.135 rows=20 loops=1)
  ->  Index Only Scan Backward using idx_orders_created_at_id on orders
        (cost=0.42..25902.83 rows=500348 width=16) (actual time=0.127..0.131 rows=20 loops=1)
        Index Cond: (ROW(created_at, id) < ROW('2020-01-07 01:53:20', 500000))
        Heap Fetches: 20
Execution Time: 0.204 ms
```

**0,204 ms** — gần bằng `OFFSET 0` (0,086 ms), và nhanh hơn `OFFSET 500.000`
**~790 lần** cho cùng một "độ sâu" logic. Lý do nằm ở `rows=20` ngay tại
node `Index Only Scan Backward` (so với `rows=500020` ở bản OFFSET): điều
kiện `WHERE (created_at, id) < (...)` là một **index condition** thật sự —
B-tree dùng nó để nhảy thẳng (seek, O(log n)) tới đúng điểm bắt đầu trong
cây rồi đọc tiếp 20 dòng, không phải đếm qua 500.000 dòng đứng trước.

## COUNT(*) chính xác vs `pg_class.reltuples`

| Cách | Giá trị | Thời gian |
|---|---|---|
| `SELECT COUNT(*) FROM orders` | 1.000.000 (chính xác) | **53,9 ms** |
| `SELECT reltuples::bigint FROM pg_class WHERE relname = 'orders'` | 1.000.000 (xấp xỉ) | **3,5 ms** |

`COUNT(*)` chính xác buộc Postgres phải quét toàn bộ bảng — MVCC nghĩa là
không có một con số "tổng số dòng" nào được duy trì sẵn ở bất kỳ đâu, vì
mỗi transaction có thể thấy một tập dòng "còn sống" khác nhau (do các dòng
đang bị xoá/sửa bởi transaction khác nhưng chưa commit). `pg_class.reltuples`
là con số Postgres đã tính sẵn từ lần `ANALYZE`/`VACUUM` gần nhất — chỉ là
một lookup catalog, nhanh gấp **~15 lần**, nhưng là số liệu XẤP XỈ (ước
lượng bằng sampling), không đảm bảo đúng tuyệt đối tại mọi thời điểm — ở
đây trùng khớp 1.000.000 vì `ANALYZE` vừa mới chạy ngay sau khi seed.

## "Count up to N+1": biết có trang kế tiếp mà không cần đếm cả bảng

Thay vì `COUNT(*)` để quyết định hiện nút "Trang sau", chỉ cần xin
`LIMIT (pageSize + 1)`:

```sql
SELECT id FROM orders WHERE (created_at, id) < (?, ?)
ORDER BY created_at DESC, id DESC LIMIT 21  -- pageSize=20 + 1
```

| Vị trí cursor | Số dòng nhận được | `hasNext` |
|---|---|---|
| Vị trí 500.000 (giữa bảng) | **21** | `true` (21 > 20) |
| Vị trí gần cuối (còn đúng 14 dòng phía sau) | **14** | `false` (14 ≤ 20) |

Nhận đủ `pageSize + 1` dòng nghĩa là chắc chắn còn ít nhất 1 dòng nữa sau
trang hiện tại — không cần biết CHÍNH XÁC còn bao nhiêu, chỉ cần biết CÓ
hay KHÔNG. Khi hiển thị, chỉ lấy `pageSize` dòng đầu, dòng thứ `pageSize + 1`
(nếu có) chỉ dùng để suy ra `hasNext` rồi bỏ đi.

## Khái niệm

- **Tại sao `OFFSET` phải đọc rồi bỏ**: Postgres không có cấu trúc nào cho
  phép "nhảy tới dòng thứ N" trực tiếp — kể cả với index, nó vẫn phải duyệt
  tuần tự qua đúng N dòng đầu tiên (theo thứ tự yêu cầu) trước khi bắt đầu
  trả kết quả. Chi phí luôn tỉ lệ thuận với `OFFSET`, không phải với
  `LIMIT`.
- **Keyset cần cột tie-breaker**: chỉ dùng `created_at` làm điều kiện seek
  sẽ SAI nếu có nhiều dòng trùng `created_at` — dòng trùng giá trị có thể bị
  bỏ sót hoặc lặp lại giữa các trang. Thêm `id` (khoá chính, luôn duy nhất)
  làm cột thứ hai trong so sánh tuple `(created_at, id) < (?, ?)` đảm bảo
  thứ tự tổng thể là DUY NHẤT tuyệt đối, bất kể `created_at` có trùng nhau
  hay không.
- **Đánh đổi**: keyset pagination **mất khả năng nhảy tới trang bất kỳ**
  (trang 5, trang 12345) — nó chỉ đi được "trang kế tiếp" hoặc "trang trước"
  dựa trên cursor của trang hiện tại, không có khái niệm "trang số N" độc
  lập. Đây là lý do các UI dùng keyset pagination thường chỉ có nút
  "Trước/Sau" (infinite scroll, feed) chứ không có ô nhập "đi tới trang...".
  `OFFSET` vẫn là lựa chọn đúng khi thực sự cần nhảy trang tuỳ ý và độ sâu
  dữ liệu không quá lớn.

## Tổng kết

`mvn clean test` → **63/63 test pass** (60 test của các mục 1–15 + 3 test
mới của mục 16, toàn bộ chạy trên PostgreSQL thật qua Testcontainers).
