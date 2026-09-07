# Lộ trình hợp nhất — Data Layer + Spring Security

> Gộp **20 project JPA/Index/Transaction** và **16 project Spring Security** thành một
> lộ trình 10 tuần, xây dựng trên **một codebase duy nhất** phát triển dần.
>
> Ưu tiên: Data Layer đi trước (phục vụ take-home assessment), Security bám theo sau
> và cắm vào đúng những chỗ hai chủ đề giao nhau.

---

## Triết lý của lộ trình này

**Một ứng dụng, lớn dần.** Không tạo 36 project rời rạc. Anh xây một service duy nhất — `Order & Inventory Service` — và mỗi tuần thêm một lớp năng lực. Đến tuần 10 nó là một hệ thống có thể đưa vào portfolio, không phải 36 thư mục demo bỏ đi.

**Tái hiện bug trước, sửa sau.** Mọi project đều bắt đầu bằng việc cố tình làm sai để thấy hiện tượng, rồi mới sửa. Không đọc lý thuyết trước khi thấy lỗi thật.

**Hai chủ đề không song song mà đan xen.** Data layer và Security giao nhau ở 5 điểm cụ thể (đánh dấu 🔗 bên dưới). Học tách rời sẽ bỏ lỡ chính những điểm này — mà đó lại là chỗ phỏng vấn hay đào.

**Ký hiệu:**
- `D1`–`D20` = project Data Layer
- `S1`–`S16` = project Security
- 🔗 = điểm giao thoa, làm chung một lần

---

## Bảng tổng quan 10 tuần

| Tuần | Trọng tâm | Project | Giờ |
| :--- | :--- | :--- | :--- |
| 1 | Mapping & lifecycle | D1–D5 | 8h |
| 2 | Auth nền tảng + User entity | S1–S5 🔗 | 8h |
| 3 | N+1 và query efficiency | D6–D10 | 12h |
| 4 | Authorization & error handling | S6, S7, S16 | 8h |
| 5 | Indexing & EXPLAIN | D11–D14 | 10h |
| 6 | JWT & stateless | S8, S9, S13 | 10h |
| 7 | Transaction & locking | D15–D17 | 8h |
| 8 | `@Transactional` traps + ownership | D18, D19, S10 🔗 | 10h |
| 9 | **Capstone** — nguyên đề take-home | D20 | 8h |
| 10 | OAuth2, microservices, hardening | S11, S12, S14, S15 | 10h |

**Tổng: ~92h.** Nếu làm 2h/ngày trong tuần + 4h cuối tuần → đúng 10 tuần.

---

## Tuần 1 — Mapping & Lifecycle

Xây bộ khung domain: `Customer`, `Category`, `Product`, `Tag`, `Inventory`, `Order`, `OrderItem`, `OrderStatusHistory`.

| # | Project | Bug tái hiện | Nắm được |
| :--- | :--- | :--- | :--- |
| D1 | Bidirectional trap | FK `order_id` null hoặc UPDATE thừa | Owning vs inverse side, `mappedBy` không sinh SQL |
| D2 | Cascade & orphanRemoval | Xoá Order làm xoá luôn Product | `REMOVE` vs `orphanRemoval`, cascade chỉ đúng với lifecycle-dependent |
| D3 | equals/hashCode | `set.contains(item)` = false sau persist | Tại sao `@GeneratedValue` phá hash contract |
| D4 | EAGER mặc định | Load 1 OrderItem sinh nhiều query | Default fetch của 4 association, lazy `@OneToOne` inverse không hoạt động |
| D5 | ManyToMany | DELETE-ALL rồi INSERT lại | Bag semantics, tại sao nên tách join entity |

**Bật ngay từ đầu:**
```properties
spring.jpa.show-sql=true
spring.jpa.properties.hibernate.format_sql=true
spring.jpa.properties.hibernate.generate_statistics=true
logging.level.org.hibernate.orm.jdbc.bind=TRACE
```

