# Lộ trình học JPA / Hibernate (dễ → khó)

Dựa trên các dependency cần có trong `pom.xml` (Web, Data JPA, Validation, PostgreSQL driver, Flyway, Lombok, Testcontainers, springdoc-openapi).
Mỗi project nên làm trên một branch riêng hoặc tuần tự trên cùng project này, commit sau mỗi bước để dễ so sánh trước/sau.

Domain xuyên suốt: **Order & Inventory Service** — `Customer`, `Category`, `Product`, `Tag`, `Inventory`, `Order`, `OrderItem`, `OrderStatusHistory`.

Bật ngay từ project đầu tiên, nếu không sẽ không quan sát được gì:

```properties
spring.jpa.show-sql=true
spring.jpa.properties.hibernate.format_sql=true
spring.jpa.properties.hibernate.generate_statistics=true
logging.level.org.hibernate.orm.jdbc.bind=TRACE
```

## Cấp 1 — Nền tảng (Mapping & vòng đời entity)

1. **Entity đầu tiên và persistence context**
   - Tạo `Product`, `ProductRepository`, thử `save()`, `findById()`, và sửa field sau khi load mà **không** gọi `save()` → quan sát Hibernate vẫn sinh `UPDATE`.
   - Khái niệm: persistence context, dirty checking, ba trạng thái transient / managed / detached, thời điểm flush.

2. **Bidirectional trap — FK bị null**
   - `Order` ↔ `OrderItem` với `@OneToMany(mappedBy = "order")`. Cố tình chỉ gọi `order.getItems().add(item)` mà không set `item.setOrder(order)`.
   - Quan sát: `order_id` null hoặc có thêm một câu `UPDATE` thừa. Sau đó thêm helper method `addItem()` và đếm lại số SQL.
   - Khái niệm: owning side giữ foreign key, `mappedBy` chỉ đánh dấu inverse side và không sinh SQL, tại sao helper method là bắt buộc.

3. **Cascade và orphanRemoval**
   - Thử 4 kịch bản: xoá `Order` khi không cascade, khi `CascadeType.REMOVE`, khi `orphanRemoval = true` nhưng chỉ `items.remove(0)`, và khi cả hai.
   - Cố tình đặt `CascadeType.ALL` trên `OrderItem → Product` rồi xoá order → quan sát `Product` bị xoá theo.
   - Khái niệm: `REMOVE` chỉ kích hoạt khi xoá parent, `orphanRemoval` kích hoạt cả khi tách phần tử khỏi collection, cascade chỉ đúng với quan hệ lifecycle-dependent.

4. **equals / hashCode cho entity**
   - Cho `OrderItem` vào `HashSet` **trước khi** persist, persist xong gọi `set.contains(item)` → `false`.
   - Thử 3 cách: dùng `id`, dùng tất cả field, dùng business key hoặc UUID gán ở client.
   - Khái niệm: tại sao `@GeneratedValue` phá hash contract, entity ở trạng thái detached so sánh thế nào.

5. **Fetch type mặc định là cái bẫy**
   - Khai báo `@ManyToOne` và `@OneToOne` không kèm gì, load 1 `OrderItem` và đếm query.
   - Thử `@OneToOne(fetch = LAZY)` ở phía inverse giữa `Product` và `Inventory` → quan sát nó vẫn EAGER.
   - Khái niệm: default fetch của 4 loại association, tại sao lazy `@OneToOne` inverse không hoạt động nếu không bật bytecode enhancement, `LazyInitializationException`.

## Cấp 2 — Quan hệ nâng cao và truy vấn hiệu quả

6. **ManyToMany và bag semantics**
   - `Product` ↔ `Tag` bằng `@ManyToMany` với `List`. Thêm 1 tag vào product đã có 5 tag → xem log: `DELETE` cả 5 row rồi `INSERT` lại 6.
   - Đổi `List` sang `Set`, sau đó refactor thành entity `ProductTag` tường minh có thêm `createdAt`, `sortOrder`.
   - Khái niệm: bag không có định danh từng row, tại sao production hầu như luôn tách join entity.

