package com.example.orderinventory.concurrency;

import com.example.orderinventory.inventory.Inventory;
import com.example.orderinventory.inventory.InventoryRepository;
import com.example.orderinventory.product.Product;
import com.example.orderinventory.product.ProductRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Fix 3/3 cho lost update (muc 18): mot cau UPDATE atomic duy nhat voi dieu
 * kien "con du hang" NGAY TRONG WHERE, kiem tra bang affected rows. Khong
 * dung @Transactional o class/method test - ly do giong het muc 17.
 *
 * Day la cach DON GIAN NHAT trong 3 cach: khong entity, khong @Version,
 * khong SELECT ... FOR UPDATE tuong minh, khong retry loop - chi mot cau SQL
 * va mot con so (affected rows).
 *
 * Chay: mvn test -Dtest=AtomicConditionalUpdateFixTest
 */
@SpringBootTest
@Testcontainers
class AtomicConditionalUpdateFixTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    private ProductRepository productRepository;

    @Autowired
    private InventoryRepository inventoryRepository;

    @Autowired
    private AtomicUpdateInventoryReservationService atomicUpdateInventoryReservationService;

    @AfterEach
    void cleanUpManually() {
        inventoryRepository.deleteAll();
        productRepository.deleteAll();
    }

    private Long seedInventory(int initialQuantity) {
        Product product = productRepository.save(
                new Product("Atomic Widget", "ATM-WIDGET-" + initialQuantity, initialQuantity, BigDecimal.TEN));
        return inventoryRepository.save(new Inventory(product, initialQuantity)).getId();
    }

    /**
     * Chi con 1 don vi ton kho, 2 thread cung tranh nhau: DUNG 1 thread nhan
     * affected rows = 1 (thanh cong), thread con lai nhan 0 (het hang tai
     * thoi diem UPDATE cua no chay - vi cua thread kia da chay va commit
     * truoc). KHONG co exception nao ca - affected rows la tin hieu du.
     */
    @Test
    void chi_dung_1_trong_2_thread_thanh_cong_khi_ton_kho_chi_con_1() throws Exception {
        Long inventoryId = seedInventory(1);

        ExecutorService executor = Executors.newFixedThreadPool(2);
        boolean resultThread1;
        boolean resultThread2;
        try {
            List<Future<Boolean>> futures = List.of(
                    executor.submit(() -> atomicUpdateInventoryReservationService.tryDecrement(inventoryId, 1)),
                    executor.submit(() -> atomicUpdateInventoryReservationService.tryDecrement(inventoryId, 1)));
            resultThread1 = futures.get(0).get(10, TimeUnit.SECONDS);
            resultThread2 = futures.get(1).get(10, TimeUnit.SECONDS);
        } finally {
            executor.shutdown();
        }

        System.out.println("[LESSON18-ATOMIC] thread1 thanh cong=" + resultThread1
                + ", thread2 thanh cong=" + resultThread2);

        assertThat(resultThread1 ^ resultThread2).as("dung 1 trong 2 phai thanh cong (XOR)").isTrue();

        Inventory reloaded = inventoryRepository.findById(inventoryId).orElseThrow();
        assertThat(reloaded.getQuantityOnHand()).isEqualTo(0);
    }

    /**
     * 50 thread, moi thread tru DUNG 1 tu ton kho ban dau = 50 - khong can
     * @Version, khong can retry: MOI thread deu thanh cong ngay lan goi DAU
     * TIEN (khong co khai niem "lan thu lai" o cach nay), vi dieu kien du
     * hang nam trong chinh cau UPDATE nen khong bao gio "doc gia tri cu roi
     * ghi de" nhu muc 17.
     */
    @Test
    void ca_50_thread_deu_thanh_cong_khong_can_retry_khi_du_hang() throws Exception {
        int threadCount = 50;
        Long inventoryId = seedInventory(threadCount);

        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        long successCount;
        try {
            List<Future<Boolean>> futures = new ArrayList<>();
            for (int i = 0; i < threadCount; i++) {
                futures.add(executor.submit(() ->
                        atomicUpdateInventoryReservationService.tryDecrement(inventoryId, 1)));
            }
            successCount = 0;
            for (Future<Boolean> future : futures) {
                if (future.get(10, TimeUnit.SECONDS)) {
                    successCount++;
                }
            }
        } finally {
            executor.shutdown();
        }

        System.out.println("[LESSON18-ATOMIC] " + threadCount + " thread, so lan thanh cong = " + successCount);
        assertThat(successCount).isEqualTo(threadCount);

        Inventory reloaded = inventoryRepository.findById(inventoryId).orElseThrow();
        assertThat(reloaded.getQuantityOnHand()).isEqualTo(0);
    }
}