**Cuối tuần 1 phải trả lời được:** `mappedBy` đặt ở bên nào và tại sao? Helper method `addItem()` giải quyết vấn đề gì?

---

## Tuần 2 — Auth nền tảng + User entity 🔗

Đây là điểm giao thoa đầu tiên. `S5` (User trong database) chính là **một bài tập mapping nữa** — làm nó ngay sau tuần 1 để tận dụng kiến thức còn nóng.

| # | Project | Nội dung |
| :--- | :--- | :--- |
| S1 | Hello Security mặc định | Quan sát password ngẫu nhiên trong log, tại sao mọi request bị chặn |
| S2 | User trong properties | `InMemoryUserDetailsManager` được autoconfigure ra sao |
| S3 | Basic Auth cho REST | Viết `SecurityFilterChain` bean đầu tiên, public vs protected route |
| S4 | Nhiều user + roles | `PasswordEncoder` (BCrypt), `hasRole()` vs `hasAuthority()` |
| S5 🔗 | **User trong DB** | Entity `User`, custom `UserDetailsService`, mapping `User ↔ Role` |

**Điểm giao thoa cần chú ý ở S5:**
- `User ↔ Role` là quan hệ many-to-many → **áp dụng bài học D5**, dùng join entity hay `@ManyToMany`?
- `UserDetailsService.loadUserByUsername()` chạy **ngoài transaction** → nếu `roles` là `LAZY` sẽ nổ `LazyInitializationException`. Đây là bài học D4 quay lại ở ngữ cảnh thật. Sửa bằng `JOIN FETCH` chứ không phải bằng cách đổi sang `EAGER`.

**Cuối tuần 2:** app có login thật, user đọc từ H2, password đã hash.

---

## Tuần 3 — N+1 và Query Efficiency

Tuần nặng nhất về kỹ thuật. Đây là phần chiếm trọng số cao trong take-home.

| # | Project | Trọng tâm |
| :--- | :--- | :--- |
| D6 | Tái hiện N+1 và **đo** nó | Assert `getPrepareStatementCount()`, test phải fail lúc đầu |
| D7 | 4 vũ khí chống N+1 | `JOIN FETCH` / `@EntityGraph` / `@BatchSize` / `SUBSELECT` và giới hạn từng cái |
| D8 | **HHH000104** | Phân trang chết trong memory → two-query pattern |
| D9 | MultipleBagFetchException | Hai `List` không JOIN FETCH cùng lúc được |
| D10 | DTO projection & aggregate | Constructor expression, `readOnly=true`, `SUM/COUNT` thay vì stream |

> Chuyển sang **Testcontainers + PostgreSQL** từ đây. H2 sẽ nói dối về plan, lock và isolation ở các tuần sau.

**D8 là project quan trọng nhất tuần này.** Đề take-home coi warning `HHH000104` là fail. Đây cũng là ranh giới rõ nhất giữa người *dùng* JPA và người *hiểu* JPA.

---

## Tuần 4 — Authorization & Error Handling

| # | Project | Nội dung |
| :--- | :--- | :--- |
| S6 | Phân quyền endpoint + method | `@EnableMethodSecurity`, `@PreAuthorize`, URL-level vs method-level |
| S7 | Validation + Security | Tách 400 (validation) / 401 (chưa auth) / 403 (không đủ quyền) đúng cách |
| S16 | Test cho Security | `@WithMockUser`, `SecurityMockMvcRequestPostProcessors` |

**S7 đáng đầu tư hơn vẻ ngoài của nó.** Rất nhiều codebase nhầm lẫn 401 và 403, hoặc để `AccessDeniedException` bị `@ControllerAdvice` nuốt thành 500. Cần hiểu:
- `AuthenticationEntryPoint` xử lý 401, `AccessDeniedHandler` xử lý 403.
- `@PreAuthorize` ném exception **bên trong** filter chain hay ngoài? Tuỳ vị trí đặt → quyết định ai bắt được nó.
- Kết hợp RFC 7807 `ProblemDetail` cho response lỗi nhất quán.

