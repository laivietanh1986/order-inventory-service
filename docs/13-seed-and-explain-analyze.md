# Mục 13 — Seed 1 triệu row và đọc EXPLAIN ANALYZE

## Mục tiêu

Đóng lại **Cấp 2** và mở **Cấp 3 — Index và hiệu năng ở scale**. Từ mục này,
project chuyển hẳn từ H2 sang **PostgreSQL thật qua Testcontainers**, vì H2
không có `EXPLAIN ANALYZE` thật, không có Seq Scan/Index Scan/Bitmap Heap
Scan, không có MVCC/lock/isolation giống Postgres — nó "nói dối" về mọi thứ
liên quan đến execution plan.

Ba việc cần làm:
1. Viết seeder bằng JDBC batch insert cho 1.000.000 row.
2. Thử seed bằng JPA (`persist()` trong vòng lặp, không flush/clear) một lần
   để tự thấy tại sao không nên dùng cách này ở quy mô lớn.
3. Chạy `EXPLAIN (ANALYZE, BUFFERS)` cho một query lọc trên bảng chưa có
   index nào ngoài PK, và đọc hiểu plan thật.

## File đã tạo

| File | Vai trò |
|---|---|
| [`SeedAndExplainAnalyzeTest.java`](../src/test/java/com/example/orderinventory/explain/SeedAndExplainAnalyzeTest.java) | Toàn bộ lesson 13: seed 1 triệu row, so sánh JPA vs JDBC, chạy EXPLAIN ANALYZE |

Không có thay đổi nào ở code production (entity, repository) — mục này thuần
về seeding và đọc execution plan trên dữ liệu có sẵn của `orders`.
`pom.xml` đã có sẵn `spring-boot-testcontainers`, `testcontainers-junit-jupiter`
và `testcontainers-postgresql` từ trước nên không cần thêm dependency.

## Cạm bẫy đầu tiên: thứ tự khởi động `@Testcontainers` + `@ServiceConnection`

Thử đầu tiên dùng `@TestInstance(Lifecycle.PER_CLASS)` để `@BeforeAll` không
cần `static`, seed dữ liệu 1 lần cho cả lớp test. Kết quả: context load thất
bại ngay lập tức:

```
Caused by: java.lang.IllegalStateException: Mapped port can only be obtained
after the container is started
	at org.testcontainers.containers.PostgreSQLContainer.getJdbcUrl(...)
	at ...JdbcContainerConnectionDetailsFactory$JdbcContainerConnectionDetails.getJdbcUrl(...)
	at ...DataSourceConfiguration.createDataSource(...)
```

Lý do: với `PER_CLASS`, JUnit phải **tạo instance test trước** để có thể gọi
`@BeforeAll` không-static trên đó, và việc tạo instance này kích hoạt
`postProcessTestInstance` — nơi Spring nạp `ApplicationContext` (bao gồm tạo
`DataSource`/`HikariDataSource` trỏ vào container). Nhưng extension
`@Testcontainers` (chịu trách nhiệm start container tĩnh đánh dấu
`@Container`) chỉ chạy `beforeAll` callback SAU bước đó trong lifecycle
`PER_CLASS` — container chưa kịp start thì Spring đã cố lấy mapped port của
nó rồi.

**Fix**: quay về lifecycle mặc định (`PER_METHOD`, bỏ hẳn
`@TestInstance`), và khai báo `@BeforeAll` là `static` với tham số
`@Autowired JdbcTemplate` — Spring hỗ trợ resolve tham số `@Autowired` cho
các lifecycle method tĩnh của JUnit 5 (nạp `ApplicationContext` on-demand
ngay tại điểm đó), và với `PER_METHOD`, `@Testcontainers` đảm bảo container
đã start xong trước khi bất kỳ instance test nào được tạo. Sau khi sửa,
context load bình thường và toàn bộ 11 migration (V1–V11, vốn chỉ mới chạy
trên H2 từ trước tới giờ) áp dụng thành công lên Postgres thật ngay trong
lần chạy đầu tiên — không migration nào dùng cú pháp chỉ H2 mới hiểu.

