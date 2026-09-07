package com.example.orderinventory.order;

import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;

import jakarta.persistence.EntityManagerFactory;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Minh hoa bidirectional trap giua Order (inverse, @OneToMany mappedBy="order")
 * va OrderItem (owning, @ManyToOne giu cot order_id).
 * Chay: mvn test -Dtest=OrderItemBidirectionalTest
 */
@DataJpaTest
class OrderItemBidirectionalTest {

    @Autowired
    private TestEntityManager entityManager;

    @Autowired
    private OrderRepository orderRepository;

    @Autowired
    private OrderItemRepository orderItemRepository;

    @Autowired
    private EntityManagerFactory entityManagerFactory;

    private Statistics statistics() {
        return entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
    }

    @Test
    void chi_them_vao_collection_ma_quen_setOrder_khien_order_id_bi_null() {
        statistics().clear();

        Order order = new Order("Nguyen Van A", "CREATED");
        OrderItem item = new OrderItem("SKU-001", 2);

        // BUG: chi sua phia inverse (Order.items). Hibernate KHONG doc list nay
        // de sinh SQL, no chi dung field OrderItem.order (owning side) de biet
        // gia tri FK. item.order dang la null => insert voi order_id = NULL.
        order.getItems().add(item);

        orderRepository.save(order); // cascade = ALL -> item cung duoc persist theo
        entityManager.flush();

        // Chi 2 lenh INSERT (order, item). mappedBy khong sinh them SQL nao de
        // co "sua lai" FK giup ta - do la ly do no khong tu dong dung duoc.
        assertThat(statistics().getPrepareStatementCount()).isEqualTo(2);

        entityManager.clear();
        OrderItem reloaded = orderItemRepository.findById(item.getId()).orElseThrow();
        assertThat(reloaded.getOrder()).isNull(); // FK bi mat du item ro rang "thuoc ve" order
    }

    @Test
    void set_order_sau_lan_flush_dau_tien_sinh_ra_mot_UPDATE_thua() {
        statistics().clear();

        Order order = new Order("Tran Thi B", "CREATED");
        OrderItem item = new OrderItem("SKU-002", 1);

        order.getItems().add(item); // van chua goi item.setOrder(order)

        orderRepository.save(order);
        entityManager.flush(); // insert order, insert item VOI order_id = NULL

        // "Vam lai" bang cach set FK sau khi da flush lan dau (vd: mot doan code
        // sua chua o cho khac, hoac thu tu goi sai).
        item.setOrder(order);
        entityManager.flush(); // item dang managed -> dirty checking phat hien
        // order thay doi tu null sang co gia tri -> sinh them 1 UPDATE

        // 2 INSERT (order, item) + 1 UPDATE thua de dong bo lai FK
        assertThat(statistics().getPrepareStatementCount()).isEqualTo(3);

        entityManager.clear();
        OrderItem reloaded = orderItemRepository.findById(item.getId()).orElseThrow();
        assertThat(reloaded.getOrder().getId()).isEqualTo(order.getId());
    }

    @Test
    void dung_helper_method_addItem_dong_bo_ca_hai_phia_ngay_tu_dau() {
        statistics().clear();

        Order order = new Order("Le Van C", "CREATED");
        OrderItem item = new OrderItem("SKU-003", 5);

        order.addItem(item); // helper: items.add(item) + item.setOrder(this) trong CUNG mot loi goi

        orderRepository.save(order);
        entityManager.flush();

        // Chi 2 INSERT, KHONG co UPDATE thua nao: vi item.order da dung ngay tu
        // luc insert dau tien, Hibernate khong can sinh them cau lenh nao de sua.
        // => helper method khong lam tang chi phi SQL, no chi lam du lieu dung.
        assertThat(statistics().getPrepareStatementCount()).isEqualTo(2);

        entityManager.clear();
        OrderItem reloaded = orderItemRepository.findById(item.getId()).orElseThrow();
        assertThat(reloaded.getOrder().getId()).isEqualTo(order.getId());
    }
}
