package com.example.orderinventory.concurrency;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Fix 1/3 cho lost update (muc 18): optimistic locking bang @Version + retry
 * loop. Khong dung @Transactional o class/method test - ly do giong het muc
 * 17 (xem LostUpdateConcurrencyTest).
 *
 * Chay: mvn test -Dtest=OptimisticLockingFixTest
 */
@SpringBootTest
@Testcontainers
class OptimisticLockingFixTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    private OptimisticInventoryRepository optimisticInventoryRepository;

    @Autowired
    private OptimisticInventoryReservationService optimisticInventoryReservationService;

    @AfterEach
    void cleanUpManually() {
        optimisticInventoryRepository.deleteAll();
    }

    private Long seedInventory(int initialQuantity) {
        return optimisticInventoryRepository.save(new OptimisticInventory(initialQuantity)).getId();
    }

    /**
     * Deterministic: ca hai thread bi ep doc CUNG mot version (0) truoc khi
     * ben nao kip ghi, nen chac chan MOT trong hai va phai
     * OptimisticLockingFailureException ngay lan thu dau - khac han muc 17,
     * o day loi duoc BAO RO RANG thay vi am tham mat cap nhat.
     */
    @Test
    void mot_trong_hai_thread_bi_optimistic_lock_exception_va_phai_retry() throws Exception {
        Long inventoryId = seedInventory(1);

        int threadCount = 2;
        CountDownLatch bothThreadsHaveRead = new CountDownLatch(threadCount);
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        try {
            Runnable afterRead = () -> {
                bothThreadsHaveRead.countDown();
                try {
                    boolean bothArrived = bothThreadsHaveRead.await(5, TimeUnit.SECONDS);
                    assertThat(bothArrived).isTrue();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(e);
                }
            };

            List<Future<Integer>> futures = List.of(
                    executor.submit(() -> optimisticInventoryReservationService
                            .decrementWithRetry(inventoryId, 10, afterRead)),
                    executor.submit(() -> optimisticInventoryReservationService
                            .decrementWithRetry(inventoryId, 10, afterRead)));

            int attemptsThread1 = futures.get(0).get(10, TimeUnit.SECONDS);
            int attemptsThread2 = futures.get(1).get(10, TimeUnit.SECONDS);

            System.out.println("[LESSON18-OPTIMISTIC] thread1 attempts=" + attemptsThread1
                    + ", thread2 attempts=" + attemptsThread2);

            // Khong the biet truoc thread nao thang, nhung CHAC CHAN dung 1
            // trong 2 phai retry (attempts > 1) vi ca hai cung doc version 0.
            assertThat(Math.max(attemptsThread1, attemptsThread2)).isGreaterThan(1);
        } finally {
            executor.shutdown();
        }

        // Khac han muc 17 (ket qua sai am tham la 0): retry lam CA HAI lan
        // tru deu thanh cong that su, ket qua DUNG la -1.
        OptimisticInventory reloaded = optimisticInventoryRepository.findById(inventoryId).orElseThrow();
        assertThat(reloaded.getQuantityOnHand()).isEqualTo(-1);
    }

    /**
     * "Do ti le retry khi tang len 50 thread": 50 thread, moi thread tru
     * DUNG 1 lan tu ton kho ban dau = 50 - neu khong mat cap nhat nao, ket
     * qua cuoi phai la 0 va CA 50 lan goi deu thanh cong (co the sau vai lan
     * retry moi thanh cong).
     */
    @Test
    void do_ti_le_retry_khi_50_thread_cung_tranh_chap() throws Exception {
        int threadCount = 50;
        Long inventoryId = seedInventory(threadCount);

        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        AtomicInteger totalAttempts = new AtomicInteger();
        try {
            List<Future<Integer>> futures = new java.util.ArrayList<>();
            for (int i = 0; i < threadCount; i++) {
                futures.add(executor.submit(() ->
                        optimisticInventoryReservationService.decrementWithRetry(inventoryId, 50)));
            }

            int successCount = 0;
            for (Future<Integer> future : futures) {
                int attempts = future.get(20, TimeUnit.SECONDS);
                totalAttempts.addAndGet(attempts);
                successCount++;
            }

            int totalRetries = totalAttempts.get() - threadCount; // attempts=1 nghia la khong retry lan nao
            double retryRatePerThread = totalRetries / (double) threadCount;

            System.out.println("[LESSON18-OPTIMISTIC] " + threadCount + " thread, tong so lan thu = "
                    + totalAttempts.get() + ", tong so lan RETRY = " + totalRetries
                    + " (~" + String.format("%.2f", retryRatePerThread) + " retry/thread)");

            assertThat(successCount).isEqualTo(threadCount);
            // O muc tranh chap cao (50 thread cung 1 row), it nhat phai co
            // mot vai retry - neu khong test setup chua thuc su tao ap luc
            // dong thoi.
            assertThat(totalRetries).isGreaterThan(0);
        } finally {
            executor.shutdown();
        }

        OptimisticInventory reloaded = optimisticInventoryRepository.findById(inventoryId).orElseThrow();
        // Tat ca 50 lan tru deu "song sot" nho retry - khong lan nao bi mat.
        assertThat(reloaded.getQuantityOnHand()).isEqualTo(0);
    }
}
