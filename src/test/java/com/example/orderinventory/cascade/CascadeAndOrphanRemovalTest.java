package com.example.orderinventory.cascade;

import com.example.orderinventory.order.Order;
import com.example.orderinventory.order.OrderItem;
import com.example.orderinventory.product.Product;
import jakarta.persistence.PersistenceException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Minh hoa 4 to hop cascade/orphanRemoval tren quan he Order-OrderItem, va
 * mot anti-pattern: cascade = ALL tu OrderItem sang Product.
 * Chay: mvn test -Dtest=CascadeAndOrphanRemovalTest
 */
@DataJpaTest
class CascadeAndOrphanRemovalTest {

    @Autowired
    private TestEntityManager entityManager;

    // ---------- Kich ban 1: KHONG cascade ----------

    @Test
    void khong_cascade_xoa_order_that_bai_vi_item_van_con_tham_chieu() {
        NoCascadeOrder order = new NoCascadeOrder("Nguyen Van A");
        NoCascadeOrderItem item = new NoCascadeOrderItem("SKU-100");
        order.addItem(item);

        // Khong co cascade PERSIST: phai tu persist ca hai, khong the "nho" vao
        // viec persist(order) de item duoc luu theo.
        entityManager.persist(order);
        entityManager.persist(item);
        entityManager.flush();
        entityManager.clear();

        NoCascadeOrder reloaded = entityManager.find(NoCascadeOrder.class, order.getId());

        // Xoa order trong khi item van con FK tro toi -> vi pham rang buoc khoa
        // ngoai ngay tai DB, khong co Hibernate cascade nao "don dep ho".
        assertThatThrownBy(() -> {
            entityManager.remove(reloaded);
            entityManager.flush();
        }).isInstanceOf(PersistenceException.class);
    }

    // ---------- Kich ban 2: chi CascadeType.REMOVE ----------

    @Test
    void cascade_remove_khong_kich_hoat_khi_chi_tach_item_khoi_collection() {
        CascadeRemoveOrder order = new CascadeRemoveOrder("Tran Thi B");
        CascadeRemoveOrderItem item = new CascadeRemoveOrderItem("SKU-200");
        order.addItem(item);

        entityManager.persist(order);
        entityManager.persist(item); // cascade=REMOVE khong bao gom PERSIST
        entityManager.flush();
        entityManager.clear();

        CascadeRemoveOrder reloaded = entityManager.find(CascadeRemoveOrder.class, order.getId());
        Long itemId = reloaded.getItems().get(0).getId();

        reloaded.getItems().remove(0); // chi tach khoi collection, KHONG xoa order
        entityManager.flush();
        entityManager.clear();

        // REMOVE chi kich hoat khi entityManager.remove() duoc goi tren CHINH
        // Order - tach khoi collection khong lien quan, nen item van con nguyen.
        assertThat(entityManager.find(CascadeRemoveOrderItem.class, itemId)).isNotNull();
    }

    @Test
    void cascade_remove_xoa_toan_bo_item_khi_xoa_chinh_order() {
        CascadeRemoveOrder order = new CascadeRemoveOrder("Tran Thi B2");
        CascadeRemoveOrderItem item1 = new CascadeRemoveOrderItem("SKU-201");
        CascadeRemoveOrderItem item2 = new CascadeRemoveOrderItem("SKU-202");
        order.addItem(item1);
        order.addItem(item2);

        entityManager.persist(order);
        entityManager.persist(item1);
        entityManager.persist(item2);
        entityManager.flush();
        entityManager.clear();

        CascadeRemoveOrder reloaded = entityManager.find(CascadeRemoveOrder.class, order.getId());
        Long item1Id = reloaded.getItems().get(0).getId();
        Long item2Id = reloaded.getItems().get(1).getId();

        entityManager.remove(reloaded); // xoa CHINH order -> REMOVE cascade kich hoat
        entityManager.flush();

        assertThat(entityManager.find(CascadeRemoveOrderItem.class, item1Id)).isNull();
        assertThat(entityManager.find(CascadeRemoveOrderItem.class, item2Id)).isNull();
    }

