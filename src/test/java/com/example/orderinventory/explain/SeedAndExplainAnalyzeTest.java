package com.example.orderinventory.explain;

import com.example.orderinventory.order.Order;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.PersistenceContext;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tu day chuyen han sang PostgreSQL that qua Testcontainers thay vi H2 - vi
 * H2 khong co EXPLAIN ANALYZE that, khong co MVCC/lock/isolation giong
 * Postgres, va "noi doi" ve execution plan. @ServiceConnection tu dong tro
 * DataSource cua toan bo Spring context (bao gom Flyway) vao container nay,
 * nen schema duoc tao lai tu dau qua CHINH cac migration V1-V11 da dung cho
 * H2 - day cung la lan dau tien cac migration nay duoc kiem chung tren
 * Postgres that.
 * Chay: mvn test -Dtest=SeedAndExplainAnalyzeTest
 *
 * LUU Y VE THU TU KHOI DONG: @BeforeAll o day PHAI la static (lifecycle mac
 * dinh PER_METHOD, KHONG dung @TestInstance(PER_CLASS)). Voi PER_CLASS, JUnit
 * phai tao instance test truoc de goi @BeforeAll khong-static, va viec tao
 * instance do kich hoat Spring nap ApplicationContext (qua
 * postProcessTestInstance) TRUOC KHI extension @Testcontainers kip start
 * container - dung ngay loi "Mapped port can only be obtained after the
 * container is started" da gap khi thu PER_CLASS.
 */