## Cách 1 (không nên): JPA `persist()` trong vòng lặp, không flush/clear

`application.yaml` không cấu hình `hibernate.jdbc.batch_size`, nên mỗi
`entityManager.persist()` khi flush sẽ sinh **một prepared statement riêng**
— không có gộp batch nào ở tầng JDBC. Test seed 5.000 row theo cách này
(chỉ 5.000, không phải 1 triệu — làm thật với 1 triệu theo kiểu này sẽ giữ
toàn bộ 1 triệu entity managed cùng lúc trong RAM, cực dễ `OutOfMemoryError`
và không thực tế để chạy trong một bài test):

```java
statistics().clear();
long jpaStart = System.nanoTime();
for (int i = 0; i < JPA_DEMO_COUNT; i++) {
    entityManager.persist(new Order("JPA Seed " + i, "CREATED"));
}
entityManager.flush();
long jpaDurationNanos = System.nanoTime() - jpaStart;
long jpaStatementCount = statistics().getPrepareStatementCount();
```

Kết quả đo được thật (2 lần chạy độc lập, cùng 5.000 row):

| Cách | Thời gian | Số prepared statement |
|---|---|---|
| JPA `persist()` loop, không flush/clear | **11.521 – 12.175 ms** (~11.5–12.2 giây) | **5.000** (đúng bằng số row — không hề gộp) |
| JDBC `batchUpdate()` | **109 – 185 ms** | 1 lệnh `batchUpdate` (driver tự gộp round-trip) |

Chênh lệch **~65–105 lần** cho cùng 5.000 row. Hibernate còn tự in ra
`Session Metrics` xác nhận điều này:

```
Session Metrics {
    115556800 nanoseconds spent preparing 5000 JDBC statements;
    9200292900 nanoseconds spent executing 5000 JDBC statements;
    0 nanoseconds spent executing 0 JDBC batches;
    ...
    283963300 nanoseconds spent executing 1 flushes (flushing a total of 5000 entities and 10000 collections);
}
```

`0 nanoseconds spent executing 0 JDBC batches` là dòng quan trọng nhất: xác
nhận không có batching nào xảy ra ở tầng Hibernate/JDBC — toàn bộ 9.2 giây là
9.2 giây gửi 5.000 round-trip riêng lẻ tới Postgres. Ngoại suy tuyến tính cho
1 triệu row: cỡ **hơn 2.000 giây (~35+ phút)** chỉ để insert theo cách này —
chưa kể rủi ro `OutOfMemoryError` vì persistence context giữ cả 1 triệu
entity cùng lúc trước khi flush.

## Cách 2 (nên dùng): JDBC batch insert 1 triệu row

```java
List<Object[]> batchArgs = new ArrayList<>(BATCH_SIZE); // BATCH_SIZE = 1_000
for (int i = 1; i <= ONE_MILLION; i++) {
    String status = (i % 100 == 0) ? "CANCELLED" : (i % 10 == 0 ? "CONFIRMED" : "CREATED");
    batchArgs.add(new Object[]{"Customer " + (i % 200_000), status, BigDecimal.valueOf(i % 1000)});
    if (batchArgs.size() == BATCH_SIZE) {
        jdbcTemplate.batchUpdate(INSERT_ORDER_SQL, batchArgs);
        batchArgs.clear();
    }
}
```

Kết quả đo thật: **25.869 ms (~25.9 giây)** cho đúng 1.000.000 row — hoàn
toàn bỏ qua Hibernate, đi thẳng JDBC `PreparedStatement.addBatch()` theo
lô 1.000, đúng như cách các lesson trước (10, 12) đã dùng để seed nhanh.

## EXPLAIN (ANALYZE, BUFFERS) khi chưa có index