Đưa `S16` lên sớm (thay vì để cuối như file gốc) vì từ tuần này trở đi mọi endpoint đều có security — không có `@WithMockUser` thì mọi integration test sẽ 401.

---

## Tuần 5 — Indexing & EXPLAIN

| # | Project | Trọng tâm |
| :--- | :--- | :--- |
| D11 | Seed 1M row + đọc `EXPLAIN ANALYZE` | Seq Scan vs Index Scan, `rows` estimate vs `actual rows`, BUFFERS |
| D12 | Composite index & leftmost prefix | Equality trước, range/sort sau; selectivity |
| D13 | Partial & covering index | `WHERE status = ...`, `INCLUDE`, điều kiện Index Only Scan |
| D14 | Deep offset → keyset pagination | Đường cong `OFFSET`, seek pagination, chi phí `COUNT(*)` |

Seeder viết bằng **JDBC batch insert**, không dùng JPA — thử dùng JPA một lần để tự thấy tại sao.

**Cuối tuần 5:** anh có sẵn output `EXPLAIN ANALYZE` before/after để dán vào README của capstone tuần 9.

---

## Tuần 6 — JWT & Stateless

| # | Project | Nội dung |
| :--- | :--- | :--- |
| S8 | Register + login trả JWT | `OncePerRequestFilter`, `SessionCreationPolicy.STATELESS`, ký/verify |
| S9 | Refresh token | Access ngắn + refresh dài, revoke/blacklist, lưu refresh token trong DB |
| S13 | CORS & CSRF | Khi nào tắt CSRF được, cấu hình CORS cho SPA |

**Điểm nối với data layer ở S9:** bảng `refresh_token` sẽ lớn rất nhanh (mỗi login một row) và luôn query theo `token` + `expires_at`. Đây là chỗ áp dụng ngay bài học D12/D13:
- Index nào cho `WHERE token = ? AND revoked = false AND expires_at > now()`?
- Partial index `WHERE revoked = false` có hợp lý không?
- Job dọn token hết hạn — dùng `DELETE ... WHERE expires_at < ?` theo batch để tránh khoá bảng lâu.

**S13 cần hiểu bản chất, không copy config:** CSRF chỉ có ý nghĩa khi trình duyệt tự động gửi credential (cookie/session). Với JWT trong header `Authorization`, trình duyệt không tự gửi → tắt CSRF là hợp lý. Nếu lưu JWT trong cookie thì CSRF quay lại thành vấn đề — phải giải thích được cả hai chiều.

---

## Tuần 7 — Transaction & Locking

> ⚠️ **Từ đây: test method tuyệt đối không đánh `@Transactional`.**
> Spring sẽ gói cả test vào một transaction duy nhất, hai "thread" thực ra dùng chung
> một connection → concurrency test **pass giả tạo**. Dọn dữ liệu thủ công ở `@AfterEach`.

| # | Project | Trọng tâm |
| :--- | :--- | :--- |
| D15 | Tái hiện lost update | 2 thread + `CountDownLatch`, stock về âm ở `READ COMMITTED` |
| D16 | 3 cách sửa | Optimistic `@Version` / Pessimistic `FOR UPDATE` / Atomic conditional UPDATE |
| D17 | Deadlock có chủ đích | Order A `[1,2]` vs Order B `[2,1]` → sort productId để sửa |

**D16 là project có giá trị phỏng vấn cao nhất toàn bộ lộ trình.** Làm cả ba cách trong **cùng một project** để so sánh trực tiếp, đo tỉ lệ retry của optimistic khi tăng lên 50 thread, đo throughput giảm của pessimistic. Học thêm `SKIP LOCKED` — chìa khoá cho job queue pattern.

---

## Tuần 8 — `@Transactional` traps + Ownership 🔗

Điểm giao thoa lớn thứ hai, và là tuần thú vị nhất.

