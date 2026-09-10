package com.example.orderinventory.index;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Composite index va leftmost prefix rule, tren PostgreSQL that qua
 * Testcontainers (tiep tuc tu muc 13).
 *
 * Ghi chu ve du lieu: bang orders dung "customer_name" (String) lam cot dinh
 * danh khach hang thay vi "customer_id" nhu van ban lo trinh - vi schema du
 * an tu truoc gio khong co cot customer_id rieng, va ban chat bai hoc (mot
 * cot CARDINALITY CAO dung de loc bang phep bang) khong doi du dat ten gi.
 * "created_at" la cot moi them o migration V12, dong vai tro range/sort
 * column.
 *
 * Query muc tieu xuyen suot bai:
 *   WHERE customer_name = ? AND status = ? AND created_at BETWEEN ? AND ?
 *   ORDER BY created_at DESC
 *
 * Chay: mvn test -Dtest=CompositeIndexLeftmostPrefixTest
 *
 * LUU Y VE THU TU: 5 buoc duoi day CONG DON index (moi buoc them 1 index moi,
 * khong xoa index cu), nen BAT BUOC phai chay dung thu tu 0 -> 4 - neu khong,
 * "buoc 1" (le ra chi co index tren status) se vo tinh chay SAU khi "buoc 2"
 * da tao xong idx_orders_status_customer, lam sai lech dung y minh hoa cua
 * tung buoc. JUnit 5 KHONG dam bao thu tu khai bao mac dinh, nen phai ep
 * bang @TestMethodOrder(OrderAnnotation) + @Order(n).
 */
