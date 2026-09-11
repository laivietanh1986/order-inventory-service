package com.example.orderinventory.order;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Ban SAI co chu dich (muc 21): check-then-act o tang application -
 * {@code if (order.getStatus() == CREATED) { ... }}. Doc trang thai, KIEM
 * TRA no trong bo nho JVM, roi moi ghi - khoang thoi gian giua "kiem tra" va
 * "ghi" khong duoc bao ve boi bat ky lock nao, nen hai lenh goi doc lap
 * (confirm va cancel) co the cung doc duoc CREATED va cung nghi minh duoc
 * phep chuyen trang thai.
 */
@Service
public class NaiveOrderStateMachineService {

    private final OrderRepository orderRepository;
    private final OrderStatusHistoryRepository orderStatusHistoryRepository;

    public NaiveOrderStateMachineService(OrderRepository orderRepository,
            OrderStatusHistoryRepository orderStatusHistoryRepository) {
        this.orderRepository = orderRepository;
        this.orderStatusHistoryRepository = orderStatusHistoryRepository;
    }

    @Transactional
    public boolean confirm(Long orderId) {
        return confirm(orderId, () -> { });
    }

    /** afterRead chay ngay sau khi doc status, TRUOC khi kiem tra/ghi - diem test dung de ep dong bo hoa. */
    @Transactional
    public boolean confirm(Long orderId, Runnable afterRead) {
        return transitionIfCreated(orderId, "CONFIRMED", afterRead);
    }

    @Transactional
    public boolean cancel(Long orderId) {
        return cancel(orderId, () -> { });
    }

    @Transactional
    public boolean cancel(Long orderId, Runnable afterRead) {
        return transitionIfCreated(orderId, "CANCELLED", afterRead);
    }

    private boolean transitionIfCreated(Long orderId, String newStatus, Runnable afterRead) {
        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new IllegalArgumentException("Order not found: " + orderId));

        boolean canTransition = "CREATED".equals(order.getStatus()); // "kiem tra" - trong bo nho, khong lock
        afterRead.run();

        if (!canTransition) {
            return false;
        }
        order.setStatus(newStatus);
        orderRepository.save(order);

        OrderStatusHistory history = new OrderStatusHistory(newStatus);
        history.setOrder(order);
        orderStatusHistoryRepository.save(history);
        return true;
    }
}
