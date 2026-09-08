package com.example.orderinventory.fetch;

import com.example.orderinventory.inventory.Inventory;
import com.example.orderinventory.order.Order;
import com.example.orderinventory.order.OrderItem;
import com.example.orderinventory.product.Product;
import org.hibernate.Hibernate;
import org.hibernate.LazyInitializationException;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;

import jakarta.persistence.EntityManagerFactory;
import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Minh hoa fetch type mac dinh cua tung loai association va cai bay cua
 * @OneToOne inverse side.
 * Chay: mvn test -Dtest=DefaultFetchTypeTest
 */
@DataJpaTest
class DefaultFetchTypeTest {

    @Autowired
    private TestEntityManager entityManager;

    @Autowired
    private EntityManagerFactory entityManagerFactory;

    private Statistics statistics() {
        return entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
    }

    // ---------- @ManyToOne khong khai bao gi -> mac dinh EAGER ----------

    @Test
    void manyToOne_mac_dinh_eager_nen_load_orderItem_tu_dong_keo_theo_order() {
        Order order = new Order("Nguyen Van A", "CREATED");
        OrderItem item = new OrderItem("SKU-900", 1);
        order.addItem(item);

        entityManager.persist(order);
        entityManager.flush();
        entityManager.clear();

        statistics().clear();
        OrderItem loaded = entityManager.find(OrderItem.class, item.getId());

        // Chua he goi loaded.getOrder() nhung field da duoc nap san du lieu
        // that (khong phai proxy) - vi @ManyToOne khong khai bao fetch nen
        // mac dinh la EAGER.
        assertThat(Hibernate.isInitialized(loaded.getOrder())).isTrue();

        // Ca hai deu bi keo ve chi trong 1 lan find(): Hibernate JOIN san
        // order_items voi orders trong CUNG mot cau SELECT.
        assertThat(statistics().getPrepareStatementCount()).isEqualTo(1);
    }

    // ---------- @OneToOne owning side khong khai bao gi -> mac dinh EAGER ----------

    @Test
    void oneToOne_owning_mac_dinh_eager_nen_load_inventory_tu_dong_keo_theo_product() {
        Product product = new Product("Wireless Mouse", "SKU-901", 100, new BigDecimal("19.99"));
        entityManager.persist(product);
        Inventory inventory = new Inventory(product, 50);
        entityManager.persist(inventory);
        entityManager.flush();
        entityManager.clear();

        statistics().clear();
        Inventory loaded = entityManager.find(Inventory.class, inventory.getId());

        assertThat(Hibernate.isInitialized(loaded.getProduct())).isTrue();
        assertThat(statistics().getPrepareStatementCount()).isEqualTo(1); // 1 SELECT co JOIN
    }

    // ---------- @OneToOne inverse side khai bao fetch = LAZY nhung van EAGER ----------

    @Test
    void oneToOne_inverse_khai_bao_lazy_nhung_van_bi_load_eager() {
        Product product = new Product("Mechanical Keyboard", "SKU-902", 30, new BigDecimal("89.90"));
        entityManager.persist(product);
        Inventory inventory = new Inventory(product, 20);
        entityManager.persist(inventory);
        entityManager.flush();
        entityManager.clear();

        statistics().clear();
        Product loaded = entityManager.find(Product.class, product.getId());

        // Product.inventory khai bao fetch = LAZY, nhung day la phia INVERSE
        // (mappedBy) cua @OneToOne: Hibernate khong the tao proxy vi khong
        // biet truoc gia tri la mot Inventory hay null (FK nam ben bang
        // inventory, khong phai bang products) neu khong bat bytecode
        // enhancement. Ket qua: no vi phia hint LAZY va load EAGER.
        assertThat(Hibernate.isInitialized(loaded.getInventory())).isTrue();

        // 1 SELECT cho Product + 1 SELECT rieng de tim Inventory tuong ung
        // (khong the JOIN ngay trong cau dau vi Product khong giu FK).
        assertThat(statistics().getPrepareStatementCount()).isEqualTo(2);
    }

    // ---------- @OneToMany mac dinh LAZY + LazyInitializationException ----------

    @Test
    void oneToMany_mac_dinh_lazy_va_nem_LazyInitializationException_khi_da_detach() {
        Order order = new Order("Tran Thi B", "CREATED");
        OrderItem item = new OrderItem("SKU-903", 2);
        order.addItem(item);

        entityManager.persist(order);
        entityManager.flush();
        entityManager.clear();

        Order loaded = entityManager.find(Order.class, order.getId());

        // OneToMany khong khai bao fetch -> mac dinh LAZY: items la mot proxy
        // collection chua duoc init, hoan toan khac voi @ManyToOne/@OneToOne
        // o hai test tren.
        assertThat(Hibernate.isInitialized(loaded.getItems())).isFalse();

        entityManager.detach(loaded); // loaded roi khoi persistence context

        // Truy cap collection lazy tren mot entity da detach (khong con
        // Session nao de chay cau SELECT con thieu) -> nem ngoai le thay vi
        // am tham tra ve du lieu sai.
        assertThatThrownBy(() -> loaded.getItems().size())
                .isInstanceOf(LazyInitializationException.class);
    }
}
