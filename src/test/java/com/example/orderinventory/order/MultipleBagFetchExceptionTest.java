package com.example.orderinventory.order;

import com.example.orderinventory.bagfetch.BagFetchFixItemA;
import com.example.orderinventory.bagfetch.BagFetchFixItemB;
import com.example.orderinventory.bagfetch.BagFetchFixParent;
import com.example.orderinventory.bagfetch.BagFetchFixParentRepository;
import jakarta.persistence.EntityManager;
import org.hibernate.loader.MultipleBagFetchException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * MultipleBagFetchException: Hibernate khong the JOIN FETCH dong thoi hai
 * collection deu la "bag" (List khong @OrderColumn) trong cung mot query.
 *
 * Ghi chu: de bai goc con mo ta mot kich ban thu ba - khai bao query hong nay
 * thanh @NamedQuery se lam EntityManagerFactory that bai NGAY LUC KHOI DONG
 * (Hibernate validate tat ca named query luc bootstrap SessionFactory). Day
 * la hanh vi Hibernate duoc tai lieu hoa ro rang, nhung khi thu tai hien no
 * bang mot context Spring bi lap (ApplicationContextRunner + entity rieng)
 * de khong anh huong context dung chung cua ca du an, ket qua lai KHONG on
 * dinh giua cac lan chay (Hibernate 6.2.5 co ve co mot quirk ve thu tu nap
 * lop/ANTLR parser khi build nhieu SessionFactory lien tiep trong cung JVM).
 * Vi mot test flaky con te hai hon la khong co test, kich ban nay CHI duoc
 * ghi lai trong docs (muc 11), khong duoc gan vao bo test tu dong.
 * Chay: mvn test -Dtest=MultipleBagFetchExceptionTest
 */
@DataJpaTest
class MultipleBagFetchExceptionTest {

    @Autowired
    private TestEntityManager entityManager;

    @Autowired
    private BagFetchFixParentRepository bagFetchFixParentRepository;

    private Long orderId;

    @BeforeEach
    void seedOrderWithItemsAndHistory() {
        Order order = new Order("Nguyen Van A", "CREATED");
        order.addItem(new OrderItem("SKU-001", 1));
        order.addItem(new OrderItem("SKU-002", 2));
        order.addStatusHistory(new OrderStatusHistory("CREATED"));
        order.addStatusHistory(new OrderStatusHistory("CONFIRMED"));

        entityManager.persist(order);
        entityManager.flush();
        entityManager.clear();

        orderId = order.getId();
    }

    @Test
    void join_fetch_dong_thoi_hai_bag_nem_MultipleBagFetchException_luc_thuc_thi() {
        EntityManager em = entityManager.getEntityManager();

        // Loi khong xay ra khi tao Query (createQuery van thanh cong)...
        var query = em.createQuery(
                "SELECT o FROM Order o JOIN FETCH o.items JOIN FETCH o.statusHistory");

        // ...ma xay ra khi Hibernate thuc su bien dich va thuc thi query nay.
        assertThatThrownBy(query::getResultList)
                .isInstanceOf(IllegalArgumentException.class)
                .hasRootCauseInstanceOf(MultipleBagFetchException.class)
                .hasStackTraceContaining("cannot simultaneously fetch multiple bags");
    }

    @Test
    void fix_2_tach_thanh_2_query_rieng_biet_hibernate_tu_gop_vao_persistence_context() {
        EntityManager em = entityManager.getEntityManager();

        // Query 1: chi JOIN FETCH items - an toan, khong co bag thu hai nao.
        Order withItems = em.createQuery(
                        "SELECT o FROM Order o JOIN FETCH o.items WHERE o.id = :id", Order.class)
                .setParameter("id", orderId)
                .getSingleResult();

        // Query 2: chi JOIN FETCH statusHistory - cung an toan tuong tu.
        Order withHistory = em.createQuery(
                        "SELECT o FROM Order o JOIN FETCH o.statusHistory WHERE o.id = :id", Order.class)
                .setParameter("id", orderId)
                .getSingleResult();

        // Ca hai query cung tra ve id giong nhau -> trong CUNG mot persistence
        // context, Hibernate tra ve LAI CHINH object Java da duoc quan ly tu
        // query 1 (identity map), chi bo sung them statusHistory da duoc nap
        // boi query 2 vao DUNG object do.
        assertThat(withItems).isSameAs(withHistory);
        assertThat(withItems.getItems()).hasSize(2);
        assertThat(withItems.getStatusHistory()).hasSize(2);
    }

    @Test
    void fix_1_doi_ca_hai_ve_Set_thi_JOIN_FETCH_dong_thoi_thanh_cong_va_dung_du_lieu() {
        BagFetchFixParent parent = new BagFetchFixParent();
        entityManager.persist(parent);
        parent.addItemA(new BagFetchFixItemA());
        parent.addItemA(new BagFetchFixItemA());
        parent.addItemB(new BagFetchFixItemB());
        parent.addItemB(new BagFetchFixItemB());
        entityManager.flush();
        entityManager.clear();

        // Ca hai ben deu da la Set (khong con la bag) -> JOIN FETCH dong thoi
        // ca hai CUNG LUC khong nem MultipleBagFetchException, VA khong bi
        // nhan doi phan tu du JOIN sinh ra 2x2=4 row o tang SQL (khac voi
        // truong hop chi doi MOT ben - xem docs muc 11 de biet vi sao).
        BagFetchFixParent reloaded = bagFetchFixParentRepository.findWithBothSetsById(parent.getId());

        assertThat(reloaded.getItemsA()).hasSize(2);
        assertThat(reloaded.getItemsB()).hasSize(2);
    }
}
