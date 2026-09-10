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
import org.springframework.dao.PessimisticLockingFailureException;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * Fix 2/3 cho lost update (muc 18): pessimistic locking ({@code SELECT ...
 * FOR UPDATE}), cong voi hai bien the NOWAIT va SKIP LOCKED. Khong dung
 * @Transactional o class/method test - ly do giong het muc 17.
 *
 * Chay: mvn test -Dtest=PessimisticLockingFixTest
 */
@SpringBootTest
@Testcontainers
class PessimisticLockingFixTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    private ProductRepository productRepository;

    @Autowired
    private InventoryRepository inventoryRepository;

    @Autowired
    private PessimisticInventoryReservationService pessimisticInventoryReservationService;

    @AfterEach
    void cleanUpManually() {
        inventoryRepository.deleteAll();
        productRepository.deleteAll();
    }

    private static final java.util.concurrent.atomic.AtomicInteger SKU_SEQUENCE = new java.util.concurrent.atomic.AtomicInteger();

    private Long seedInventory(int initialQuantity) {
        String sku = "PES-WIDGET-" + initialQuantity + "-" + SKU_SEQUENCE.incrementAndGet();
        Product product = productRepository.save(new Product("Pessimistic Widget", sku, initialQuantity, BigDecimal.TEN));
        return inventoryRepository.save(new Inventory(product, initialQuantity)).getId();
    }

    /**
     * Khong can latch dac biet: transaction thu hai TU NHIEN bi chan ngay o
     * cau SELECT ... FOR UPDATE cho den khi transaction thu nhat commit, nen
     * luon doc duoc gia tri MOI NHAT - khong bao gio doc phai gia tri "cu"
     * nhu kieu naive o muc 17.
     */
    @Test
    void hai_thread_cung_tru_qua_pessimistic_lock_khong_mat_cap_nhat_nao() throws Exception {
        Long inventoryId = seedInventory(1);

        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            List<Future<?>> futures = List.of(
                    executor.submit(() -> pessimisticInventoryReservationService.decrement(inventoryId)),
                    executor.submit(() -> pessimisticInventoryReservationService.decrement(inventoryId)));
            for (Future<?> future : futures) {
                future.get(10, TimeUnit.SECONDS);
            }
        } finally {
            executor.shutdown();
        }

        Inventory reloaded = inventoryRepository.findById(inventoryId).orElseThrow();
        assertThat(reloaded.getQuantityOnHand()).isEqualTo(-1);
    }

    /**
     * "Do throughput giam": tren MOT row dang bi tranh chap, viec them
     * thread khong lam nhanh hon - vi lock la EXCLUSIVE tren dung 1 row, moi
     * thread phai xep hang cho nhau, nen tong thoi gian cho N thao tac gan
     * nhu KHONG DOI du chay tuan tu (1 thread) hay chay dong thoi (nhieu
     * thread): thong luong (ops/giay) khong the vuot xa muc "1 / thoi gian
     * giu lock trung binh", bat ke thread pool lon co nao.
     */
    @Test
    void them_thread_khong_lam_tang_throughput_tren_mot_row_bi_khoa() throws Exception {
        int operationCount = 20;

        Long sequentialInventoryId = seedInventory(operationCount);
        long sequentialStart = System.nanoTime();
        for (int i = 0; i < operationCount; i++) {
            pessimisticInventoryReservationService.decrement(sequentialInventoryId);
        }
        long sequentialMs = (System.nanoTime() - sequentialStart) / 1_000_000;

        Long concurrentInventoryId = seedInventory(operationCount);
        ExecutorService executor = Executors.newFixedThreadPool(8);
        long concurrentMs;
        try {
            List<Future<?>> futures = new java.util.ArrayList<>();
            long concurrentStart = System.nanoTime();
            for (int i = 0; i < operationCount; i++) {
                futures.add(executor.submit(() ->
                        pessimisticInventoryReservationService.decrement(concurrentInventoryId)));
            }
            for (Future<?> future : futures) {
                future.get(20, TimeUnit.SECONDS);
            }
            concurrentMs = (System.nanoTime() - concurrentStart) / 1_000_000;
        } finally {
            executor.shutdown();
        }

        double sequentialOpsPerSec = operationCount / (sequentialMs / 1000.0);
        double concurrentOpsPerSec = operationCount / (concurrentMs / 1000.0);

        System.out.println("[LESSON18-PESSIMISTIC] " + operationCount + " thao tac TUAN TU: " + sequentialMs
                + " ms (" + String.format("%.1f", sequentialOpsPerSec) + " ops/s)");
        System.out.println("[LESSON18-PESSIMISTIC] " + operationCount + " thao tac qua 8 thread DONG THOI tren"
                + " CUNG 1 row: " + concurrentMs + " ms (" + String.format("%.1f", concurrentOpsPerSec) + " ops/s)");

        // Bien gioi rong (2x) de tranh flaky do overhead JVM/connection pool,
        // nhung du chung minh: 8 thread KHONG lam thong luong tang vot, vi
        // lock tren MOT row tuan tu hoa moi thao tac ghi bat ke so thread.
        assertThat(concurrentOpsPerSec).isLessThan(sequentialOpsPerSec * 2);

        assertThat(inventoryRepository.findById(sequentialInventoryId).orElseThrow().getQuantityOnHand())
                .isEqualTo(0);
        assertThat(inventoryRepository.findById(concurrentInventoryId).orElseThrow().getQuantityOnHand())
                .isEqualTo(0);
    }

    /**
     * NOWAIT: thread A giu lock (thong qua holdLockUntilReleased), thread B
     * goi decrementNoWait tren CUNG row phai nhan loi NGAY (khong cho) -
     * PessimisticLockingFailureException (Spring dich tu loi Postgres "could
     * not obtain lock").
     */
    @Test
    void nowait_bao_loi_ngay_thay_vi_cho_khi_row_dang_bi_khoa() throws Exception {
        Long inventoryId = seedInventory(5);

        CountDownLatch lockAcquiredByA = new CountDownLatch(1);
        CountDownLatch releaseA = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<?> threadA = executor.submit(() -> {
                try {
                    pessimisticInventoryReservationService.holdLockUntilReleased(
                            inventoryId, lockAcquiredByA::countDown, releaseA);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });

            assertThat(lockAcquiredByA.await(5, TimeUnit.SECONDS)).isTrue();

            Future<?> threadB = executor.submit(() ->
                    pessimisticInventoryReservationService.decrementNoWait(inventoryId));

            Throwable thrownByB = catchThrowable(() -> threadB.get(5, TimeUnit.SECONDS));
            System.out.println("[LESSON18-PESSIMISTIC] NOWAIT that bai NGAY voi: "
                    + (thrownByB == null ? null : thrownByB.getCause()));

            assertThat(thrownByB).isNotNull();
            assertThat(thrownByB.getCause()).isInstanceOf(PessimisticLockingFailureException.class);

            releaseA.countDown();
            threadA.get(5, TimeUnit.SECONDS);
        } finally {
            executor.shutdown();
        }
    }

    /**
     * SKIP LOCKED: thread A giu lock, thread B goi decrementSkipLocked tren
     * CUNG row phai tra ve rong NGAY (khong cho, khong loi) - day la chia
     * khoa cho job queue pattern: nhieu worker tu dong "nhuong" row dang bi
     * ai do xu ly cho nhau.
     */
    @Test
    void skip_locked_tra_ve_rong_thay_vi_cho_khi_row_dang_bi_khoa() throws Exception {
        Long inventoryId = seedInventory(5);

        CountDownLatch lockAcquiredByA = new CountDownLatch(1);
        CountDownLatch releaseA = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<?> threadA = executor.submit(() -> {
                try {
                    pessimisticInventoryReservationService.holdLockUntilReleased(
                            inventoryId, lockAcquiredByA::countDown, releaseA);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });

            assertThat(lockAcquiredByA.await(5, TimeUnit.SECONDS)).isTrue();

            Future<Optional<Long>> threadB = executor.submit(() ->
                    pessimisticInventoryReservationService.decrementSkipLocked(inventoryId));

            Optional<Long> resultB = threadB.get(5, TimeUnit.SECONDS);
            System.out.println("[LESSON18-PESSIMISTIC] SKIP LOCKED tra ve: " + resultB);
            assertThat(resultB).isEmpty();

            releaseA.countDown();
            threadA.get(5, TimeUnit.SECONDS);
        } finally {
            executor.shutdown();
        }

        // Row khong bi B dung toi, A khong tru gi ca (chi giu lock roi nha) -
        // quantity van nguyen ven.
        assertThat(inventoryRepository.findById(inventoryId).orElseThrow().getQuantityOnHand()).isEqualTo(5);
    }
}
