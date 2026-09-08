package com.example.orderinventory.order;

import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;

import jakarta.persistence.EntityManagerFactory;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tai hien N+1: load 100 order roi duyet qua order.getItems() cua tung order.
 * Chay: mvn test -Dtest=NPlusOneTest
 */
@DataJpaTest
class NPlusOneTest {

    @Autowired
    private TestEntityManager entityManager;

    @Autowired
    private OrderRepository orderRepository;

    @Autowired
    private EntityManagerFactory entityManagerFactory;

    private Statistics statistics() {
        return entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
    }

    @BeforeEach
    void seed100Orders() {
        for (int i = 0; i < 100; i++) {
            Order order = new Order("Customer " + i, "CREATED");
            order.addItem(new OrderItem("SKU-" + i + "-1", 1));
            order.addItem(new OrderItem("SKU-" + i + "-2", 2));
            entityManager.persist(order);
        }
        entityManager.flush();
        entityManager.clear();
    }

    @Test
    void load_100_order_roi_duyet_items_sinh_ra_101_query_khong_phai_1() {
        statistics().clear();

        List<Order> orders = orderRepository.findAll(); // 1 query lay 100 order

        int totalItems = 0;
        for (Order order : orders) {
            totalItems += order.getItems().size(); // moi order lazy-load rieng -> 1 query/order
        }

        assertThat(totalItems).isEqualTo(200); // 100 order x 2 item

        // Ky vong "ngay tho": chi can 1 query la du de lay het du lieu can dung.
        // Thuc te do duoc: 1 (orders) + 100 (items cua tung order) = 101.
        // Day la con so CO THAT, khong phai uoc luong - neu ai do sau nay them
        // JOIN FETCH/EntityGraph/BatchSize (muc 9) ma quen cap nhat assertion
        // nay, test se fail va bao dong ngay.
        assertThat(statistics().getPrepareStatementCount()).isEqualTo(101);
    }
}
