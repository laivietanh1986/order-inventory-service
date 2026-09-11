package com.example.orderinventory.order;

import org.springframework.data.jpa.repository.JpaRepository;

public interface OrderStatusHistoryRepository extends JpaRepository<OrderStatusHistory, Long> {

    long countByOrderId(Long orderId);
}