@SpringBootTest
@Testcontainers
class SeedAndExplainAnalyzeTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    private static final String INSERT_ORDER_SQL =
            "INSERT INTO orders (customer_name, status, total_amount) VALUES (?, ?, ?)";
    private static final int ONE_MILLION = 1_000_000;
    private static final int BATCH_SIZE = 1_000;
    private static final int JPA_DEMO_COUNT = 5_000;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private EntityManagerFactory entityManagerFactory;

    @PersistenceContext
    private EntityManager entityManager;

    private Statistics statistics() {
        return entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
    }

    /**
     * Seeder that su dung cho ca lop test: JDBC batch insert 1 trieu order
     * NGAY TRUOC KHI Postgres chay ANALYZE tren bang - day chinh la dieu kien
     * de test EXPLAIN ANALYZE ben duoi co co hoi thay statistics con cu.
     * status duoc chia lech (90% CREATED / 9% CONFIRMED / 1% CANCELLED) de co
     * mot gia tri hiem (CANCELLED, ~10.000/1.000.000 dong) dung cho query loc.
     */
    @BeforeAll
    static void seedOneMillionOrdersViaJdbcBatchInsert(@Autowired JdbcTemplate jdbcTemplate) {
        long start = System.nanoTime();
        List<Object[]> batchArgs = new ArrayList<>(BATCH_SIZE);
        for (int i = 1; i <= ONE_MILLION; i++) {
            String status;
            if (i % 100 == 0) {
                status = "CANCELLED";
            } else if (i % 10 == 0) {
                status = "CONFIRMED";
            } else {
                status = "CREATED";
            }
            batchArgs.add(new Object[]{"Customer " + (i % 200_000), status, BigDecimal.valueOf(i % 1000)});
            if (batchArgs.size() == BATCH_SIZE) {
                jdbcTemplate.batchUpdate(INSERT_ORDER_SQL, batchArgs);
                batchArgs.clear();
            }
        }

        long durationMillis = (System.nanoTime() - start) / 1_000_000;
        System.out.println("[LESSON13] JDBC batch insert " + ONE_MILLION + " row: " + durationMillis + " ms");

        Long rowCount = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM orders", Long.class);
        assertThat(rowCount).isEqualTo(ONE_MILLION);
    }

    @Test
    void bang_orders_co_dung_1_trieu_dong_sau_khi_seed() {
        Long rowCount = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM orders", Long.class);
        assertThat(rowCount).isEqualTo(ONE_MILLION);
    }

    /**
     * "Thu bang JPA mot lan de tu thay tai sao khong nen": persist() lien tuc
     * trong CUNG MOT transaction/persistence context ma khong flush/clear -
     * toan bo entity nam trong RAM cho den luc flush() cuoi cung. Vi
     * application.yaml KHONG cau hinh hibernate.jdbc.batch_size, moi INSERT
     * la mot prepared statement rieng - dem duoc chinh xac qua Statistics.
     * Chi thu voi so luong nho (5.000, khong phai 1 trieu): lam that voi 1
     * trieu theo kieu nay se giu ca 1 trieu entity quan ly cung luc trong
     * persistence context, rat de OutOfMemoryError va cham toi muc khong
     * thuc te de chay trong 1 bai test.
     */
    @Test
    @Transactional
    void jpa_persist_loop_khong_flush_clear_sinh_N_statement_rieng_va_cham_hon_JDBC_batch() {
        statistics().clear();

        long jpaStart = System.nanoTime();
        for (int i = 0; i < JPA_DEMO_COUNT; i++) {
            entityManager.persist(new Order("JPA Seed " + i, "CREATED"));
        }
        entityManager.flush(); // toan bo JPA_DEMO_COUNT INSERT don don don don o day
        long jpaDurationNanos = System.nanoTime() - jpaStart;
        long jpaStatementCount = statistics().getPrepareStatementCount();

        entityManager.clear();

        long jdbcStart = System.nanoTime();
        List<Object[]> batchArgs = new ArrayList<>(JPA_DEMO_COUNT);
        for (int i = 0; i < JPA_DEMO_COUNT; i++) {
            batchArgs.add(new Object[]{"JDBC Seed " + i, "CREATED", BigDecimal.ZERO});
        }
        jdbcTemplate.batchUpdate(INSERT_ORDER_SQL, batchArgs);
        long jdbcDurationNanos = System.nanoTime() - jdbcStart;

        System.out.println("[LESSON13] JPA persist-loop (" + JPA_DEMO_COUNT + " row): "
                + (jpaDurationNanos / 1_000_000) + " ms, " + jpaStatementCount + " prepared statement");
        System.out.println("[LESSON13] JDBC batchUpdate (" + JPA_DEMO_COUNT + " row): "
                + (jdbcDurationNanos / 1_000_000) + " ms");

        // Khong co hibernate.jdbc.batch_size -> dung 1 statement rieng cho
        // moi entity, khong he co "gop lai" nao ca.
        assertThat(jpaStatementCount).isEqualTo(JPA_DEMO_COUNT);
        // JDBC batch insert cung so luong dong phai nhanh hon han.
        assertThat(jdbcDurationNanos).isLessThan(jpaDurationNanos);
    }

    /**
     * Chua tao index nao tren cot status -> Postgres KHONG CO LUA CHON nao
     * khac ngoai Seq Scan cho du bang co 1 trieu dong. BUFFERS cho biet bao
     * nhieu block 8KB duoc dung: "shared hit" la doc tu shared_buffers cache
     * (nhanh), "shared read" la phai doc tu dia/OS cache (cham hon).
     */
    @Test
    void explain_analyze_loc_theo_status_hiem_khi_chua_co_index_la_seq_scan() {
        List<String> planLines = jdbcTemplate.query(
                "EXPLAIN (ANALYZE, BUFFERS, FORMAT TEXT) SELECT * FROM orders WHERE status = 'CANCELLED'",
                (rs, rowNum) -> rs.getString(1));
        String plan = String.join("\n", planLines);

        System.out.println("[LESSON13] EXPLAIN (ANALYZE, BUFFERS) orders WHERE status = 'CANCELLED':");
        System.out.println(plan);

        assertThat(plan).containsIgnoringCase("Seq Scan on orders");
        assertThat(plan).contains("Buffers:");

        // Dong dau tien co dang:
        // Seq Scan on orders (cost=0.00..23834.00 rows=X width=45)
        //   (actual time=0.045..152.334 rows=Y loops=1)
        // "rows=" thu nhat la UOC LUONG cua planner (dua tren statistics),
        // "rows=" thu hai la SO DONG THUC SU no doc duoc.
        Pattern rowsPattern = Pattern.compile("rows=(\\d+).*?rows=(\\d+)");
        Matcher matcher = rowsPattern.matcher(plan);
        assertThat(matcher.find()).isTrue();
        long estimatedRows = Long.parseLong(matcher.group(1));
        long actualRows = Long.parseLong(matcher.group(2));

        System.out.println("[LESSON13] estimated rows = " + estimatedRows + ", actual rows = " + actualRows);

        // ~10.000 dong thuc su co status = CANCELLED (1% cua 1 trieu dong da
        // seed) - day la con so THAT, khong phu thuoc statistics.
        assertThat(actualRows).isCloseTo(10_000, org.assertj.core.data.Percentage.withPercentage(5));
        // estimatedRows KHONG duoc assert bang mot gia tri cu the: no phu
        // thuoc viec autovacuum da kip ANALYZE bang orders hay chua tai thoi
        // diem cau EXPLAIN nay chay - chinh do bat dinh nay la thu can quan
        // sat va ghi lai (xem docs), khong phai thu can ep mot ket qua.
    }
}
