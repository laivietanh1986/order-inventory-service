package com.example.orderinventory.pagination;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.data.Percentage.withPercentage;

/**
 * Deep OFFSET pagination vs keyset (seek) pagination, tiep tuc tren
 * PostgreSQL that qua Testcontainers (muc 13-15).
 *
 * Du lieu duoc seed CO CHU DICH de vi tri trong thu tu phan trang suy ra
 * duoc TRUC TIEP tu cong thuc, khong can truy van do tham do: id la
 * IDENTITY tang dan 1..1_000_000 tren bang trong rong, va created_at =
 * BASE_INSTANT.plusSeconds(i) - tang dan CUNG CHIEU voi id. Vi vay, o thu tu
 * ORDER BY created_at DESC, id DESC, dong o VI TRI P (dem tu 0) chinh la
 * dong co i = 1_000_000 - P. Day KHONG phai "gian lan" so voi thuc te: mot
 * client that su dung keyset pagination cung KHONG BAO GIO tinh vi tri nhu
 * the nay - ho chi luu lai (created_at, id) cua dong CUOI CUNG o trang truoc
 * do da nhan duoc tu server. Cong thuc o day chi la cach de test tu tao ra
 * mot "cursor sau" hop le ma khong can chinh no chay mot truy van OFFSET
 * rieng chi de lay du lieu setup.
 *
 * Chay: mvn test -Dtest=DeepOffsetVsKeysetPaginationTest
 */
