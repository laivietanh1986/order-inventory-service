package com.example.orderinventory.tag;

import com.example.orderinventory.product.Product;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;

import jakarta.persistence.EntityManagerFactory;
import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Minh hoa loi ich cua viec tach @ManyToMany thanh join entity tuong minh
 * (ProductTag): moi dong co @Id rieng nen them 1 tag chi la 1 INSERT, va co
 * the mang theo metadata rieng cho tung lien ket (created_at, sort_order).
 * Chay: mvn test -Dtest=ProductTagJoinEntityTest
 */
@DataJpaTest
class ProductTagJoinEntityTest {

    @Autowired
    private TestEntityManager entityManager;

    @Autowired
    private EntityManagerFactory entityManagerFactory;

    private Statistics statistics() {
        return entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
    }

    @Test
    void them_1_tag_moi_chi_insert_1_dong_du_da_co_5_tag() {
        Product product = new Product("USB-C Cable", "SKU-TAG-001", 100, new BigDecimal("5.50"));
        entityManager.persist(product);

        for (int i = 1; i <= 5; i++) {
            Tag tag = new Tag("tag-" + i);
            entityManager.persist(tag);
            product.addTag(tag, i);
        }
        entityManager.flush();
        entityManager.clear();

        Product reloaded = entityManager.find(Product.class, product.getId());
        assertThat(reloaded.getProductTags()).hasSize(5);

        statistics().clear();
        Tag newTag = new Tag("tag-6");
        entityManager.persist(newTag);
        reloaded.addTag(newTag, 6);
        entityManager.flush();

        // Khac han bag: ProductTag la mot entity that su co @Id rieng. Them
        // mot lien ket moi chi don gian la 1 INSERT cho Tag + 1 INSERT cho
        // ProductTag = 2 statement, KHONG dung gi den 5 dong ProductTag cu.
        assertThat(statistics().getPrepareStatementCount()).isEqualTo(2);

        entityManager.clear();
        Product finalState = entityManager.find(Product.class, product.getId());
        assertThat(finalState.getProductTags()).hasSize(6);

        ProductTag sixth = finalState.getProductTags().stream()
                .filter(pt -> pt.getTag().getName().equals("tag-6"))
                .findFirst()
                .orElseThrow();

        // Metadata ma mot bang join thuan tuy (chi 2 cot FK) khong the co.
        assertThat(sixth.getSortOrder()).isEqualTo(6);
        assertThat(sixth.getCreatedAt()).isNotNull();
    }
}
