package com.example.orderinventory.order;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;

import java.util.List;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Ba cach tra ve danh sach OrderItem duoi dang tom tat (chi can productSku va
 * quantity): load entity roi map, constructor expression (SELECT new), va
 * interface-based projection. So sanh SO COT duoc SELECT va so luong query.
 * Chay: mvn test -Dtest=OrderItemProjectionTest
 */
@DataJpaTest
class OrderItemProjectionTest {

    private static final String HIBERNATE_SQL_LOGGER = "org.hibernate.SQL";

    @Autowired
    private TestEntityManager entityManager;

    @Autowired
    private OrderItemRepository orderItemRepository;

    private Logger sqlLogger;
    private ListAppender<ILoggingEvent> sqlAppender;

    @BeforeEach
    void seedAndAttachSqlCapture() {
        Order order = new Order("Nguyen Van A", "CREATED");
        order.addItem(new OrderItem("SKU-100", 3));
        order.addItem(new OrderItem("SKU-101", 5));
        entityManager.persist(order);
        entityManager.flush();
        entityManager.clear();

        sqlLogger = (Logger) LoggerFactory.getLogger(HIBERNATE_SQL_LOGGER);
        sqlLogger.setLevel(Level.DEBUG);
        sqlAppender = new ListAppender<>();
        sqlAppender.start();
        sqlLogger.addAppender(sqlAppender);
    }

    @AfterEach
    void detachSqlCapture() {
        sqlLogger.detachAppender(sqlAppender);
    }

    private List<String> capturedSql() {
        return sqlAppender.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
    }

    /**
     * Hibernate (voi format_sql=true) in SQL nhieu dong, moi cot duoc SELECT
     * tren mot dong rieng giua dong "select" va dong "from". Dem so dong do
     * de biet chinh xac so cot, khong phu thuoc vao dau phay/khoang trang.
     */
    private int countSelectedColumns(String sql) {
        String[] lines = sql.split("\\R");
        int start = -1;
        int end = -1;
        for (int i = 0; i < lines.length; i++) {
            String trimmed = lines[i].trim().toLowerCase(Locale.ROOT);
            if (start == -1 && trimmed.equals("select")) {
                start = i;
                continue;
            }
            if (start != -1 && trimmed.equals("from")) {
                end = i;
                break;
            }
        }
        int count = 0;
        for (int i = start + 1; i < end; i++) {
            if (!lines[i].trim().isEmpty()) {
                count++;
            }
        }
        return count;
    }

    /** True neu dong ngay sau "from" (bo qua dong trong) bat dau bang tableName. */
    private boolean selectsFromTable(String sql, String tableName) {
        String[] lines = sql.split("\\R");
        for (int i = 0; i < lines.length; i++) {
            if (lines[i].trim().equalsIgnoreCase("from")) {
                for (int j = i + 1; j < lines.length; j++) {
                    String trimmed = lines[j].trim();
                    if (!trimmed.isEmpty()) {
                        return trimmed.toLowerCase(Locale.ROOT).startsWith(tableName.toLowerCase(Locale.ROOT));
                    }
                }
            }
        }
        return false;
    }

    @Test
    void cach_1_load_entity_roi_map_can_them_1_query_rieng_va_nhieu_cot_hon_can() {
        sqlAppender.list.clear();

        List<OrderItem> items = entityManager.getEntityManager()
                .createQuery("SELECT i FROM OrderItem i", OrderItem.class)
                .getResultList();

        List<OrderItemSummaryDto> summaries = items.stream()
                .map(i -> new OrderItemSummaryDto(i.getProductSku(), i.getQuantity()))
                .toList();

        assertThat(summaries).hasSize(2);

        List<String> statements = capturedSql();
        String itemSelect = statements.stream()
                .filter(s -> selectsFromTable(s, "order_items"))
                .findFirst().orElseThrow();

        // Cau SELECT chinh cua OrderItem da co 4 cot (id, order_id, product_sku,
        // quantity) - nhieu hon 2 cot thuc su can dung.
        assertThat(countSelectedColumns(itemSelect)).isEqualTo(4);

        // OrderItem.order la @ManyToOne mac dinh EAGER (muc 5): vi JPQL o day
        // khong JOIN FETCH tuong minh, Hibernate KHONG gop no vao cung 1 cau
        // SQL - no phai chay THEM mot query RIENG de nap Order tuong ung (dung
        // 1 lan vi ca 2 item cung chung 1 order, da co san trong persistence
        // context tu identity map). Day chinh la hinh hai N+1 tu muc 8, chi
        // khac la o day N=1 vi chi co 1 order dang sau 2 item.
        boolean hasSeparateOrderSelect = statements.stream()
                .anyMatch(s -> selectsFromTable(s, "orders"));
        assertThat(hasSeparateOrderSelect).isTrue();
        assertThat(statements).hasSize(2); // 1 cho order_items + 1 cho orders
    }

    @Test
    void cach_2_constructor_expression_chi_1_query_dung_2_cot_can_dung() {
        sqlAppender.list.clear();

        List<OrderItemSummaryDto> summaries = orderItemRepository.findAllAsConstructorExpression();

        assertThat(summaries).hasSize(2);
        assertThat(summaries).extracting(OrderItemSummaryDto::getProductSku)
                .containsExactlyInAnyOrder("SKU-100", "SKU-101");

        List<String> statements = capturedSql();
        assertThat(statements).hasSize(1); // KHONG co query rieng nao cho orders
        assertThat(statements.get(0)).doesNotContainIgnoringCase("orders");
        assertThat(countSelectedColumns(statements.get(0))).isEqualTo(2);
    }

    @Test
    void cach_3_interface_projection_cung_chi_1_query_dung_2_cot() {
        sqlAppender.list.clear();

        List<OrderItemSummaryView> views = orderItemRepository.findAllAsInterfaceProjection();

        assertThat(views).hasSize(2);
        assertThat(views).extracting(OrderItemSummaryView::getProductSku)
                .containsExactlyInAnyOrder("SKU-100", "SKU-101");

        List<String> statements = capturedSql();
        assertThat(statements).hasSize(1);
        assertThat(statements.get(0)).doesNotContainIgnoringCase("orders");
        assertThat(countSelectedColumns(statements.get(0))).isEqualTo(2);
    }
}