    // ---------- Kich ban 3: chi orphanRemoval = true ----------

    @Test
    void orphan_removal_tu_xoa_item_bi_tach_khoi_collection_du_khong_xoa_order() {
        OrphanRemovalOrder order = new OrphanRemovalOrder("Le Van C");
        OrphanRemovalOrderItem item1 = new OrphanRemovalOrderItem("SKU-300");
        OrphanRemovalOrderItem item2 = new OrphanRemovalOrderItem("SKU-301");
        order.addItem(item1);
        order.addItem(item2);

        // cascade = PERSIST co san tren quan he nay -> chi can persist order,
        // item duoc cascade theo.
        entityManager.persist(order);
        entityManager.flush();
        entityManager.clear();

        OrphanRemovalOrder reloaded = entityManager.find(OrphanRemovalOrder.class, order.getId());
        Long item1Id = reloaded.getItems().get(0).getId();

        reloaded.getItems().remove(0); // KHONG xoa order, chi tach 1 phan tu
        entityManager.flush();

        // orphanRemoval theo doi truc tiep viec mot phan tu bien mat khoi
        // collection va tu dong xoa no, hoan toan doc lap voi so phan cua order.
        assertThat(entityManager.find(OrphanRemovalOrderItem.class, item1Id)).isNull();
        assertThat(entityManager.find(OrphanRemovalOrder.class, order.getId())).isNotNull();
    }

    // ---------- Kich ban 4: ca cascade = REMOVE (qua CascadeType.ALL) lan orphanRemoval ----------
    // Tai su dung Order/OrderItem "that" cua muc 2, dang cau hinh
    // cascade = CascadeType.ALL + orphanRemoval = true.

    @Test
    void ca_hai_thi_vua_xoa_duoc_theo_collection_vua_xoa_duoc_theo_order() {
        Order order = new Order("Pham Thi D", "CREATED");
        OrderItem item1 = new OrderItem("SKU-400", 1);
        OrderItem item2 = new OrderItem("SKU-401", 2);
        order.addItem(item1);
        order.addItem(item2);

        entityManager.persist(order); // cascade ALL -> ca hai item duoc persist theo
        entityManager.flush();
        entityManager.clear();

        Order reloaded = entityManager.find(Order.class, order.getId());
        Long item1Id = reloaded.getItems().get(0).getId();
        Long item2Id = reloaded.getItems().get(1).getId();

        // (a) tach 1 item khoi collection -> orphanRemoval tu xoa no
        reloaded.getItems().remove(0);
        entityManager.flush();
        assertThat(entityManager.find(OrderItem.class, item1Id)).isNull();

        // (b) xoa ca order -> REMOVE cascade xoa not item con lai
        entityManager.remove(reloaded);
        entityManager.flush();
        assertThat(entityManager.find(OrderItem.class, item2Id)).isNull();
        assertThat(entityManager.find(Order.class, order.getId())).isNull();
    }

    // ---------- Anti-pattern: cascade = ALL tu OrderItem sang Product ----------

    @Test
    void anti_pattern_xoa_order_lam_xoa_luon_ca_product_dung_chung() {
        Product product = new Product("Wireless Mouse", "SKU-SHARED-001", 50, new BigDecimal("19.99"));
        entityManager.persist(product);

        BadCascadeOrder order = new BadCascadeOrder("Hoang Van E");
        BadCascadeOrderItem item = new BadCascadeOrderItem(product);
        order.addItem(item);

        entityManager.persist(order); // cascade ALL tren items -> item duoc persist theo
        entityManager.flush();
        entityManager.clear();

        Long productId = product.getId();
        BadCascadeOrder reloaded = entityManager.find(BadCascadeOrder.class, order.getId());

        entityManager.remove(reloaded); // xoa order -> cascade ALL xoa item -> cascade ALL xoa luon product
        entityManager.flush();

        // Day la hau qua sai: Product la danh muc dung chung, khong nen bien
        // mat chi vi mot don hang tham chieu no bi xoa.
        assertThat(entityManager.find(Product.class, productId)).isNull();
    }
}
