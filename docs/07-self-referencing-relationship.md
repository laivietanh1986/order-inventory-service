# 7. Quan hệ tự tham chiếu

Tương ứng mục 7 trong [`Lo_trinh_hoc_JPA.md`](../Lo_trinh_hoc_JPA.md).

## Mục tiêu

`Category` có `parentCategory`, viết endpoint trả về một category kèm toàn bộ subtree.

So sánh 2 cách: đệ quy ở tầng Java (N query) và `WITH RECURSIVE` một query.

Khái niệm cần nắm: adjacency list, recursive CTE, so sánh với materialized path và nested set.

## Các file đã tạo

| File | Vai trò |
| :--- | :--- |
| [`Category.java`](../src/main/java/com/example/orderinventory/category/Category.java) | Entity tự tham chiếu (`parentCategory`, adjacency list) |
| [`CategoryRepository.java`](../src/main/java/com/example/orderinventory/category/CategoryRepository.java) | `findByParentCategoryId` |
| [`CategoryTreeDto.java`](../src/main/java/com/example/orderinventory/category/CategoryTreeDto.java) | Cây kết quả trả về client |
| [`CategoryTreeService.java`](../src/main/java/com/example/orderinventory/category/CategoryTreeService.java) | 2 cách lấy subtree: đệ quy Java và `WITH RECURSIVE` |
| [`CategoryController.java`](../src/main/java/com/example/orderinventory/category/CategoryController.java) | `GET /api/categories/{id}/subtree?strategy=recursive\|cte` |
| [`V7__create_categories_table.sql`](../src/main/resources/db/migration/V7__create_categories_table.sql) | Bảng `categories`, tự tham chiếu qua `parent_id` |
| [`CategoryTreeServiceTest.java`](../src/test/java/com/example/orderinventory/category/CategoryTreeServiceTest.java) | So sánh số query của 2 cách |
| [`CategoryControllerTest.java`](../src/test/java/com/example/orderinventory/category/CategoryControllerTest.java) | Kiểm tra endpoint (web layer) |

## Cách chạy

```bash
./mvnw test -Dtest=CategoryTreeServiceTest,CategoryControllerTest
```

Cây dữ liệu dùng cho test (6 node):

```
Electronics (root)
├── Phones
│   ├── Android
│   └── iOS
└── Laptops
    └── Gaming Laptops
```

## Adjacency list — mô hình đơn giản nhất

```java
@ManyToOne(fetch = FetchType.LAZY)
@JoinColumn(name = "parent_id")
private Category parentCategory;
```

Mỗi row chỉ biết **parent trực tiếp** của nó qua cột `parent_id` (NULL nếu là root). Đây là mô hình cây phổ biến nhất vì đơn giản, dễ hiểu, và **ghi/sửa cực rẻ**: thêm một node mới, hoặc di chuyển một subtree sang parent khác, chỉ là `UPDATE` đúng một cột trên đúng một row. Cái giá phải trả là **đọc cả subtree không hề rẻ** — vì mỗi row chỉ biết cha ngay trên nó, DB không có cách nào trả lời "cho tôi toàn bộ con cháu của node X" chỉ bằng một điều kiện `WHERE` đơn giản.

## Cách 1 — đệ quy ở tầng Java (1 + N query)

```java
public CategoryTreeDto getSubtreeRecursive(Long rootId) {
    Category root = categoryRepository.findById(rootId).orElseThrow();
    return buildRecursive(root);
}

private CategoryTreeDto buildRecursive(Category category) {
    CategoryTreeDto dto = new CategoryTreeDto(category.getId(), category.getName());
    List<Category> children = categoryRepository.findByParentCategoryId(category.getId()); // 1 query/node
    for (Category child : children) {
        dto.addChild(buildRecursive(child));
    }
    return dto;
}
```

```java
statistics().clear();
CategoryTreeDto tree = categoryTreeService.getSubtreeRecursive(rootId);

assertThat(statistics().getPrepareStatementCount()).isEqualTo(7); // 1 (root) + 6 (moi node)
```

Với cây 6 node: 1 câu `findById` cho root, cộng thêm **đúng 1 câu `SELECT` cho mỗi node** (kể cả 3 node lá — chúng vẫn cần một query để biết chắc "không có con nào") = 7 statement. Số query này **tỉ lệ thuận với số node trong subtree**, không phụ thuộc độ sâu hay độ rộng cụ thể — cây càng lớn, cái giá phải trả ở lớp ứng dụng càng tăng tuyến tính.

## Cách 2 — `WITH RECURSIVE` (recursive CTE), một query duy nhất

```sql
WITH RECURSIVE subtree (id, name, parent_id, depth) AS (
    SELECT id, name, parent_id, 0 AS depth
    FROM categories
    WHERE id = :rootId
    UNION ALL
    SELECT c.id, c.name, c.parent_id, s.depth + 1
    FROM categories c
    JOIN subtree s ON c.parent_id = s.id
)
SELECT id, name, parent_id, depth FROM subtree ORDER BY depth
```

```java
statistics().clear();
CategoryTreeDto tree = categoryTreeService.getSubtreeWithRecursiveCte(rootId);

assertThat(statistics().getPrepareStatementCount()).isEqualTo(1);
```

