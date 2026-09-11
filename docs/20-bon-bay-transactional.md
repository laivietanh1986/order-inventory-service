# Mục 20 — Bốn bẫy `@Transactional`

Tương ứng mục 20 trong [`Lo_trinh_hoc_JPA.md`](../Lo_trinh_hoc_JPA.md).

## Mục tiêu

Bốn cạm bẫy kinh điển của `@Transactional` trong Spring, mỗi cái minh hoạ
một hệ quả trực tiếp của việc `@Transactional` là **proxy-based AOP** —
annotation không "gắn" vào method, nó chỉ có hiệu lực khi lời gọi đi **qua
proxy** mà Spring bọc quanh bean:

1. **Self-invocation** — gọi method `@Transactional` từ method khác cùng
   class, chứng minh bằng `TransactionSynchronizationManager.isActualTransactionActive()`.
2. **Checked exception** — `throw new Exception(...)` nhưng dữ liệu vẫn
   commit.
3. **`REQUIRES_NEW`** — audit log sống sót dù transaction chính rollback, so
   sánh với `NESTED` (savepoint).
4. **Side effect trước commit** — `ApplicationEventPublisher` thường so với
   `@TransactionalEventListener(phase = AFTER_COMMIT)`, chứng minh consumer
   không thấy dữ liệu ở cách đầu.

> ⚠️ Không dùng `@Transactional` trên class/method test — lý do giống hệt
> mục 17: nếu có, Spring gói cả test vào một transaction bao trùm luôn code
> đang được test, làm sai lệch chính hành vi commit/rollback cần quan sát
> (mọi thứ sẽ tự rollback ở cuối test bất kể logic bên trong đúng hay sai).

## File đã tạo

| File | Vai trò |
|---|---|
| [`TxDemoRecord.java`](../src/main/java/com/example/orderinventory/transactional/TxDemoRecord.java) / [`TxDemoRecordRepository.java`](../src/main/java/com/example/orderinventory/transactional/TxDemoRecordRepository.java) | Entity/repository dùng chung cho cả 4 bẫy |
| [`TxDemoAuditLog.java`](../src/main/java/com/example/orderinventory/transactional/TxDemoAuditLog.java) / [`TxDemoAuditLogRepository.java`](../src/main/java/com/example/orderinventory/transactional/TxDemoAuditLogRepository.java) | Bảng audit log riêng cho bẫy 3 |
| [`V14__create_tx_demo_tables.sql`](../src/main/resources/db/migration/V14__create_tx_demo_tables.sql) | Migration cho 2 bảng trên |
| [`SelfInvocationService.java`](../src/main/java/com/example/orderinventory/transactional/SelfInvocationService.java) | Bẫy 1 |
| [`CheckedExceptionService.java`](../src/main/java/com/example/orderinventory/transactional/CheckedExceptionService.java) | Bẫy 2 |
| [`AuditLogService.java`](../src/main/java/com/example/orderinventory/transactional/AuditLogService.java), [`PropagationDemoService.java`](../src/main/java/com/example/orderinventory/transactional/PropagationDemoService.java) | Bẫy 3 |
| [`TxDemoRecordCreatedEvent.java`](../src/main/java/com/example/orderinventory/transactional/TxDemoRecordCreatedEvent.java), [`EventPublishingService.java`](../src/main/java/com/example/orderinventory/transactional/EventPublishingService.java), [`TxDemoRecordEventListener.java`](../src/main/java/com/example/orderinventory/transactional/TxDemoRecordEventListener.java), [`EventVisibilityRecorder.java`](../src/main/java/com/example/orderinventory/transactional/EventVisibilityRecorder.java) | Bẫy 4 |
| [`TransactionalPitfallsTest.java`](../src/test/java/com/example/orderinventory/transactional/TransactionalPitfallsTest.java) | Toàn bộ thực nghiệm mục 20, trên PostgreSQL thật (Testcontainers) |

Chạy: `mvn test -Dtest=TransactionalPitfallsTest`

## Bẫy 1 — Self-invocation

```java
// KHONG @Transactional
public boolean callInnerViaSelfInvocation() {
    return callInnerTransactional();   // "this.callInnerTransactional()" ngam dinh
}

@Transactional
public boolean callInnerTransactional() {
    return TransactionSynchronizationManager.isActualTransactionActive();
}
```

```
[LESSON20-SELF-INVOCATION] qua self-invocation: transaction active = false
[LESSON20-SELF-INVOCATION] qua proxy truc tiep: transaction active = true
```