7. **Quan hệ tự tham chiếu**
   - `Category` có `parentCategory`, viết endpoint trả về một category kèm toàn bộ subtree.
   - So sánh 2 cách: đệ quy ở tầng Java (N query) và `WITH RECURSIVE` một query.
   - Khái niệm: adjacency list, recursive CTE, so sánh với materialized path và nested set.

8. **Tái hiện N+1 và đo được nó**
   - Load 100 order rồi lặp qua `order.getItems()`. Đếm query bằng `SessionFactory.getStatistics().getPrepareStatementCount()` và viết assertion `isEqualTo(1)` — test này phải **fail** lúc đầu.
   - Khái niệm: cơ chế 1 + N, và quan trọng hơn là cách biến N+1 thành thứ đo được thay vì cảm tính.

9. **Bốn vũ khí chống N+1 và giới hạn của từng cái**
   - Lần lượt áp dụng `JOIN FETCH`, `@EntityGraph`, `@BatchSize(size = 20)`, `@Fetch(SUBSELECT)` lên đúng query ở project 8. Ghi lại số query và hình dạng SQL sinh ra.
   - Khái niệm: `JOIN FETCH` sinh cartesian product, `@BatchSize` biến N thành N/20, `SUBSELECT` giữ query gốc làm subquery — không có cách nào thắng tuyệt đối.

10. **HHH000104 — phân trang chết trong memory**
    - `JOIN FETCH` collection kết hợp `Pageable`, seed 10.000 order, tìm warning `firstResult/maxResults specified with collection fetch; applying in memory` và đo heap.
    - Sửa bằng two-query pattern: query 1 lấy page các `id` không join collection, query 2 `WHERE id IN (:ids) JOIN FETCH`.
    - Khái niệm: tại sao Hibernate buộc phải phân trang trong JVM, đây là ranh giới rõ nhất giữa người dùng JPA và người hiểu JPA.

11. **MultipleBagFetchException**
    - `JOIN FETCH` đồng thời `items` và `statusHistory` (cả hai là `List`) → lỗi ngay lúc khởi động.
    - Sửa bằng 2 cách: đổi sang `Set`, hoặc tách thành 2 query để Hibernate tự merge vào persistence context.
    - Khái niệm: Hibernate không phân biệt được duplicate row của hai bag trong cùng result set.

12. **DTO projection và aggregate query**
    - Ba cách trả danh sách order: load entity rồi map, constructor expression `select new`, interface-based projection. So sánh số cột `SELECT` và thời gian.
    - Viết `customer order-summary` bằng `SELECT count(*), sum(...), max(...)` thay vì load list rồi `stream().reduce()`. Seed 1 khách có 50.000 order để thấy khác biệt.
    - Khái niệm: khi nào không cần entity, `@Transactional(readOnly = true)` tắt dirty checking và bỏ snapshot.

## Cấp 3 — Index và hiệu năng ở scale

> Từ đây chuyển hẳn sang **PostgreSQL thật qua Testcontainers**. H2 sẽ nói dối về execution plan, lock và isolation.

13. **Seed 1 triệu row và đọc EXPLAIN ANALYZE**
    - Viết seeder bằng JDBC batch insert (thử bằng JPA một lần để tự thấy tại sao không nên). Chạy `EXPLAIN (ANALYZE, BUFFERS)` cho query list order khi chưa có index.
    - Khái niệm: Seq Scan / Index Scan / Index Only Scan / Bitmap Heap Scan, chênh lệch `rows` estimate với `actual rows` là dấu hiệu statistics cũ, `shared hit` với `shared read`.

