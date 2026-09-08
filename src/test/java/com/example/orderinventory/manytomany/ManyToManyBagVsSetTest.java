package com.example.orderinventory.manytomany;

import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;

import jakarta.persistence.EntityManagerFactory;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Minh hoa bag semantics cua @ManyToMany + List so voi @ManyToMany + Set.
 * Chay: mvn test -Dtest=ManyToManyBagVsSetTest
 */
@DataJpaTest
class ManyToManyBagVsSetTest {

    @Autowired
    private TestEntityManager entityManager;

    @Autowired
    private EntityManagerFactory entityManagerFactory;

    private Statistics statistics() {
        return entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
    }

    @Test
    void bag_list_them_1_tag_xoa_het_5_dong_cu_roi_insert_lai_ca_6() {
        BagDemoProduct product = new BagDemoProduct("Wireless Mouse");
        for (int i = 1; i <= 5; i++) {
            product.getTags().add(new BagDemoTag("tag-" + i));
        }

        entityManager.persist(product);
        entityManager.flush();
        entityManager.clear();

        BagDemoProduct reloaded = entityManager.find(BagDemoProduct.class, product.getId());
        assertThat(reloaded.getTags()).hasSize(5);

        statistics().clear();
        reloaded.getTags().add(new BagDemoTag("tag-6")); // chi THEM 1 tag moi
        entityManager.flush();

        // List khong co index column -> Hibernate coi day la mot BAG: cac
        // phan tu khong co dinh danh rieng trong bang join nen no khong the
        // tinh duoc diff chinh xac. Chien luoc an toan duy nhat: xoa SACH
        // toan bo 5 dong cu roi insert lai ca 6 dong hien tai.
        // 1 DELETE (xoa toan bo theo product_id) + 1 INSERT tag-6 (entity moi)
        // + 6 INSERT vao bang join = 8 statement.
        assertThat(statistics().getPrepareStatementCount()).isEqualTo(8);

        entityManager.clear();
        BagDemoProduct finalState = entityManager.find(BagDemoProduct.class, product.getId());
        assertThat(finalState.getTags()).hasSize(6);
    }

    @Test
    void set_them_1_tag_chi_insert_1_dong_moi_khong_dung_den_5_dong_cu() {
        SetDemoProduct product = new SetDemoProduct("Mechanical Keyboard");
        for (int i = 1; i <= 5; i++) {
            product.getTags().add(new SetDemoTag("tag-" + i));
        }

        entityManager.persist(product);
        entityManager.flush();
        entityManager.clear();

        SetDemoProduct reloaded = entityManager.find(SetDemoProduct.class, product.getId());
        assertThat(reloaded.getTags()).hasSize(5);

        statistics().clear();
        reloaded.getTags().add(new SetDemoTag("tag-6")); // chi THEM 1 tag moi
        entityManager.flush();

        // Set co the diff chinh xac phan tu nao moi duoc them (dua tren
        // equals/hashCode, o day la identity trong cung persistence context)
        // -> chi 1 INSERT tag-6 (entity) + 1 INSERT vao bang join = 2 statement,
        // KHONG dung gi den 5 dong da co san.
        assertThat(statistics().getPrepareStatementCount()).isEqualTo(2);

        entityManager.clear();
        SetDemoProduct finalState = entityManager.find(SetDemoProduct.class, product.getId());
        assertThat(finalState.getTags()).hasSize(6);
    }
}