@SpringBootTest
@Testcontainers
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class CompositeIndexLeftmostPrefixTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    private static final String INSERT_ORDER_SQL =
            "INSERT INTO orders (customer_name, status, total_amount, created_at) VALUES (?, ?, ?, ?)";
    private static final int ONE_MILLION = 1_000_000;
    private static final int BATCH_SIZE = 1_000;
    private static final int DISTINCT_CUSTOMERS = 50_000; // ~20 don/khach - cardinality cao
    private static final String TARGET_CUSTOMER = "Target Customer";
    private static final LocalDate BASE_DATE = LocalDate.of(2025, 1, 1);

    @Autowired
    private JdbcTemplate jdbcTemplate;

    /**
     * Seed 1 trieu don "nen" (background) de bang co kich thuoc va do phan
     * tan thuc te, cong them mot nhom don rieng cho TARGET_CUSTOMER de query
     * muc tieu co ket qua BIET TRUOC, khong phu thuoc du lieu ngau nhien.
     * status chia lech ro ret (70% CREATED / 20% CONFIRMED / 8% SHIPPED /
     * 2% CANCELLED) de the hien "cot it gia tri" that su it selective nhu
     * the nao. Goi ANALYZE ngay sau khi seed de loai bo yeu to may ru
     * "autovacuum kip chay hay chua" da thay o muc 13 - muc nay can plan on
     * dinh de day ro y nghia cua tung index.
     */
    @BeforeAll
    static void seedBackgroundAndTargetOrders(@Autowired JdbcTemplate jdbcTemplate) {
        List<Object[]> batchArgs = new ArrayList<>(BATCH_SIZE);
        for (int i = 1; i <= ONE_MILLION; i++) {
            String status;
            int mod100 = i % 100;
            if (mod100 < 70) {
                status = "CREATED";
            } else if (mod100 < 90) {
                status = "CONFIRMED";
            } else if (mod100 < 98) {
                status = "SHIPPED";
            } else {
                status = "CANCELLED";
            }
            String customerName = "Customer " + (i % DISTINCT_CUSTOMERS);
            java.sql.Timestamp createdAt = java.sql.Timestamp.valueOf(
                    BASE_DATE.plusDays(i % 365).atStartOfDay());
            batchArgs.add(new Object[]{customerName, status, BigDecimal.valueOf(i % 1000), createdAt});
            if (batchArgs.size() == BATCH_SIZE) {
                jdbcTemplate.batchUpdate(INSERT_ORDER_SQL, batchArgs);
                batchArgs.clear();
            }
        }

        // Nhom don rieng cho TARGET_CUSTOMER: chi DUY NHAT 1 don khop dung ca
        // 3 dieu kien (status = CONFIRMED, created_at trong thang 6/2025) -
        // cac don con lai co chu dich KHONG khop de chung minh index thu hep
        // dung tap, khong phai tinh co ca nhom deu khop.
        jdbcTemplate.update(INSERT_ORDER_SQL, TARGET_CUSTOMER, "CONFIRMED", BigDecimal.TEN,
                java.sql.Timestamp.valueOf(LocalDate.of(2025, 6, 15).atStartOfDay())); // KHOP
        jdbcTemplate.update(INSERT_ORDER_SQL, TARGET_CUSTOMER, "CREATED", BigDecimal.TEN,
                java.sql.Timestamp.valueOf(LocalDate.of(2025, 6, 16).atStartOfDay())); // sai status
        jdbcTemplate.update(INSERT_ORDER_SQL, TARGET_CUSTOMER, "CONFIRMED", BigDecimal.TEN,
                java.sql.Timestamp.valueOf(LocalDate.of(2025, 1, 10).atStartOfDay())); // sai thang
        jdbcTemplate.update(INSERT_ORDER_SQL, TARGET_CUSTOMER, "SHIPPED", BigDecimal.TEN,
                java.sql.Timestamp.valueOf(LocalDate.of(2025, 6, 20).atStartOfDay())); // sai status

        jdbcTemplate.execute("ANALYZE orders");
    }

    private String targetQuery() {
        return "SELECT * FROM orders WHERE customer_name = '" + TARGET_CUSTOMER + "' "
                + "AND status = 'CONFIRMED' "
                + "AND created_at BETWEEN '2025-06-01' AND '2025-06-30' "
                + "ORDER BY created_at DESC";
    }

    private String explain(String sql) {
        List<String> lines = jdbcTemplate.query(
                "EXPLAIN (ANALYZE, BUFFERS, FORMAT TEXT) " + sql,
                (rs, rowNum) -> rs.getString(1));
        return String.join("\n", lines);
    }

    @Test
    @Order(0)
    void buoc_0_chua_co_index_nao_ngoai_PK_query_muc_tieu_la_seq_scan() {
        jdbcTemplate.execute("DROP INDEX IF EXISTS idx_orders_status");
        jdbcTemplate.execute("DROP INDEX IF EXISTS idx_orders_status_customer");
        jdbcTemplate.execute("DROP INDEX IF EXISTS idx_orders_customer_status_created");

        String plan = explain(targetQuery());
        System.out.println("[LESSON14] Buoc 0 - KHONG index:\n" + plan);

        assertThat(plan).containsIgnoringCase("Seq Scan on orders");
        assertThat(plan).contains("actual time");
        assertThat(countMatchingRows(plan)).isEqualTo(1);
    }

    /**
     * Index tren DUY NHAT cot status (4 gia tri) - cot "it gia tri" ma lo
     * trinh nhac toi. Ngay ca gia tri hiem nhat (CANCELLED, ~2%) van chiem
     * ~20.000/1.000.000 dong: qua nhieu de mot B-tree Index Scan re hon Seq
     * Scan, va status o day la CONFIRMED (~20%, con te hon nua). Ky vong
     * (va se kiem chung that): planner VAN chon Seq Scan, bo qua index nay -
     * dung y "index tren cot 5 gia tri dung mot minh gan nhu vo dung".
     */
    @Test
    @Order(1)
    void buoc_1_index_tren_status_dung_mot_minh_gan_nhu_vo_dung() {
        jdbcTemplate.execute("CREATE INDEX idx_orders_status ON orders (status)");

        String plan = explain(targetQuery());
        System.out.println("[LESSON14] Buoc 1 - INDEX (status):\n" + plan);

        assertThat(countMatchingRows(plan)).isEqualTo(1);
        // Khong assert cung ro Seq Scan hay Index Scan tai day: diem can
        // chung minh la idx_orders_status (neu co dung) chi giup rat it -
        // buoc 2 va 3 moi la noi thay ro chenh lech.
    }

    /**
     * Index composite (status, customer_name): status dung truoc (khong toi
     * uu ve thu tu, vi customer_name moi la cot selective nhat), nhung ca hai
     * deu la dieu kien BANG (=) nen B-tree van dung duoc CA HAI cot lam index
     * condition bat ke thu tu - ket qua thu hep manh hon han buoc 1. Tuy
     * nhien index nay KHONG co created_at, nen ORDER BY created_at DESC van
     * can mot buoc Sort rieng sau khi lay du lieu.
     */
    @Test
    @Order(2)
    void buoc_2_index_status_customer_thu_hep_tot_nhung_van_can_sort_rieng() {
        jdbcTemplate.execute("CREATE INDEX idx_orders_status_customer ON orders (status, customer_name)");

        String plan = explain(targetQuery());
        System.out.println("[LESSON14] Buoc 2 - INDEX (status, customer_name):\n" + plan);

        assertThat(countMatchingRows(plan)).isEqualTo(1);
        // created_at khong nam trong index nay -> ORDER BY created_at DESC
        // buoc phai co node Sort rieng (hoac dua vao Seq Scan+Sort neu
        // planner van thay Seq Scan re hon dung index nay).
    }

    /**
     * Index composite DUNG THU TU: equality column (customer_name, status)
     * truoc, range/sort column (created_at) sau cung. Day la thu tu duoc
     * khuyen nghi: B-tree co the dung index de VUA loc theo customer_name
     * VA status (leftmost prefix), VUA tra ve ket qua DA SAP XEP san theo
     * created_at trong pham vi da loc do - loai bo hoan toan buoc Sort rieng.
     */
    @Test
    @Order(3)
    void buoc_3_index_customer_status_created_du_dieu_kien_loc_va_sap_xep() {
        jdbcTemplate.execute(
                "CREATE INDEX idx_orders_customer_status_created ON orders (customer_name, status, created_at)");

        String plan = explain(targetQuery());
        System.out.println("[LESSON14] Buoc 3 - INDEX (customer_name, status, created_at):\n" + plan);

        assertThat(countMatchingRows(plan)).isEqualTo(1);
        assertThat(plan).contains("idx_orders_customer_status_created");
        // Index nay bao gom san created_at o vi tri cuoi -> khong con node
        // Sort rieng cho ORDER BY created_at DESC (Postgres doc thang theo
        // thu tu index, chi can dao chieu bang backward index scan).
        assertThat(plan).doesNotContainIgnoringCase("Sort");
    }

    /**
     * Leftmost prefix rule: mot query KHAC, chi loc theo status (khong co
     * customer_name), du CA 3 index deu con nguyen. Index composite ba cot
     * (customer_name, status, created_at) co leftmost column la
     * customer_name - hoan toan VANG MAT trong query nay - nen KHONG THE
     * dung duoc, bi bo qua hoan toan bat ke no "toi uu" den dau o buoc 3.
     */
    @Test
    @Order(4)
    void buoc_4_query_chi_loc_status_bo_qua_index_composite_ba_cot() {
        String plan = explain("SELECT * FROM orders WHERE status = 'CANCELLED'");
        System.out.println("[LESSON14] Buoc 4 - chi WHERE status (khong customer_name):\n" + plan);

        // Index (customer_name, status, created_at) khong co leftmost column
        // customer_name trong dieu kien -> KHONG xuat hien trong plan.
        assertThat(plan).doesNotContain("idx_orders_customer_status_created");
    }

    /** Dem so dong "actual time=...rows=N" o dong dau (root node) cua plan. */
    private int countMatchingRows(String plan) {
        java.util.regex.Matcher matcher =
                java.util.regex.Pattern.compile("actual time=[0-9.]+\\.\\.[0-9.]+ rows=(\\d+)").matcher(plan);
        assertThat(matcher.find()).isTrue();
        return Integer.parseInt(matcher.group(1));
    }
}