14. **Composite index và leftmost prefix**
    - Query `WHERE customer_id = ? AND status = ? AND created_at BETWEEN ? AND ? ORDER BY created_at DESC`. Tạo lần lượt index `(status)`, `(status, customer_id)`, `(customer_id, status, created_at)` và đo từng cái.
    - Thử query chỉ có `status` mà không có `customer_id` → index bị bỏ qua.
    - Khái niệm: equality column trước, range/sort column sau; leftmost prefix rule; selectivity và tại sao index trên cột 5 giá trị đứng một mình gần như vô dụng.

15. **Partial index và covering index**
    - Bảng 1M order trong đó 95% là `COMPLETED`. Tạo `CREATE INDEX ... WHERE status = 'CONFIRMED'`, so sánh dung lượng bằng `\di+`. Thêm `INCLUDE (total_amount)` để đạt Index Only Scan.
    - Khái niệm: partial index cho workload lệch, covering index tránh heap fetch, Index Only Scan phụ thuộc visibility map nên phụ thuộc `VACUUM`.

16. **Deep offset pagination và keyset pagination**
    - Đo `OFFSET 0`, `OFFSET 10000`, `OFFSET 500000` rồi vẽ đường cong thời gian. Sửa bằng seek pagination `WHERE (created_at, id) < (?, ?) ORDER BY created_at DESC, id DESC LIMIT 20`.
    - Đo riêng chi phí `COUNT(*)`, thử approximate count từ `pg_class.reltuples` và "count up to N+1".
    - Khái niệm: tại sao OFFSET phải đọc rồi bỏ, keyset cần tie-breaker column, đánh đổi là mất khả năng nhảy tới trang bất kỳ.

## Cấp 4 — Transaction và concurrency

> ⚠️ Từ đây, **test method tuyệt đối không đánh `@Transactional`**. Spring sẽ gói cả test vào một transaction, hai thread thực ra dùng chung một connection và concurrency test sẽ pass giả tạo. Dọn dữ liệu thủ công ở `@AfterEach`.

17. **Tái hiện lost update**
    - `ExecutorService` 2 thread + `CountDownLatch`, cùng đọc `availableQty = 1`, cùng trừ 1, cùng save. Chạy ở `READ COMMITTED` mặc định của Postgres.
    - Quan sát: cả hai order đều thành công, stock về 0 hoặc âm.
    - Khái niệm: isolation level, các anomaly (dirty read, non-repeatable read, phantom, lost update, write skew), tại sao `READ COMMITTED` không ngăn lost update, MVCC của Postgres so với gap lock của MySQL.

18. **Ba cách sửa lost update trong cùng một project**
    - Optimistic `@Version` → bắt `OptimisticLockException`, thêm retry loop, đo tỉ lệ retry khi tăng lên 50 thread.
    - Pessimistic `PESSIMISTIC_WRITE` → xem `SELECT ... FOR UPDATE` trong log, đo throughput giảm, thử thêm `NOWAIT` và `SKIP LOCKED`.
    - Atomic conditional update `UPDATE inventory SET available = available - :q WHERE product_id = :id AND available >= :q`, kiểm tra affected rows.
    - Khái niệm: trade-off thật đo được giữa ba chiến lược, `SKIP LOCKED` là chìa khoá cho job queue pattern.

19. **Deadlock có chủ đích**
    - Order A đặt product `[1, 2]`, order B đặt product `[2, 1]`, chạy song song → Postgres báo `deadlock detected`. Sửa bằng cách sort `productId` trước khi khoá.
    - Khái niệm: deadlock sinh từ thứ tự khoá không nhất quán, mitigation chuẩn là sắp xếp deterministic, `deadlock_timeout` và cơ chế tự phát hiện.

20. **Bốn bẫy `@Transactional`**
    - Self-invocation: gọi method `@Transactional` từ method khác cùng class, chứng minh bằng `TransactionSynchronizationManager.isActualTransactionActive()`.
    - Checked exception: `throw new Exception(...)` nhưng dữ liệu vẫn commit.
    - `REQUIRES_NEW`: ghi audit log sống sót dù transaction chính rollback, so sánh với `NESTED` (savepoint).
    - Side effect trước commit: `ApplicationEventPublisher` thường so với `@TransactionalEventListener(phase = AFTER_COMMIT)`, chứng minh consumer không thấy dữ liệu ở cách đầu.
    - Khái niệm: proxy-based AOP, rollback rule mặc định, `readOnly` không chỉ là hint, không gọi HTTP bên ngoài trong transaction vì cạn connection pool.