Spring tạo `@Transactional` bằng cách bọc bean gốc trong một **proxy** (JDK
dynamic proxy hoặc CGLIB) — logic mở/commit/rollback transaction nằm ở
**proxy**, không nằm trong chính method. Khi `callInnerViaSelfInvocation()`
gọi `callInnerTransactional()` bằng `this.` (dù viết tường minh hay ngầm
định), đó là một lời gọi Java thuần tuý trên đối tượng gốc — **hoàn toàn
không đi qua proxy** — nên annotation `@Transactional` bị bỏ qua trong im
lặng, không exception, không cảnh báo. Gọi đúng method đó từ **bên ngoài**
(test gọi thẳng `service.callInnerTransactional()`, đi qua proxy do Spring
tiêm vào) thì transaction được mở bình thường.

Đây là lý do các bẫy 3 và 4 trong project này đặt logic phụ (audit log,
event listener) ở **service khác** thay vì gọi nội bộ cùng class — không
phải ngẫu nhiên, mà là cách né bẫy 1 một cách tự nhiên.

## Bẫy 2 — Checked exception không kích hoạt rollback mặc định

```java
@Transactional
public void saveThenThrowCheckedException(String label) throws Exception {
    recordRepository.save(new TxDemoRecord(label));
    throw new Exception("simulated checked failure");
}
```

```
[LESSON20-CHECKED-EXCEPTION] so dong con lai sau checked exception = 1
```

Quy tắc rollback mặc định của Spring (kế thừa từ EJB CMT): chỉ rollback khi
method ném ra **unchecked exception** (`RuntimeException`/`Error`), **không
rollback** với checked exception (`Exception` thường) — dù exception đó vẫn
được ném ra và propagate lên caller bình thường. Ngược lại hoàn toàn với trực
giác "có exception thì phải rollback".

So sánh trực tiếp trong cùng test:

```java
@Transactional
public void saveThenThrowRuntimeException(String label) {   // KHONG throws — unchecked
    recordRepository.save(new TxDemoRecord(label));
    throw new RuntimeException(...);
}
```

→ dòng bị rollback đúng như kỳ vọng (đếm được 0 dòng).

Fix: khai báo tường minh `rollbackFor`:

```java
@Transactional(rollbackFor = Exception.class)
public void saveThenThrowCheckedExceptionWithRollbackFor(String label) throws Exception { ... }
```

→ checked exception giờ cũng kích hoạt rollback (đếm được 0 dòng).

## Bẫy 3 — `REQUIRES_NEW` sống sót, `NESTED` bị cuốn theo

```java
@Transactional(propagation = Propagation.REQUIRES_NEW)
public void logInNewTransaction(String message) {
    auditLogRepository.save(new TxDemoAuditLog(message));
}

@Transactional(propagation = Propagation.NESTED)
public void logInNestedTransaction(String message) {
    auditLogRepository.save(new TxDemoAuditLog(message));
}
```

```java
@Transactional
public void mainOperationWithRequiresNewAudit(String recordLabel, String auditMessage) {
    recordRepository.save(new TxDemoRecord(recordLabel));
    auditLogService.logInNewTransaction(auditMessage);   // REQUIRES_NEW hoac NESTED
    throw new RuntimeException("gia lap loi nghiep vu SAU KHI da ghi audit");
}
```

```
[LESSON20-PROPAGATION] REQUIRES_NEW: main record con lai=0, audit log con lai=1
[LESSON20-PROPAGATION] NESTED: main record con lai=0, audit log con lai=0
```

Trong cả hai trường hợp, `mainOperationXxxAudit` đều rollback đúng như kỳ
vọng (`main record con lai = 0`) — khác biệt nằm ở audit log:

- **`REQUIRES_NEW`** tạm dừng transaction hiện tại, mở một transaction **vật
  lý hoàn toàn mới** với connection riêng, commit ngay khi method kết thúc —
  độc lập tuyệt đối với số phận của transaction ngoài. Khi transaction chính
  rollback sau đó, audit log **đã commit từ trước rồi**, không bị ảnh hưởng
  → còn lại 1 dòng.
- **`NESTED`** tạo một **savepoint** bên trong CÙNG transaction vật lý (cùng
  connection) với transaction ngoài — không độc lập. Khi transaction ngoài
  rollback TOÀN BỘ (không chỉ rollback về savepoint), mọi thứ sau savepoint
  — kể cả audit log — bị cuốn theo → còn lại 0 dòng.

Ghi chú thực nghiệm: `NESTED` hoạt động đúng ngay lập tức với cấu hình mặc
định của Spring Boot (`JpaTransactionManager` + Hibernate + PostgreSQL,
không cần cấu hình gì thêm) — PostgreSQL hỗ trợ JDBC `Savepoint` đầy đủ nên
Spring có thể dùng ngay. Đây không phải điều hiển nhiên với mọi tổ hợp
persistence provider/database, nên luôn đáng kiểm chứng bằng test thật thay
vì giả định.

