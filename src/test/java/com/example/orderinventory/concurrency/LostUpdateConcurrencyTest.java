package com.example.orderinventory.concurrency;

import com.example.orderinventory.inventory.Inventory;
import com.example.orderinventory.inventory.InventoryRepository;
import com.example.orderinventory.product.Product;
import com.example.orderinventory.product.ProductRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tai hien lost update (muc 17), tren PostgreSQL that qua Testcontainers, o
 * isolation level READ COMMITTED mac dinh cua Postgres.
 *
 * ⚠️ KHONG duoc dat @Transactional len class/method test: neu co, Spring se
 * goi ca test vao MOT transaction, hai thread se dung CHUNG mot connection
 * (hoac tranh nhau connection roi deadlock/timeout voi chinh no), va rot cuoc
 * "concurrency test" nay se pass gia tao du bug van con nguyen. Vi vay du
 * lieu duoc don thu cong o @AfterEach thay vi dua vao rollback.
 *
 * Kich ban: 2 thread cung goi NaiveInventoryReservationService.decrementQuantity
 * tren MOT dong inventory dang co quantityOnHand = 1. Ca hai deu doc duoc gia
 * tri 1 (chua thread nao commit), roi CUNG tinh 1 - 1 = 0 va save. Ket qua
 * dung ra phai la 1 - 1 - 1 = -1 (hai lan tru thanh cong), nhung READ
 * COMMITTED khong ngan duoc kieu doc-tinh-ghi nay: mot trong hai lan tru bi
 * MAT, ket qua cuoi cung la 0, va KHONG co exception nao bao hieu - ca hai
 * lenh save deu "thanh cong" binh thuong.
 *
 * De bug nay xay ra CHAC CHAN moi lan chay (khong phu thuoc may man cua
 * thread scheduler), test dung mot CountDownLatch thu hai ben trong service
 * (tham so afterRead) de ep CA HAI thread phai doc xong gia tri cu TRUOC KHI
 * ben nao kip tinh gia tri moi va ghi - dung it hon se co the (nhung khong
 * chac chan) khong tai hien duoc race condition.
 *
 * Chay: mvn test -Dtest=LostUpdateConcurrencyTest
 */
@SpringBootTest
@Testcontainers
class LostUpdateConcurrencyTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    private ProductRepository productRepository;

    @Autowired
    private InventoryRepository inventoryRepository;

    @Autowired
    private NaiveInventoryReservationService naiveInventoryReservationService;

    private Long inventoryId;

    @BeforeEach
    void seedProductWithOneUnitInStock() {
        Product product = productRepository.save(
                new Product("Lost Update Widget", "LU-WIDGET-1", 1, BigDecimal.TEN));
        Inventory inventory = inventoryRepository.save(new Inventory(product, 1));
        inventoryId = inventory.getId();
    }

    @AfterEach
    void cleanUpManually() {
        inventoryRepository.deleteAll();
        productRepository.deleteAll();
    }

    @Test
    void hai_thread_cung_doc_cung_tru_cung_save_lam_mat_mot_lan_cap_nhat() throws Exception {
        int threadCount = 2;
        CountDownLatch bothThreadsHaveRead = new CountDownLatch(threadCount);
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        try {
            List<Future<?>> futures = List.of(
                    executor.submit(() -> naiveInventoryReservationService.decrementQuantity(
                            inventoryId, awaitAfterRead(bothThreadsHaveRead))),
                    executor.submit(() -> naiveInventoryReservationService.decrementQuantity(
                            inventoryId, awaitAfterRead(bothThreadsHaveRead))));

            // Ca hai save deu phai hoan tat KHONG NEM exception - day chinh la
            // diem nguy hiem cua lost update: khong co OptimisticLockException,
            // khong co loi nao het, chi la SAI AM THAM.
            for (Future<?> future : futures) {
                future.get(10, TimeUnit.SECONDS);
            }
        } finally {
            executor.shutdown();
        }

        Inventory reloaded = inventoryRepository.findById(inventoryId).orElseThrow();
        System.out.println("[LESSON17] quantityOnHand sau 2 lan tru tu 1 = " + reloaded.getQuantityOnHand()
                + " (dung ra phai la -1 neu khong mat cap nhat)");

        // Day la ASSERTION CHUNG MINH BUG: neu code duoc sua dung (optimistic
        // lock, pessimistic lock, hoac atomic UPDATE ... WHERE), assertion
        // nay se FAIL vi ket qua dung phai la -1 - luc do phai doi lai bai
        // test cho muc 18, khong phai xoa test nay.
        assertThat(reloaded.getQuantityOnHand()).isEqualTo(0);
    }

    private static Runnable awaitAfterRead(CountDownLatch bothThreadsHaveRead) {
        return () -> {
            bothThreadsHaveRead.countDown();
            try {
                boolean bothArrived = bothThreadsHaveRead.await(5, TimeUnit.SECONDS);
                assertThat(bothArrived).as("ca hai thread phai doc xong truoc khi ben nao ghi").isTrue();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            }
        };
    }
}