Một CTE đệ quy có hai phần nối bằng `UNION ALL`:
- **Anchor member** (`SELECT ... WHERE id = :rootId`): điểm khởi đầu, chỉ chạy một lần.
- **Recursive member** (`SELECT ... JOIN subtree s ON c.parent_id = s.id`): tự tham chiếu tới chính CTE (`subtree`) đang được xây dựng, được thực thi lặp lại — mỗi vòng nối thêm một tầng con — cho đến khi không còn row nào khớp điều kiện `JOIN` (tức là đã chạm tới các node lá).

Toàn bộ vòng lặp này chạy **bên trong engine của database**, ứng dụng chỉ gửi đi một câu SQL và nhận về một danh sách phẳng `(id, name, parent_id, depth)` đã bao gồm mọi tầng — dựng lại thành cây trong bộ nhớ (dùng `ORDER BY depth` để đảm bảo cha luôn xuất hiện trước con, nên khi build map chỉ cần duyệt một lượt).

> Lưu ý cú pháp: H2 yêu cầu khai báo tường minh danh sách cột của CTE — `subtree (id, name, parent_id, depth) AS (...)` — thay vì suy luận tên cột từ vế `SELECT` đầu (khác với PostgreSQL, vốn cho phép bỏ qua phần này). Khai báo tường minh vừa an toàn hơn vừa chạy được trên cả hai.

## So sánh

| Tiêu chí | Đệ quy tầng Java | `WITH RECURSIVE` |
| :--- | :---: | :---: |
| Số round-trip DB | 1 + N (N = số node) | 1 |
| Độ phức tạp code ứng dụng | thấp (dễ đọc) | trung bình (SQL đặc thù DB) |
| Phụ thuộc cú pháp DB cụ thể | không | có (cần kiểm tra tương thích H2/Postgres/MySQL) |
| Khả năng debug bằng breakpoint Java | dễ | khó hơn (logic nằm trong SQL) |

Với cây nhỏ (vài chục node), khác biệt không đáng kể. Với cây có hàng nghìn category hoặc độ sâu lớn, N+1 round-trip từ cách 1 nhanh chóng trở thành nút thắt cổ chai I/O — đây chính xác là bài toán N+1 sẽ gặp lại ở mục 8, chỉ khác là lần này N+1 nằm trong một vòng đệ quy thay vì một vòng lặp phẳng.

## So sánh với hai mô hình khác (không cài đặt trong bài này)

Adjacency list không phải cách duy nhất để lưu cây quan hệ. Hai lựa chọn thay thế phổ biến:

- **Materialized path**: mỗi row lưu thêm một cột dạng chuỗi biểu diễn toàn bộ đường đi từ root, ví dụ `path = "/1/3/7/"`. Đọc subtree chỉ cần một điều kiện `WHERE path LIKE '/1/3/%'` — cực nhanh, không cần CTE. Đổi lại: di chuyển một subtree sang vị trí khác trong cây đòi hỏi `UPDATE` lại `path` của **toàn bộ node con cháu**, và cột `path` dễ bị giới hạn độ dài nếu cây quá sâu.
- **Nested set** (`lft`/`rgt`): mỗi node lưu một cặp số nguyên sao cho "là con cháu của node X" tương đương với điều kiện `lft BETWEEN X.lft AND X.rgt`. Đọc subtree cực nhanh (một `BETWEEN`, có index tốt), nhưng **ghi cực đắt**: thêm hoặc xoá một node bất kỳ đòi hỏi cập nhật lại `lft`/`rgt` của phần lớn các node còn lại trong cây.

Ba mô hình đại diện cho ba điểm khác nhau trên cùng một trục đánh đổi **đọc rẻ ↔ ghi rẻ**:

| Mô hình | Đọc subtree | Ghi (thêm/di chuyển node) |
| :--- | :---: | :---: |
| Adjacency list | đắt (N query hoặc cần CTE) | rẻ (1 `UPDATE`) |
| Materialized path | rẻ (`LIKE`) | trung bình/đắt (update path của con cháu) |
| Nested set | rất rẻ (`BETWEEN`) | rất đắt (update phần lớn cây) |

Adjacency list + recursive CTE là lựa chọn cân bằng phù hợp cho phần lớn ứng dụng (cây thay đổi cấu trúc thường xuyên, không cần đọc subtree với tần suất cực cao) — đó là lý do nó được chọn làm mô hình chính trong bài này, thay vì materialized path hay nested set vốn chỉ đáng đánh đổi khi đọc subtree là thao tác nóng (hot path) và cấu trúc cây gần như tĩnh.

## Endpoint

```
GET /api/categories/{id}/subtree              -- mac dinh dung WITH RECURSIVE
GET /api/categories/{id}/subtree?strategy=recursive  -- dung de quy tang Java
```

> Lưu ý: project đã có `spring-boot-starter-security` trên classpath nhưng chưa cấu hình `SecurityFilterChain` nào, nên mặc định **mọi endpoint đều yêu cầu đăng nhập** (Spring Security tự sinh một user `user` với mật khẩu in ra console lúc khởi động). Muốn gọi thử endpoint này bằng `curl` cần đăng nhập bằng thông tin đó, hoặc cấu hình security (ngoài phạm vi bài này). [`CategoryControllerTest`](../src/test/java/com/example/orderinventory/category/CategoryControllerTest.java) loại bỏ `SecurityAutoConfiguration` trong slice test để kiểm tra riêng logic controller.

## Kết quả chạy

```
Tests run: 3, Failures: 0, Errors: 0, Skipped: 0   -- CategoryTreeServiceTest
Tests run: 2, Failures: 0, Errors: 0, Skipped: 0   -- CategoryControllerTest
BUILD SUCCESS
```
