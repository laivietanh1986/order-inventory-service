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
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Vu khi 1 va 2 chong N+1: JOIN FETCH va @EntityGraph. Dung lai chinh du lieu
 * va bai toan cua NPlusOneTest (muc 8): 100 order, moi order 2 item.
 * Chay: mvn test -Dtest=JoinFetchAndEntityGraphTest
 */
@DataJpaTest
class JoinFetchAndEntityGraphTest {

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
    void join_fetch_du_co_hay_khong_co_distinct_deu_chi_can_1_query_va_tra_ve_dung_100_order() {
        statistics().clear();

        List<Order> withoutDistinct = orderRepository.findAllWithItemsJoinFetchNoDistinct();
        List<Order> withDistinct = orderRepository.findAllWithItemsJoinFetch();

        // Hibernate 6 tu dong loai trung Order o tang object (dua vao identity
        // trong persistence context) cho ca hai truong hop - khac voi Hibernate
        // 5 cu, noi ban KHONG DISTINCT se tra ve 200 phan tu Order bi lap.
        assertThat(withoutDistinct).hasSize(100);
        assertThat(withDistinct).hasSize(100);
        for (Order order : withDistinct) {
            assertThat(order.getItems()).hasSize(2); // da duoc nap san, khong lazy-load them
        }

        // Ca hai deu chi ton 1 statement/lan goi -> tong 2 cho 2 lan goi tren.
        assertThat(statistics().getPrepareStatementCount()).isEqualTo(2);
    }

    @Test
    void join_fetch_van_la_mot_LEFT_JOIN_thuc_su_sinh_ra_200_row_du_lieu() {
        // Chung minh truc tiep ban chat "cartesian product": mot JOIN giua
        // orders va order_items (100 order x 2 item/order) luon sinh ra 200
        // to hop (order, item) o tang quan he - day chinh la SQL ma JOIN FETCH
        // thuc thi ben duoi, bat ke Hibernate co gop lai gon gang cho ta hay
        // khong o tang object. Cang nhieu item/order, so row nay cang phinh to.
        Long joinRowCount = ((Number) entityManager.getEntityManager()
                .createNativeQuery("SELECT COUNT(*) FROM order_items i JOIN orders o ON o.id = i.order_id")
                .getSingleResult()).longValue();

        assertThat(joinRowCount).isEqualTo(200);
    }

    @Test
    void entity_graph_cung_chi_1_query_de_nap_san_items() {
        statistics().clear();

        List<Order> orders = orderRepository.findAllWithItemsEntityGraph();

        Set<Long> distinctIds = orders.stream().map(Order::getId).collect(Collectors.toSet());
        assertThat(distinctIds).hasSize(100);

        for (Order order : orders) {
            assertThat(order.getItems()).hasSize(2);
        }

        // @EntityGraph duoc Spring Data dich thanh mot fetch graph hint gan
        // vao query - ve co ban van la MOT cau SQL co LEFT JOIN, giong ban
        // chat voi JOIN FETCH tuong minh o tren (va cung gap dung "cartesian
        // product" 200 row nhu tren, chi khac cach khai bao).
        assertThat(statistics().getPrepareStatementCount()).isEqualTo(1);
    }
}
