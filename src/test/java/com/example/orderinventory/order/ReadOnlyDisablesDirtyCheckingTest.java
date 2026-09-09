package com.example.orderinventory.order;

import org.hibernate.Session;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;

import jakarta.persistence.EntityManagerFactory;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * @Transactional(readOnly = true) (o muc do Hibernate: Session.setDefaultReadOnly)
 * tat dirty checking: entity load trong che do nay KHONG con duoc luu snapshot
 * de so sanh, nen sua field tren no roi flush KHONG con sinh UPDATE - doi lap
 * hoan toan voi hanh vi da thay o muc 1.
 * Chay: mvn test -Dtest=ReadOnlyDisablesDirtyCheckingTest
 */
@DataJpaTest
class ReadOnlyDisablesDirtyCheckingTest {

    @Autowired
    private TestEntityManager entityManager;

    @Autowired
    private EntityManagerFactory entityManagerFactory;

    private Statistics statistics() {
        return entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
    }

    @Test
    void mac_dinh_sua_field_tren_managed_entity_van_sinh_UPDATE_nhu_muc_1() {
        Order order = new Order("Nguyen Van A", "CREATED");
        entityManager.persist(order);
        entityManager.flush();
        entityManager.clear();

        statistics().clear();
        Order loaded = entityManager.find(Order.class, order.getId());
        loaded.setStatus("CONFIRMED"); // khong goi save()

        entityManager.flush();

        // Dung nhu muc 1: dirty checking mac dinh phat hien thay doi va tu
        // sinh UPDATE, khong can goi save()/saveAndFlush().
        assertThat(statistics().getEntityUpdateCount()).isEqualTo(1);
    }

    @Test
    void readOnly_tat_dirty_checking_sua_field_khong_con_sinh_UPDATE() {
        Order order = new Order("Tran Thi B", "CREATED");
        entityManager.persist(order);
        entityManager.flush();
        entityManager.clear();

        Session session = entityManager.getEntityManager().unwrap(Session.class);
        // Tuong duong hieu ung ma Spring tao ra khi vao mot method
        // @Transactional(readOnly = true): entity nap SAU loi goi nay se
        // khong con duoc luu snapshot lam co so cho dirty checking.
        session.setDefaultReadOnly(true);

        statistics().clear();
        Order loaded = entityManager.find(Order.class, order.getId());
        loaded.setStatus("CONFIRMED"); // van sua field, khong goi save()

        entityManager.flush();

        // Khong co snapshot -> khong co gi de so sanh -> khong UPDATE nao
        // duoc sinh ra, du field da bi sua tren mot entity dang managed.
        assertThat(statistics().getEntityUpdateCount()).isZero();

        session.setDefaultReadOnly(false); // tra ve mac dinh, tranh anh huong test khac

        entityManager.clear();
        Order reloaded = entityManager.find(Order.class, order.getId());
        assertThat(reloaded.getStatus()).isEqualTo("CREATED"); // van la gia tri cu
    }
}