| # | Project | Nội dung |
| :--- | :--- | :--- |
| D18 | 4 bẫy `@Transactional` | Self-invocation, checked exception không rollback, `REQUIRES_NEW`, `AFTER_COMMIT` |
| D19 | State machine an toàn | Check-then-act là race condition → `UPDATE ... WHERE status = 'CREATED'` |
| S10 🔗 | Ownership authorization | User chỉ sửa được Order của chính mình |

**Tại sao S10 phải nằm cạnh D19:** authorization dựa trên dữ liệu (`@PreAuthorize("@orderService.isOwner(#id, principal)")`) là **một dạng check-then-act khác**. Kiểm tra quyền sở hữu ở thời điểm T, thao tác ở thời điểm T+1 — giữa hai thời điểm đó dữ liệu có thể đổi. Bài học D19 áp dụng nguyên vẹn: đẩy điều kiện xuống câu `UPDATE`.

```sql
UPDATE orders SET status = 'CANCELLED'
WHERE id = ? AND customer_id = ? AND status = 'CREATED'
```

Một câu lệnh giải quyết cả authorization lẫn state transition, atomic. So sánh với cách làm `@PreAuthorize` + load + check + save và giải thích khác biệt — đây là câu trả lời ở tầm Principal.

**Điểm nối thứ hai:** `@PreAuthorize` gọi vào bean service → nếu method đó gọi method `@Transactional` trong cùng class thì dính self-invocation của D18. Hai proxy (security proxy + transaction proxy) xếp chồng nhau — thứ tự do `@Order` quyết định.

---

## Tuần 9 — Capstone

**D20 — làm nguyên đề take-home Order & Inventory Service:**

- Flyway migration tường minh, **không** `ddl-auto=update`
- Seeder 1M order
- Đầy đủ 7 API, có security từ tuần 2/4/6
- Toàn bộ test bắt buộc: 2 concurrency test + 1 N+1 regression test + security test
- README trả lời 3 câu hỏi, kèm `EXPLAIN ANALYZE` before/after lấy từ tuần 5

Đây là lúc mọi thứ ghép lại. Nếu tuần 1–8 làm nghiêm túc thì tuần này chủ yếu là lắp ráp và viết README.

---

## Tuần 10 — Kiến trúc & Hardening

| # | Project | Nội dung |
| :--- | :--- | :--- |
| S11 | OAuth2 Login | Authorization Code flow, ánh xạ `OAuth2User` vào user nội bộ |
| S12 | Resource Server | Keycloak, JWK, issuer, scope-based authorization |
| S15 | Multi-module / microservices | Tách Auth Service, service-to-service auth |
| S14 | Rate limit, audit, brute-force | Account lockout, `AuditorAware`, logging |

**S15 nối thẳng vào Question C của đề take-home** — câu hỏi mở rộng về inventory nằm ở service riêng. Khi tách service, transaction đơn không còn khả dụng:
- **Transactional Outbox** — cách duy nhất đúng để atomic giữa ghi DB và publish event
- **Saga** + compensating transaction
- Trạng thái trung gian `PENDING_RESERVATION` — order không bao giờ confirm khi reservation chưa chắc thành công
- Idempotent consumer với at-least-once delivery

**S14 có phần data layer đáng chú ý:** audit log là bảng append-only tăng rất nhanh → đây là use case kinh điển của **partitioning theo thời gian** và index chỉ trên partition gần đây. Nối lại bài học D13.

---

## Năm điểm giao thoa cần ghi nhớ

| # | Giao thoa | Bài học |
| :--- | :--- | :--- |
| 1 | `UserDetailsService` + lazy roles | `LazyInitializationException` ngoài transaction → `JOIN FETCH`, không phải `EAGER` |
| 2 | `refresh_token` table | Partial index, batch delete để tránh khoá bảng |
| 3 | Ownership check + state machine | Cả hai đều là check-then-act → gộp vào một `UPDATE ... WHERE` |
| 4 | Security proxy + Transaction proxy | Thứ tự proxy, self-invocation phá cả hai |
| 5 | Audit log ở scale | Append-only table → partitioning theo thời gian |