Bảng `orders` lúc này chỉ có index ngầm định trên PK (`id`) — cột `status`
(90% `CREATED` / 9% `CONFIRMED` / 1% `CANCELLED`, tức ~10.000/1.000.000 row
là `CANCELLED`) hoàn toàn không có index nào. Query:

```sql
EXPLAIN (ANALYZE, BUFFERS, FORMAT TEXT)
SELECT * FROM orders WHERE status = 'CANCELLED'
```

Plan thật capture được (một trong hai lần chạy độc lập):

```
Gather  (cost=1000.00..15479.97 rows=9367 width=35) (actual time=0.249..80.508 rows=10000 loops=1)
  Workers Planned: 2
  Workers Launched: 2
  Buffers: shared hit=8335
  ->  Parallel Seq Scan on orders  (cost=0.00..13543.27 rows=3903 width=35) (actual time=0.026..70.783 rows=3333 loops=3)
        Filter: ((status)::text = 'CANCELLED'::text)
        Rows Removed by Filter: 330000
        Buffers: shared hit=8335
Planning:
  Buffers: shared hit=9
Planning Time: 0.145 ms
Execution Time: 81.234 ms
```

Đọc plan này:

- **`Parallel Seq Scan on orders`**: không có index nào dùng được cho
  `status`, nên Postgres KHÔNG CÓ LỰA CHỌN nào khác ngoài quét tuần tự toàn
  bộ bảng — dù bảng có 1 triệu dòng. Đây chính là assertion chắc chắn nhất
  của test (`containsIgnoringCase("Seq Scan on orders")`), không phụ thuộc
  statistics mới hay cũ.
- **`Gather` + `Workers Planned/Launched: 2`**: vì bảng đủ lớn, planner tự
  quyết định chia việc quét cho 2 worker process song song rồi gom
  (`Gather`) kết quả lại — mỗi worker quét ~1/3 bảng
  (`loops=3` ở dòng `Parallel Seq Scan`, mỗi loop trả `rows=3333` → tổng
  10.000 khớp actual rows ở `Gather`).
- **`Rows Removed by Filter: 330000`**: mỗi loop quét khoảng 333.333 dòng
  (~1/3 của 1 triệu) và loại bỏ 330.000 dòng không khớp `status = 'CANCELLED'`
  — con số cụ thể, đo được, không phải ước lượng.
- **`Buffers: shared hit=8335`, hoàn toàn không có `shared read`**: tất cả
  block 8KB cần đọc đều đã có sẵn trong `shared_buffers` (cache của chính
  Postgres server) — hợp lý vì dữ liệu vừa được insert ngay trước đó, còn
  "nóng" trong cache, chưa bị đẩy ra ngoài. Nếu bảng đã được insert từ lâu
  và bộ nhớ cache đã bị các query khác chiếm chỗ, ta sẽ thấy `shared read`
  xuất hiện (phải đọc từ đĩa/OS page cache) — chậm hơn hẳn `shared hit`.

### `rows` estimate vs `actual rows`: một phát hiện bất ngờ

Giả thuyết ban đầu (theo đúng nội dung lesson) là: bảng vừa insert 1 triệu
dòng bằng `INSERT` thường (không phải `COPY`), Postgres CHƯA kịp chạy
`ANALYZE`, nên `rows` (ước lượng của planner) sẽ lệch xa so với actual rows
— dấu hiệu kinh điển của statistics cũ.

Số liệu đo thật qua 2 lần chạy độc lập:

| Lần chạy | estimated rows | actual rows | Chênh lệch |
|---|---|---|---|
| 1 | 9.736 | 10.000 | 2,7% |
| 2 | 9.367 | 10.000 | 6,3% |

