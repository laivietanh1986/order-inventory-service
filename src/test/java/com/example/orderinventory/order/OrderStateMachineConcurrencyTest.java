package com.example.orderinventory.order;

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

import static org.assertj.core.api.Assertions.assertThat;

/**
 * State machine an toan duoi concurrency (muc 21), tren PostgreSQL that qua
 * Testcontainers. Khong dung @Transactional o class/method test - ly do
 * giong het muc 17.
 *
 * Chay: mvn test -Dtest=OrderStateMachineConcurrencyTest
 */
@SpringBootTest
@Testcontainers
class OrderStateMachineConcurrencyTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    private OrderRepository orderRepository;

    @Autowired
    private OrderStatusHistoryRepository orderStatusHistoryRepository;

    @Autowired
    private NaiveOrderStateMachineService naiveOrderStateMachineService;

    @Autowired
    private OrderStateMachineService orderStateMachineService;

    @AfterEach
    void cleanUpManually() {
        orderStatusHistoryRepository.deleteAll();
        orderRepository.deleteAll();
    }

    private Long seedCreatedOrder() {
        return orderRepository.save(new Order("State Machine Customer", "CREATED")).getId();
    }

    /**
     * BUG: confirm va cancel chay dong thoi tren CUNG mot order dang
     * CREATED, ca hai bi ep doc CUNG gia tri CREATED truoc khi ben nao kip
     * ghi (giong ky thuat CountDownLatch da dung o muc 17-19) - ca hai deu
     * "thay" minh duoc phep chuyen trang thai, ca hai deu ghi 1 dong lich
     * su -> status_history co 2 dong cho MOT don hang le ra chi duoc phep
     * chuyen trang thai DUNG MOT LAN.
     */
    @Test
    void check_then_act_lam_ca_confirm_va_cancel_cung_thanh_cong() throws Exception {
        Long orderId = seedCreatedOrder();

        int threadCount = 2;
        CountDownLatch bothThreadsHaveRead = new CountDownLatch(threadCount);
        Runnable afterRead = () -> {
            bothThreadsHaveRead.countDown();
            try {
                assertThat(bothThreadsHaveRead.await(5, TimeUnit.SECONDS)).isTrue();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            }
        };

        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        boolean confirmSucceeded;
        boolean cancelSucceeded;
        try {
            Future<Boolean> confirmFuture = executor.submit(() ->
                    naiveOrderStateMachineService.confirm(orderId, afterRead));
            Future<Boolean> cancelFuture = executor.submit(() ->
                    naiveOrderStateMachineService.cancel(orderId, afterRead));

            confirmSucceeded = confirmFuture.get(10, TimeUnit.SECONDS);
            cancelSucceeded = cancelFuture.get(10, TimeUnit.SECONDS);
        } finally {
            executor.shutdown();
        }

        long historyCount = orderStatusHistoryRepository.countByOrderId(orderId);
        String finalStatus = orderRepository.findStatusById(orderId);
        System.out.println("[LESSON21-NAIVE] confirm thanh cong=" + confirmSucceeded
                + ", cancel thanh cong=" + cancelSucceeded
                + ", trang thai cuoi=" + finalStatus + ", so dong status_history=" + historyCount);

        // Day chinh la cai bay: CA HAI deu bao "thanh cong" - hai hanh dong
        // mau thuan nhau (confirm vs cancel) khong the nao cung dung duoc.
        assertThat(confirmSucceeded).isTrue();
        assertThat(cancelSucceeded).isTrue();
        assertThat(historyCount).isEqualTo(2);
    }

    /**
     * FIX: dieu kien nam trong UPDATE ... WHERE status = 'CREATED'. Chay
     * dong thoi confirm/cancel tren CUNG order dang CREATED: Postgres tu
     * khoa row trong luc UPDATE chay, nen dung 1 trong 2 lenh THUC SU khop
     * dieu kien - lenh con lai chac chan thay status da doi khi no chay toi
     * (bat ke thu tu nao), affected rows = 0.
     */
    @Test
    void update_where_status_dam_bao_chi_dung_1_trong_2_thanh_cong() throws Exception {
        Long orderId = seedCreatedOrder();

        ExecutorService executor = Executors.newFixedThreadPool(2);
        boolean confirmSucceeded;
        boolean cancelSucceeded;
        try {
            List<Future<Boolean>> futures = List.of(
                    executor.submit(() -> orderStateMachineService.confirm(orderId)),
                    executor.submit(() -> orderStateMachineService.cancel(orderId)));
            confirmSucceeded = futures.get(0).get(10, TimeUnit.SECONDS);
            cancelSucceeded = futures.get(1).get(10, TimeUnit.SECONDS);
        } finally {
            executor.shutdown();
        }

        long historyCount = orderStatusHistoryRepository.countByOrderId(orderId);
        String finalStatus = orderRepository.findStatusById(orderId);
        System.out.println("[LESSON21-FIXED] confirm tra ve=" + confirmSucceeded
                + ", cancel tra ve=" + cancelSucceeded
                + ", trang thai cuoi=" + finalStatus + ", so dong status_history=" + historyCount);

        assertThat(confirmSucceeded ^ cancelSucceeded).as("dung 1 trong 2 phai thanh cong (XOR)").isTrue();
        assertThat(historyCount).isEqualTo(1);
        assertThat(finalStatus).isIn("CONFIRMED", "CANCELLED");
        assertThat(confirmSucceeded ? "CONFIRMED" : "CANCELLED").isEqualTo(finalStatus);
    }

    /**
     * Idempotency: goi cancel() 2 LAN LIEN TIEP (khong dong thoi) tren cung
     * mot order phai cho CUNG mot ket qua (true ca hai lan - "order dang
     * CANCELLED" van dung du lan goi thu hai khong thuc su thuc hien buoc
     * chuyen nao ca), va KHONG duoc tao them dong lich su nao o lan goi thu
     * hai.
     */
    @Test
    void goi_cancel_hai_lan_cho_cung_ket_qua_va_khong_nhan_doi_lich_su() {
        Long orderId = seedCreatedOrder();

        boolean firstCallResult = orderStateMachineService.cancel(orderId);
        boolean secondCallResult = orderStateMachineService.cancel(orderId);

        long historyCount = orderStatusHistoryRepository.countByOrderId(orderId);
        System.out.println("[LESSON21-IDEMPOTENCY] lan 1=" + firstCallResult + ", lan 2=" + secondCallResult
                + ", so dong status_history=" + historyCount);

        assertThat(firstCallResult).isTrue();
        assertThat(secondCallResult).isTrue(); // CUNG ket qua nhu lan 1, du khong lam gi them
        assertThat(historyCount).isEqualTo(1); // KHONG nhan doi
        assertThat(orderRepository.findStatusById(orderId)).isEqualTo("CANCELLED");
    }

    /** So sanh: cancel() tren mot order DA CONFIRMED (trang thai khac, khong phai idempotent-no-op) phai that bai. */
    @Test
    void cancel_mot_order_da_confirmed_that_bai_khong_phai_la_no_op_idempotent() {
        Long orderId = seedCreatedOrder();
        assertThat(orderStateMachineService.confirm(orderId)).isTrue();

        boolean cancelResult = orderStateMachineService.cancel(orderId);

        System.out.println("[LESSON21-IDEMPOTENCY] cancel tren order da CONFIRMED tra ve=" + cancelResult);
        assertThat(cancelResult).isFalse();
        assertThat(orderRepository.findStatusById(orderId)).isEqualTo("CONFIRMED");
        assertThat(orderStatusHistoryRepository.countByOrderId(orderId)).isEqualTo(1); // chi 1 dong tu confirm()
    }
}
