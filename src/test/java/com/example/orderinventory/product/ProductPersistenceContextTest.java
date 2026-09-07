package com.example.orderinventory.product;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Minh hoa persistence context: transient / managed / detached va dirty checking.
 * Chay: mvn test -Dtest=ProductPersistenceContextTest
 * Quan sat log Hibernate (show-sql=true) de thay cau lenh SQL thuc su duoc sinh ra.
 */
@DataJpaTest
class ProductPersistenceContextTest {

    @Autowired
    private TestEntityManager entityManager;

    @Autowired
    private ProductRepository productRepository;

    @Test
    void save_dua_entity_tu_transient_sang_managed() {
        // TRANSIENT: object Java thuan tuy, chua co id, Hibernate chua he biet den no
        Product product = new Product("Wireless Mouse", "SKU-001", 100, new BigDecimal("19.99"));
        assertThat(product.getId()).isNull();

        // save() => INSERT duoc thuc thi (hoac hoan lai den flush) va entity tro thanh MANAGED
        Product saved = productRepository.save(product);

        assertThat(saved.getId()).isNotNull();
        assertThat(entityManager.getEntityManager().contains(saved)).isTrue();
    }

    @Test
    void sua_field_tren_managed_entity_van_sinh_UPDATE_du_khong_goi_save() {
        Product product = productRepository.save(
                new Product("Mechanical Keyboard", "SKU-002", 50, new BigDecimal("89.90")));
        entityManager.flush();
        entityManager.clear(); // xoa persistence context hien tai de chung minh doan sau load lai tu DB

        // findById() => SELECT duoc chay, entity tra ve la MANAGED (duoc persistence context theo doi)
        Product loaded = productRepository.findById(product.getId()).orElseThrow();

        loaded.setQuantity(45); // KHONG goi save()/saveAndFlush() o day

        // flush() ep dong bo persistence context xuong DB ngay luc nay.
        // Vi loaded dang o trang thai managed, Hibernate tu dong "dirty checking":
        // so sanh snapshot luc load voi trang thai hien tai, phat hien khac biet
        // va tu sinh cau lenh: update products set quantity=?,... where id=?
        entityManager.flush();

        entityManager.clear();
        Product reloaded = productRepository.findById(product.getId()).orElseThrow();
        assertThat(reloaded.getQuantity()).isEqualTo(45);
    }

    @Test
    void sua_field_tren_detached_entity_khong_sinh_UPDATE() {
        Product product = productRepository.save(
                new Product("USB-C Cable", "SKU-003", 200, new BigDecimal("5.50")));
        entityManager.flush();

        // detach(): entity roi khoi persistence context => tro thanh DETACHED
        entityManager.detach(product);
        product.setQuantity(999); // thay doi tren mot object da detached

        // flush() luc nay khong lam gi voi "product" vi no khong con duoc theo doi nua
        entityManager.flush();

        entityManager.clear();
        Product reloaded = productRepository.findById(product.getId()).orElseThrow();

        // Gia tri trong DB van la 200, thay doi tren detached entity da bi "mat"
        assertThat(reloaded.getQuantity()).isEqualTo(200);
    }
}