---

## Fast path khi thiếu thời gian

**Nếu take-home gấp (~2 tuần):**
Tuần 1 (D1, D4) → Tuần 3 (D6, D8, D10) → Tuần 5 (D12, D14) → Tuần 7 (D15, D16) → Tuần 8 (D18, D19) → Tuần 9 (D20).
Bỏ toàn bộ Security, quay lại sau.

**Nếu chỉ có ~10h và cần tối đa điểm phỏng vấn data layer:**
**D8, D16, D18, D19** — bốn project phủ đúng 60% trọng số chấm của đề.

**Nếu mục tiêu là Security thay vì take-home:**
S1–S5 → S6, S7, S16 → S8, S9, S13 → S10 → S12, S15. Bỏ D, nhưng **giữ lại D4** (lazy loading) vì nó sẽ cắn anh ở `UserDetailsService`.

---

## Checklist tự đánh giá

### Data Layer
- [ ] Owning side khác inverse side thế nào, `mappedBy` sinh SQL gì?
- [ ] Default fetch type của 4 loại association?
- [ ] Tại sao lazy `@OneToOne` phía inverse không hoạt động?
- [ ] `CascadeType.REMOVE` khác `orphanRemoval` chỗ nào?
- [ ] Bốn cách chống N+1 và giới hạn từng cách?
- [ ] `HHH000104` là gì, sửa bằng pattern nào?
- [ ] Thứ tự cột trong composite index quyết định theo nguyên tắc nào?
- [ ] Tại sao `OFFSET 500000` chậm, thay bằng gì, đánh đổi gì?
- [ ] `READ COMMITTED` ngăn được anomaly nào, không ngăn được anomaly nào?
- [ ] Ba cách chống lost update và trade-off?
- [ ] Deadlock sinh từ đâu, mitigation chuẩn?
- [ ] Bốn bẫy `@Transactional`?
- [ ] Tại sao check-then-act trong Java không an toàn?
- [ ] Làm sao publish event chỉ sau khi commit?

### Security
- [ ] `SecurityFilterChain` xử lý request theo thứ tự nào?
- [ ] `hasRole()` khác `hasAuthority()` chỗ nào?
- [ ] 401 vs 403 — ai ném, ai bắt?
- [ ] Tại sao JWT cần stateless, session ảnh hưởng gì tới scale?
- [ ] Refresh token revoke thế nào, lưu ở đâu, đánh đổi gì?
- [ ] Khi nào tắt CSRF là an toàn, khi nào không?
- [ ] URL-level vs method-level authorization — chọn cái nào khi nào?
- [ ] Ownership check đặt ở tầng nào để an toàn dưới concurrency?

---

## Tài liệu tham khảo

| Chủ đề | Nguồn |
| :--- | :--- |
| Mapping, N+1, fetch strategy | Vlad Mihalcea — *High-Performance Java Persistence* |
| Index, EXPLAIN, pagination | *Use The Index, Luke* |
| Isolation level, anomaly | *Designing Data-Intensive Applications* — chương 7 |
| Locking, MVCC | PostgreSQL docs — Concurrency Control |
| Transaction pitfalls | Spring Framework docs — Transaction Management |
| Spring Security | Spring Security Reference — Architecture, Authorization |
| OAuth2 / OIDC | RFC 6749, OpenID Connect Core |

---

## Cách dùng file này

- Mỗi project một commit riêng, message ghi rõ **bug tái hiện** và **cách sửa**, để sau này đọc diff là nhớ lại được.
- Sau mỗi tuần, tự hỏi: *"Nếu bỏ dòng cấu hình X thì điều gì xảy ra?"* — rồi thử bỏ thật.
- Mỗi project phải kết thúc bằng một **test fail nếu bug quay lại**, không phải một `main()` in ra console.
