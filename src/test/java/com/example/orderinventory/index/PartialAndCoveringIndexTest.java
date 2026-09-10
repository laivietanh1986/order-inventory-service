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
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Partial index va covering index, tiep tuc tren PostgreSQL that qua
 * Testcontainers (muc 13, 14).
 *
 * Boi canh: bang orders 1 trieu dong, 95% la "COMPLETED", chi 5% la
 * "CONFIRMED" - workload LECH nang. Index day du tren status phai chua ca
 * 1 trieu dong (kem theo 95% vo dung vi COMPLETED qua pho bien de index
 * giup ich), trong khi mot PARTIAL index chi index rieng nhom CONFIRMED
 * hiem se nho hon rat nhieu va van du dung cho cac query loc CONFIRMED.
 *
 * LUU Y THU TU: giong muc 14, cac buoc CONG DON trang thai index/VACUUM, nen
 * bat buoc @TestMethodOrder + @Order de dam bao chay dung 0 -> 4.
 *
 * Chay: mvn test -Dtest=PartialAndCoveringIndexTest
 */
@SpringBootTest
@Testcontainers
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class PartialAndCoveringIndexTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    private static final String INSERT_ORDER_SQL =
            "INSERT INTO orders (customer_name, status, total_amount, created_at) VALUES (?, ?, ?, ?)";
    private static final int ONE_MILLION = 1_000_000;
    private static final int BATCH_SIZE = 1_000;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    /**
     * 95% COMPLETED / 5% CONFIRMED - dung "i % 20 == 0" de chinh xac 1/20
     * (5%) la CONFIRMED, phan con lai la COMPLETED.
     */
    @BeforeAll
    static void seedOneMillionOrdersMostlyCompleted(@Autowired JdbcTemplate jdbcTemplate) {
        Timestamp now = Timestamp.valueOf(LocalDateTime.now());
        List<Object[]> batchArgs = new ArrayList<>(BATCH_SIZE);
        for (int i = 1; i <= ONE_MILLION; i++) {
            String status = (i % 20 == 0) ? "CONFIRMED" : "COMPLETED";
            batchArgs.add(new Object[]{"Customer " + (i % 10_000), status, BigDecimal.valueOf(i % 1000), now});
            if (batchArgs.size() == BATCH_SIZE) {
                jdbcTemplate.batchUpdate(INSERT_ORDER_SQL, batchArgs);
                batchArgs.clear();
            }
        }
        jdbcTemplate.execute("ANALYZE orders");
    }

    private String explain(String sql) {
        List<String> lines = jdbcTemplate.query(
                "EXPLAIN (ANALYZE, BUFFERS, FORMAT TEXT) " + sql,
                (rs, rowNum) -> rs.getString(1));
        return String.join("\n", lines);
    }

    private long indexSizeBytes(String indexName) {
        return jdbcTemplate.queryForObject("SELECT pg_relation_size(?)", Long.class, indexName);
    }

    private String indexSizePretty(String indexName) {
        return jdbcTemplate.queryForObject("SELECT pg_size_pretty(pg_relation_size(?))", String.class, indexName);
    }

    @Test
    @Order(0)
    void buoc_0_index_day_du_tren_status_phai_chua_ca_1_trieu_dong() {
        jdbcTemplate.execute("CREATE INDEX idx_orders_status_full ON orders (status)");

        long sizeBytes = indexSizeBytes("idx_orders_status_full");
        System.out.println("[LESSON15] idx_orders_status_full: " + indexSizePretty("idx_orders_status_full")
                + " (" + sizeBytes + " bytes)");

        assertThat(sizeBytes).isGreaterThan(0);
    }

    /**
     * CREATE INDEX ... WHERE status = 'CONFIRMED': chi index 5% so dong. So
     * sanh dung luong bang pg_relation_size (tuong duong doc dung luong tu
     * \di+ trong psql, nhung lay qua SQL de assert duoc trong test).
     */
    @Test
    @Order(1)
    void buoc_1_partial_index_chi_5_phan_tram_nho_hon_han_index_day_du() {
        jdbcTemplate.execute(
                "CREATE INDEX idx_orders_status_confirmed_partial ON orders (status) WHERE status = 'CONFIRMED'");

        long fullSize = indexSizeBytes("idx_orders_status_full");
        long partialSize = indexSizeBytes("idx_orders_status_confirmed_partial");
        System.out.println("[LESSON15] full=" + indexSizePretty("idx_orders_status_full")
                + ", partial(chi CONFIRMED)=" + indexSizePretty("idx_orders_status_confirmed_partial"));

        // Partial index chi chua ~5% so dong -> phai nho hon han index day du
        // (khong assert dung ty le 1/20 de tranh flaky vi chi phi B-tree
        // overhead khong hoan toan tuyen tinh voi so dong).
        assertThat(partialSize).isLessThan(fullSize / 5);

        String plan = explain("SELECT * FROM orders WHERE status = 'CONFIRMED'");
        System.out.println("[LESSON15] Buoc 1 - WHERE status = 'CONFIRMED':\n" + plan);
        assertThat(plan).contains("idx_orders_status_confirmed_partial");
    }

    /**
     * Partial index chi chua dong CONFIRMED - dieu kien cua predicate index
     * (WHERE status = 'CONFIRMED') KHONG duoc suy ra tu dieu kien cua query
     * nay (status = 'COMPLETED'), nen Postgres KHONG THE dung no, du no dung
     * ten cot y het index day du.
     */
    @Test
    @Order(2)
    void buoc_2_query_loc_gia_tri_khac_khong_the_dung_partial_index() {
        String plan = explain("SELECT * FROM orders WHERE status = 'COMPLETED'");
        System.out.println("[LESSON15] Buoc 2 - WHERE status = 'COMPLETED':\n" + plan);

        assertThat(plan).doesNotContain("idx_orders_status_confirmed_partial");
    }

    /**
     * Them INCLUDE (total_amount): cot nay khong dung de TIM (khong nam
     * trong B-tree key), chi duoc "co ke theo" o la cua index de tra thang
     * ve ma khong can quay lai heap - dieu kien de co Index Only Scan cho
     * query CHI can status + total_amount. Nhung ngay sau bulk insert,
     * visibility map CHUA duoc danh dau all-visible, nen du chon duoc node
     * "Index Only Scan", Postgres van co the phai "Heap Fetches" > 0 de tu
     * kiem tra MVCC visibility truc tiep tren heap cho tung dong.
     *
     * XOA 2 index cu (full va partial khong-covering) TRUOC KHI tao index
     * moi: da do xong dung luong/cach dung cua chung o buoc 0-2 roi, va neu
     * de chung ton tai song song, chi phi UOC LUONG (chua biet visibility
     * map) cua Index Only Scan qua covering index doi khi GAN BANG chi phi
     * mot Index Scan thuong qua idx_orders_status_confirmed_partial - lam
     * planner chon nham index khac nhau giua cac lan chay (da gap that khi
     * chay lai nhieu lan). Xoa bot lua chon giup buoc 3-4 tap trung dung vao
     * chu de cua no (VACUUM/visibility map), khong bi nhieu boi mot quyet
     * dinh chi phi khac dang duoc kiem chung song song.
     */
    @Test
    @Order(3)
    void buoc_3_covering_index_truoc_VACUUM_van_con_heap_fetches() {
        jdbcTemplate.execute("DROP INDEX idx_orders_status_full");
        jdbcTemplate.execute("DROP INDEX idx_orders_status_confirmed_partial");
        jdbcTemplate.execute("CREATE INDEX idx_orders_status_confirmed_covering "
                + "ON orders (status) INCLUDE (total_amount) WHERE status = 'CONFIRMED'");

        String plan = explain("SELECT status, total_amount FROM orders WHERE status = 'CONFIRMED'");
        System.out.println("[LESSON15] Buoc 3 - TRUOC VACUUM:\n" + plan);

        assertThat(plan).contains("idx_orders_status_confirmed_covering");
        assertThat(plan).containsIgnoringCase("Index Only Scan");
        assertThat(plan).contains("Heap Fetches:");
    }

    /**
     * VACUUM cap nhat visibility map, danh dau cac page chi chua dong "all
     * visible" (khong con giao dich nao co the thay phien ban cu hon). Sau
     * do, cung query nhu buoc 3 khong con can Heap Fetches nao nua -
     * Index Only Scan THAT SU, khong cham vao heap.
     */
    @Test
    @Order(4)
    void buoc_4_sau_VACUUM_index_only_scan_khong_con_heap_fetches() {
        jdbcTemplate.execute("VACUUM orders");

        String plan = explain("SELECT status, total_amount FROM orders WHERE status = 'CONFIRMED'");
        System.out.println("[LESSON15] Buoc 4 - SAU VACUUM:\n" + plan);

        assertThat(plan).containsIgnoringCase("Index Only Scan");
        assertThat(plan).contains("Heap Fetches: 0");
    }
}