@SpringBootTest
@Testcontainers
class DeepOffsetVsKeysetPaginationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    private static final String INSERT_ORDER_SQL =
            "INSERT INTO orders (customer_name, status, total_amount, created_at) VALUES (?, ?, ?, ?)";
    private static final int ONE_MILLION = 1_000_000;
    private static final int BATCH_SIZE = 1_000;
    private static final int PAGE_SIZE = 20;
    private static final Instant BASE_INSTANT = Instant.parse("2020-01-01T00:00:00Z");

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeAll
    static void seedOneMillionOrdersWithMonotonicCreatedAt(@Autowired JdbcTemplate jdbcTemplate) {
        List<Object[]> batchArgs = new ArrayList<>(BATCH_SIZE);
        for (int i = 1; i <= ONE_MILLION; i++) {
            Timestamp createdAt = Timestamp.from(BASE_INSTANT.plusSeconds(i));
            batchArgs.add(new Object[]{"Customer", "CREATED", BigDecimal.ZERO, createdAt});
            if (batchArgs.size() == BATCH_SIZE) {
                jdbcTemplate.batchUpdate(INSERT_ORDER_SQL, batchArgs);
                batchArgs.clear();
            }
        }

        // Index ho tro dung ORDER BY created_at DESC, id DESC - de phep so
        // sanh OFFSET vs keyset o cung mot dieu kien "da co index toi uu",
        // khong bi nhieu boi chi phi Seq Scan + Sort rieng.
        jdbcTemplate.execute("CREATE INDEX idx_orders_created_at_id ON orders (created_at, id)");
        jdbcTemplate.execute("ANALYZE orders");
    }

    private String explain(String sql) {
        List<String> lines = jdbcTemplate.query(
                "EXPLAIN (ANALYZE, BUFFERS, FORMAT TEXT) " + sql,
                (rs, rowNum) -> rs.getString(1));
        return String.join("\n", lines);
    }

    private double parseExecutionTimeMs(String plan) {
        Matcher matcher = Pattern.compile("Execution Time: ([0-9.]+) ms").matcher(plan);
        assertThat(matcher.find()).isTrue();
        return Double.parseDouble(matcher.group(1));
    }

    /** Cursor (created_at, id) cua dong o vi tri P (dem tu 0) trong thu tu DESC. */
    private Object[] cursorForDescPosition(long position) {
        long i = ONE_MILLION - position;
        return new Object[]{Timestamp.from(BASE_INSTANT.plusSeconds(i)), i};
    }

    private String pageQuery(long offset) {
        return "SELECT id, created_at FROM orders ORDER BY created_at DESC, id DESC "
                + "LIMIT " + PAGE_SIZE + " OFFSET " + offset;
    }

    private String seekQuery(Object[] cursor) {
        return "SELECT id, created_at FROM orders "
                + "WHERE (created_at, id) < ('" + cursor[0] + "', " + cursor[1] + ") "
                + "ORDER BY created_at DESC, id DESC LIMIT " + PAGE_SIZE;
    }

    /**
     * OFFSET buoc phai DOC roi BO: du da co index ho tro dung ORDER BY (nen
     * KHONG can Seq Scan + Sort), Postgres van phai duyet qua dung OFFSET
     * dong dau tien trong index truoc khi bat dau tra ve LIMIT dong tiep
     * theo - chi phi nay tang TUYEN TINH theo OFFSET. Keyset pagination o
     * CUNG VI TRI SAU 500.000 lai nhanh gan bang OFFSET 0, vi WHERE (created_at,
     * id) < (...) cho phep B-tree "nhay" thang toi vi tri can thiet (index
     * seek, O(log n)) thay vi dem tung dong.
     */
    @Test
    void offset_cang_sau_cang_cham_con_keyset_o_cung_vi_tri_van_nhanh_nhu_trang_dau() {
        String plan0 = explain(pageQuery(0));
        String plan10k = explain(pageQuery(10_000));
        String plan500k = explain(pageQuery(500_000));

        double time0 = parseExecutionTimeMs(plan0);
        double time10k = parseExecutionTimeMs(plan10k);
        double time500k = parseExecutionTimeMs(plan500k);

        System.out.println("[LESSON16] OFFSET 0: " + time0 + " ms");
        System.out.println("[LESSON16] OFFSET 10.000: " + time10k + " ms");
        System.out.println("[LESSON16] OFFSET 500.000: " + time500k + " ms");
        System.out.println("[LESSON16] Plan OFFSET 500.000:\n" + plan500k);

        // "Duong cong": cang sau cang cham, khong phai hang so.
        assertThat(time10k).isGreaterThan(time0);
        assertThat(time500k).isGreaterThan(time10k);

        Object[] cursorAt500k = cursorForDescPosition(500_000);
        String seekPlan = explain(seekQuery(cursorAt500k));
        double seekTime = parseExecutionTimeMs(seekPlan);

        System.out.println("[LESSON16] Keyset tai cung vi tri (sau dong thu 500.000): " + seekTime + " ms");
        System.out.println("[LESSON16] Plan keyset:\n" + seekPlan);

        // Cung mot "do sau" logic (500.000 dong truoc no), nhung keyset
        // KHONG can dem qua tung do - phai nhanh hon han OFFSET 500.000.
        assertThat(seekTime).isLessThan(time500k);
    }

    /**
     * COUNT(*) chinh xac buoc phai quet TOAN BO bang (Postgres khong duy tri
     * san tong so dong nao ca vi MVCC - moi transaction co the thay mot
     * "phien ban" so dong khac nhau). pg_class.reltuples la con so Postgres
     * da tinh san tu lan ANALYZE/VACUUM gan nhat - tra ve tuc thi vi chi la
     * mot lookup catalog, nhung la SO GAN DUNG (approximate), khong dam bao
     * chinh xac tuyet doi tai moi thoi diem.
     */
    @Test
    void count_chinh_xac_phai_quet_ca_bang_con_reltuples_la_uoc_luong_tuc_thi() {
        long countStart = System.nanoTime();
        Long exactCount = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM orders", Long.class);
        double countTimeMs = (System.nanoTime() - countStart) / 1_000_000.0;

        long reltuplesStart = System.nanoTime();
        Long approxCount = jdbcTemplate.queryForObject(
                "SELECT reltuples::bigint FROM pg_class WHERE relname = 'orders'", Long.class);
        double reltuplesTimeMs = (System.nanoTime() - reltuplesStart) / 1_000_000.0;

        System.out.println("[LESSON16] COUNT(*) chinh xac = " + exactCount + ", mat " + countTimeMs + " ms");
        System.out.println("[LESSON16] pg_class.reltuples uoc luong = " + approxCount
                + ", mat " + reltuplesTimeMs + " ms");

        assertThat(exactCount).isEqualTo(ONE_MILLION);
        assertThat(reltuplesTimeMs).isLessThan(countTimeMs);
        // reltuples la uoc luong tu ANALYZE vua chay o @BeforeAll - kha sat
        // that te nhung KHONG assert bang tuyet doi, vi ban chat no la
        // sampling-based, khong phai dem chinh xac.
        assertThat(approxCount).isCloseTo((long) ONE_MILLION, withPercentage(1));
    }

    /**
     * "Count up to N+1": de biet UI co nen hien nut "Trang sau" hay khong,
     * khong can COUNT(*) ca bang - chi can xin LIMIT (pageSize + 1). Neu
     * nhan du pageSize + 1 dong, chac chan con trang ke tiep (khong can biet
     * CHINH XAC con bao nhieu). Neu nhan it hon, day la trang CUOI.
     */
    @Test
    void count_up_to_N_cong_1_biet_co_trang_ke_tiep_ma_khong_can_dem_ca_bang() {
        // Vi tri 500.000: chac chan con rat nhieu dong phia sau -> co trang ke tiep.
        Object[] deepCursor = cursorForDescPosition(500_000);
        List<Long> deepPageIds = jdbcTemplate.query(
                "SELECT id FROM orders WHERE (created_at, id) < ('" + deepCursor[0] + "', " + deepCursor[1] + ") "
                        + "ORDER BY created_at DESC, id DESC LIMIT " + (PAGE_SIZE + 1),
                (rs, rowNum) -> rs.getLong("id"));
        boolean hasNextFromDeepPage = deepPageIds.size() > PAGE_SIZE;

        System.out.println("[LESSON16] Trang giua bang: nhan duoc " + deepPageIds.size()
                + " dong (xin " + (PAGE_SIZE + 1) + ") -> hasNext = " + hasNextFromDeepPage);
        assertThat(deepPageIds).hasSize(PAGE_SIZE + 1);
        assertThat(hasNextFromDeepPage).isTrue();

        // cursorForDescPosition(P) dem P TU DAU (tu dong moi nhat, i lon).
        // De con DUNG 14 dong phia SAU cursor (i = 14..1), can P sao cho
        // ONE_MILLION - P - 1 = 14, tuc P = ONE_MILLION - 15 - dong tai vi
        // tri do la i = ONE_MILLION - P = 15 (dong thu 15 tinh TU CUOI thu
        // tu DESC, gan cuoi bang).
        Object[] nearEndCursor = cursorForDescPosition(ONE_MILLION - 15);
        List<Long> lastPageIds = jdbcTemplate.query(
                "SELECT id FROM orders WHERE (created_at, id) < ('" + nearEndCursor[0] + "', " + nearEndCursor[1]
                        + ") ORDER BY created_at DESC, id DESC LIMIT " + (PAGE_SIZE + 1),
                (rs, rowNum) -> rs.getLong("id"));
        boolean hasNextFromLastPage = lastPageIds.size() > PAGE_SIZE;

        System.out.println("[LESSON16] Trang cuoi: nhan duoc " + lastPageIds.size()
                + " dong (xin " + (PAGE_SIZE + 1) + ") -> hasNext = " + hasNextFromLastPage);
        assertThat(lastPageIds).hasSize(14);
        assertThat(hasNextFromLastPage).isFalse();
    }
}