21. **State machine an toàn dưới concurrency**
    - Confirm và cancel cùng một order đồng thời. Bản sai: `if (order.getStatus() == CREATED) { ... }` → cả hai cùng qua, `status_history` có 2 row. Bản đúng: `UPDATE orders SET status = 'CONFIRMED' WHERE id = ? AND status = 'CREATED'` rồi kiểm tra affected rows.
    - Thêm test idempotency: gọi cancel 2 lần phải cho cùng kết quả.
    - Khái niệm: check-then-act ở tầng application luôn là race condition, đẩy điều kiện xuống câu `UPDATE`, idempotency key.

## Cấp 5 — Kiến trúc, migration và kiểm thử

22. **Flyway migration và bỏ `ddl-auto`**
    - Viết toàn bộ schema thành migration script có version, tắt `spring.jpa.hibernate.ddl-auto`. Thử một migration đổi cột trên bảng 1M row và đo thời gian khoá bảng.
    - Khái niệm: tại sao `ddl-auto=update` bị cấm ở production, backward-compatible migration, expand-and-contract pattern khi deploy nhiều instance.

23. **Kiểm thử tầng dữ liệu**
    - Testcontainers với Postgres, assertion số query để bắt N+1 regression, concurrency test bằng `CountDownLatch`, `@DataJpaTest` so với `@SpringBootTest`.
    - Khái niệm: tại sao `@Transactional` trên test làm sai kết quả, test data cleanup, vì sao H2 không thay thế được Postgres cho nhóm test này.

24. **Khi transaction đơn không còn đủ**
    - Tách `Inventory` thành service riêng với database riêng, order không còn confirm được trong một transaction. Implement Transactional Outbox và một saga có compensating transaction.
    - Thêm trạng thái trung gian `PENDING_RESERVATION` để order không bao giờ confirm khi reservation chưa chắc thành công.
    - Khái niệm: dual-write problem, outbox pattern, saga (choreography vs orchestration), eventual consistency, idempotent consumer với at-least-once delivery.

---

### Cách dùng file này

- Làm tuần tự từng mục, mỗi mục một commit riêng, message ghi rõ **bug tái hiện** và **cách sửa** để sau này đọc diff là nhớ lại được.
- Mỗi mục phải kết thúc bằng một **test fail nếu bug quay lại**, không phải một `main()` in ra console.
- Sau mỗi cấp, tự đặt câu hỏi: "Nếu bỏ dòng cấu hình X thì điều gì xảy ra?" — rồi thử bỏ thật, thay vì chỉ copy code.
- Không đọc lý thuyết trước khi thấy lỗi thật. Thứ tự đúng là: gây ra hiện tượng → quan sát SQL → mới đọc giải thích.

### Fast path

- **Cần gấp cho phỏng vấn (~10h):** mục 10, 18, 20, 21 — bốn mục này phủ phần được hỏi nhiều nhất.
- **~20h:** thêm mục 2, 5, 8, 12, 14, 16.
- **Đầy đủ:** làm hết 24 mục, khoảng 60-70h.

### Tài liệu tham khảo

| Chủ đề | Nguồn |
| :--- | :--- |
| Mapping, N+1, fetch strategy | Vlad Mihalcea — *High-Performance Java Persistence* |
| Index, EXPLAIN, pagination | *Use The Index, Luke* |
| Isolation level và anomaly | *Designing Data-Intensive Applications* — chương 7 |
| Locking, MVCC | PostgreSQL docs — Concurrency Control |
| Transaction pitfalls | Spring Framework docs — Transaction Management |