**Ước lượng khá sát thực tế, không lệch xa như giả thuyết ban đầu.** Lý do
thực sự: việc seed 1 triệu row bằng JDBC batch mất **~26 giây**, đủ lâu để
`autovacuum` (mặc định bật trong image `postgres:16-alpine` của
Testcontainers) chạy `ANALYZE` nền ít nhất một lần trong lúc đang insert.
Ngưỡng kích hoạt autovacuum-analyze là
`autovacuum_analyze_threshold + autovacuum_analyze_scale_factor × reltuples`
(mặc định `50 + 0.1 × reltuples`) — với một bảng mới tạo, `reltuples` bắt
đầu từ 0 nên ngưỡng ban đầu chỉ là 50 dòng thay đổi, cực kỳ dễ đạt ngay từ
đầu quá trình seed, rồi ngưỡng tự tăng dần khi `reltuples` được cập nhật sau
mỗi lần ANALYZE. Kết quả: table statistics **không "cũ" như trực giác** —
đây chính là bài học thật, khác với giả thuyết đọc từ tài liệu: "statistics
cũ sau khi bulk insert" là một hiện tượng **có điều kiện** (phụ thuộc
autovacuum có đủ thời gian chạy hay không), không phải quy luật tuyệt đối.
Muốn tái hiện đúng "stats cũ 100%", cần insert nhanh hơn (ví dụ dùng
`COPY`) hoặc tắt tạm `autovacuum` trước khi seed rồi `EXPLAIN` ngay, hoặc
seed trong một transaction lớn rồi rollback thống kê giả lập — nằm ngoài
phạm vi mục này.

## Khái niệm

- **Seq Scan**: quét tuần tự toàn bộ bảng, không dùng index. Là lựa chọn
  DUY NHẤT khi không có index phù hợp, hoặc là lựa chọn RẺ HƠN Index Scan
  khi query trả về một phần lớn bảng (planner ước tính chi phí random I/O
  của Index Scan cho quá nhiều dòng sẽ đắt hơn quét tuần tự).
- **Index Scan**: dùng index để tìm trực tiếp các dòng khớp điều kiện, rồi
  quay lại heap (bảng chính) để lấy các cột không có trong index. Hiệu quả
  khi số dòng khớp là một phần NHỎ của bảng.
- **Index Only Scan**: như Index Scan nhưng KHÔNG cần quay lại heap, vì mọi
  cột cần thiết đã có sẵn trong chính index (kèm điều kiện visibility map
  cho phép bỏ qua kiểm tra MVCC ở heap).
- **Bitmap Heap Scan**: bước trung gian giữa hai loại trên — dùng index để
  gom một "bitmap" các trang (page) cần đọc, sắp xếp lại theo thứ tự vật lý
  trên đĩa rồi mới đọc heap tuần tự theo bitmap đó, giảm số lần seek ngẫu
  nhiên so với Index Scan thuần khi số dòng khớp tương đối nhiều.
- Bốn loại trên **chưa xuất hiện đủ trong mục này** vì bảng `orders` chưa có
  index nào ngoài PK — mục 14 (thêm index) sẽ là lúc thấy Postgres chuyển
  từ Seq Scan sang Index/Bitmap Heap Scan, và so sánh chi phí thực tế.
- **`rows` (estimate) vs `actual rows`**: chênh lệch lớn là dấu hiệu
  statistics cũ (bảng thay đổi nhiều nhưng `ANALYZE` chưa chạy lại) khiến
  planner chọn sai chiến lược (ví dụ chọn Nested Loop thay vì Hash Join, hay
  Seq Scan thay vì Index Scan). Như đã thấy ở trên, mức độ "cũ" phụ thuộc
  vào timing thực tế của autovacuum — phải đo, không thể giả định.
- **`shared hit` vs `shared read`** (trong `BUFFERS`): `shared hit` là đọc
  từ cache của Postgres server (`shared_buffers`, RAM), `shared read` là
  phải đọc từ đĩa/OS page cache — chậm hơn đáng kể. Tỉ lệ `shared read` cao
  trên một query chạy lặp lại là dấu hiệu `shared_buffers` quá nhỏ so với
  working set của workload.

## Tổng kết

Toàn bộ test suite: `mvn clean test` → **50/50 test pass** (47 test của các
mục 1–12 trên H2 + 3 test mới của mục 13 trên PostgreSQL thật qua
Testcontainers).
