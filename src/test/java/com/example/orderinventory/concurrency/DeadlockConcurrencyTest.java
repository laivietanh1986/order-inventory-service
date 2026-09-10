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
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * Deadlock co chu dich (muc 19), tren PostgreSQL that qua Testcontainers.
 * Khong dung @Transactional o class/method test - ly do giong het muc 17.
 *
 * Chay: mvn test -Dtest=DeadlockConcurrencyTest
 */
@SpringBootTest
@Testcontainers
class DeadlockConcurrencyTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    private ProductRepository productRepository;

    @Autowired
    private InventoryRepository inventoryRepository;

    @Autowired
    private MultiInventoryReservationService multiInventoryReservationService;

    @AfterEach
    void cleanUpManually() {
        inventoryRepository.deleteAll();
        productRepository.deleteAll();
    }

    private Long seedInventory(String sku) {
        Product product = productRepository.save(new Product("Deadlock Widget " + sku, sku, 10, BigDecimal.TEN));
        return inventoryRepository.save(new Inventory(product, 10)).getId();
    }

    /**
     * Order A khoa [id1, id2], order B khoa [id2, id1] - thu tu NGUOC NHAU.
     * afterFirstLock ep ca hai deu da giu duoc lock DAU TIEN cua minh truoc
     * khi ben nao kip xin lock thu hai, dam bao vong cho doi vong tron
     * (A cho B nha id2, B cho A nha id1) hinh thanh CHAC CHAN moi lan chay -
     * Postgres se phat hien va huy MOT trong hai bang loi "deadlock
     * detected", ben con lai hoan tat binh thuong.
     */
    @Test
    void hai_order_khoa_2_san_pham_theo_thu_tu_nguoc_nhau_gay_deadlock() throws Exception {
        Long id1 = seedInventory("DL-A");
        Long id2 = seedInventory("DL-B");

        CountDownLatch bothHaveFirstLock = new CountDownLatch(2);
        Runnable afterFirstLock = () -> {
            bothHaveFirstLock.countDown();
            try {
                assertThat(bothHaveFirstLock.await(5, TimeUnit.SECONDS)).isTrue();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            }
        };

        ExecutorService executor = Executors.newFixedThreadPool(2);
        Throwable failureA;
        Throwable failureB;
        try {
            Future<?> orderA = executor.submit(() ->
                    multiInventoryReservationService.reserveInGivenOrder(List.of(id1, id2), afterFirstLock));
            Future<?> orderB = executor.submit(() ->
                    multiInventoryReservationService.reserveInGivenOrder(List.of(id2, id1), afterFirstLock));

            failureA = catchThrowable(() -> orderA.get(15, TimeUnit.SECONDS));
            failureB = catchThrowable(() -> orderB.get(15, TimeUnit.SECONDS));
        } finally {
            executor.shutdown();
        }

        System.out.println("[LESSON19] order A that bai: " + (failureA == null ? "khong" : failureA.getCause()));
        System.out.println("[LESSON19] order B that bai: " + (failureB == null ? "khong" : failureB.getCause()));

        // DUNG 1 trong 2 that bai (Postgres chon 1 "nan nhan" de huy), ben
        // con lai hoan tat binh thuong - va thong diep loi phai nhac toi
        // deadlock, khong phai mot loai loi khac.
        boolean exactlyOneFailed = (failureA == null) ^ (failureB == null);
        assertThat(exactlyOneFailed).as("dung 1 trong 2 order phai that bai vi deadlock").isTrue();

        Throwable theFailure = failureA != null ? failureA : failureB;
        String fullMessage = messageChain(theFailure);
        assertThat(fullMessage.toLowerCase()).contains("deadlock");
    }

    /**
     * FIX: ca hai order goi reserveDeadlockSafe (tu sap xep id truoc khi
     * khoa) voi CUNG hai id nhung truyen vao theo thu tu nguoc nhau
     * ([id1,id2] va [id2,id1]) - vi ca hai deu quy ve CUNG mot thu tu khoa
     * tuyet doi, khong con canh nao "giu A cho B" doi ngoc voi "giu B cho A"
     * duoc nua. Order den sau chi CHO (block) o dung 1 diem, KHONG deadlock.
     */
    @Test
    void sap_xep_id_truoc_khi_khoa_loai_bo_hoan_toan_deadlock() throws Exception {
        Long id1 = seedInventory("DLFIX-A");
        Long id2 = seedInventory("DLFIX-B");

        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<?> orderA = executor.submit(() ->
                    multiInventoryReservationService.reserveDeadlockSafe(List.of(id1, id2)));
            Future<?> orderB = executor.submit(() ->
                    multiInventoryReservationService.reserveDeadlockSafe(List.of(id2, id1)));

            // Ca hai PHAI hoan tat khong loi trong thoi gian hop ly - neu con
            // deadlock, it nhat mot Future se nem exception o day.
            orderA.get(15, TimeUnit.SECONDS);
            orderB.get(15, TimeUnit.SECONDS);
        } finally {
            executor.shutdown();
        }

        System.out.println("[LESSON19] Ca hai order hoan tat KHONG deadlock sau khi sap xep id truoc khi khoa.");
    }

    private static String messageChain(Throwable throwable) {
        StringBuilder builder = new StringBuilder();
        Throwable current = throwable;
        while (current != null) {
            builder.append(current.getClass().getName()).append(": ").append(current.getMessage()).append(" | ");
            current = current.getCause();
        }
        return builder.toString();
    }
}
