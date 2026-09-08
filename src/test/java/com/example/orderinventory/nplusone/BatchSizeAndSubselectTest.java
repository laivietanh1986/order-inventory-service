package com.example.orderinventory.nplusone;

import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;

import jakarta.persistence.EntityManagerFactory;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Vu khi 3 va 4 chong N+1: @BatchSize va @Fetch(SUBSELECT). Cung bai toan 100
 * order x 2 item nhu NPlusOneTest (muc 8) va JoinFetchAndEntityGraphTest,
 * nhung lan nay khong dong cham gi den mapping cua Order/OrderItem that.
 * Chay: mvn test -Dtest=BatchSizeAndSubselectTest
 */
@DataJpaTest
class BatchSizeAndSubselectTest {

    @Autowired
    private TestEntityManager entityManager;

    @Autowired
    private BatchSizeOrderRepository batchSizeOrderRepository;

    @Autowired
    private SubselectOrderRepository subselectOrderRepository;

    @Autowired
    private EntityManagerFactory entityManagerFactory;

    private Statistics statistics() {
        return entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
    }

    @Test
    void batch_size_20_bien_100_query_rieng_le_thanh_5_query_theo_lo() {
        for (int i = 0; i < 100; i++) {
            BatchSizeOrder order = new BatchSizeOrder("Customer " + i);
            order.addItem(new BatchSizeOrderItem("SKU-" + i + "-1"));
            order.addItem(new BatchSizeOrderItem("SKU-" + i + "-2"));
            entityManager.persist(order);
        }
        entityManager.flush();
        entityManager.clear();

        statistics().clear();

        List<BatchSizeOrder> orders = batchSizeOrderRepository.findAll(); // 1 query

        int totalItems = 0;
        for (BatchSizeOrder order : orders) {
            totalItems += order.getItems().size(); // lazy-load, nhung theo LO 20
        }

        assertThat(totalItems).isEqualTo(200);

        // 1 (findAll) + ceil(100/20) = 5 query theo lo (moi lo IN toi 20 order_id)
        // = 6, thay vi 101 nhu khong co @BatchSize.
        assertThat(statistics().getPrepareStatementCount()).isEqualTo(6);
    }

    @Test
    void subselect_giu_query_goc_lam_subquery_chi_can_1_query_cho_toan_bo_items() {
        for (int i = 0; i < 100; i++) {
            SubselectOrder order = new SubselectOrder("Customer " + i);
            order.addItem(new SubselectOrderItem("SKU-" + i + "-1"));
            order.addItem(new SubselectOrderItem("SKU-" + i + "-2"));
            entityManager.persist(order);
        }
        entityManager.flush();
        entityManager.clear();

        statistics().clear();

        List<SubselectOrder> orders = subselectOrderRepository.findAll(); // 1 query

        int totalItems = 0;
        for (SubselectOrder order : orders) {
            totalItems += order.getItems().size(); // lazy-load lan dau -> keo theo TAT CA cac order khac
        }

        assertThat(totalItems).isEqualTo(200);

        // 1 (findAll) + 1 (subselect nap items cho TOAN BO 100 order dang co
        // trong persistence context, dua tren dieu kien cua chinh cau findAll)
        // = 2, khong phu thuoc so luong order.
        assertThat(statistics().getPrepareStatementCount()).isEqualTo(2);
    }
}