## Bẫy 4 — Side effect trước commit: `@EventListener` thường vs `@TransactionalEventListener(AFTER_COMMIT)`

```java
@Transactional
public Long createRecordAndPublishEvent(String label) {
    TxDemoRecord saved = recordRepository.save(new TxDemoRecord(label));
    eventPublisher.publishEvent(new TxDemoRecordCreatedEvent(saved.getId()));
    return saved.getId();
}
```

```java
@EventListener   // chay DONG BO, VAN O TRONG transaction cua createRecordAndPublishEvent, TRUOC khi no commit
public void onCreatedSynchronously(TxDemoRecordCreatedEvent event) { ... }

@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)   // chi chay SAU khi commit
public void onCreatedAfterCommit(TxDemoRecordCreatedEvent event) { ... }
```

```
[LESSON20-EVENT] sync listener (truoc commit) thay row = false
[LESSON20-EVENT] after-commit listener thay row = true
```

Điểm mấu chốt để phép đo này đáng tin: cả hai listener không đọc lại bằng
chính connection đang mở transaction (connection đó luôn thấy dữ liệu của
chính nó dù chưa commit — không chứng minh được gì), mà mở một transaction
`REQUIRES_NEW` **riêng** (`TransactionTemplate` với
`Propagation.REQUIRES_NEW`) để lấy một **connection hoàn toàn khác** rồi mới
kiểm tra `existsById`:

- `@EventListener` thường được Spring gọi **đồng bộ ngay tại điểm
  `publishEvent()`** — tức là vẫn còn nằm bên trong lời gọi
  `createRecordAndPublishEvent()`, transaction ngoài **chưa commit**. Một
  connection khác dưới `READ COMMITTED` không thấy được dòng chưa commit đó
  → `false`.
- `@TransactionalEventListener(phase = AFTER_COMMIT)` được Spring **hoãn
  lại**, đăng ký thông qua `TransactionSynchronizationManager` tại thời điểm
  publish, và chỉ thực sự gọi **sau khi** transaction ngoài commit thành
  công → dữ liệu chắc chắn đã ghi thật, connection khác thấy được ngay →
  `true`.

Đây chính là "chứng minh consumer không thấy dữ liệu ở cách đầu" mà đề bài
yêu cầu — không phải suy luận lý thuyết, mà đo được trực tiếp bằng một
transaction độc lập thứ ba.

## Khái niệm

- **Proxy-based AOP**: mọi hành vi của `@Transactional` (mở, commit,
  rollback) nằm ở một proxy Spring tạo ra quanh bean, không nằm trong bản
  thân method. Bất kỳ lời gọi nào không đi qua proxy đó (self-invocation là
  trường hợp phổ biến nhất) đều khiến annotation vô hiệu — im lặng, không
  cảnh báo lúc biên dịch hay chạy.
- **Rollback rule mặc định**: rollback trên `RuntimeException`/`Error`,
  KHÔNG rollback trên checked exception — trừ khi khai báo `rollbackFor`
  (hoặc `noRollbackFor` để đảo ngược riêng lẻ cho một exception cụ thể).
- **`readOnly` không chỉ là hint**: với Hibernate, `@Transactional(readOnly
  = true)` tắt hẳn dirty checking và bỏ qua việc lưu snapshot ban đầu của
  entity (đã thấy ở mục 12) — không đơn thuần là "gợi ý" cho driver, mà thực
  sự đổi hành vi flush của persistence context, giảm chi phí bộ nhớ và CPU
  đáng kể cho các luồng chỉ đọc.
- **Không gọi HTTP bên ngoài trong transaction**: một transaction giữ một
  connection JDBC suốt vòng đời của nó (từ lúc mở tới lúc commit/rollback).
  Nếu bên trong transaction đó có một lời gọi HTTP ra dịch vụ khác (có thể
  chậm, có thể timeout, có thể treo), connection bị giữ chặt suốt thời gian
  chờ — với một connection pool kích thước cố định, vài request chậm như vậy
  đủ để **cạn kiệt toàn bộ pool**, khiến mọi request khác (kể cả không liên
  quan gì tới dịch vụ ngoài đó) bị nghẽn chờ connection.

## Tổng kết

```
Tests run: 7, Failures: 0, Errors: 0, Skipped: 0 -- TransactionalPitfallsTest
```

`mvn clean test` (toàn bộ project): **81/81 test pass** trên PostgreSQL thật
qua Testcontainers.
