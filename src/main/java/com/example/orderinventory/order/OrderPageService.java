package com.example.orderinventory.order;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.List;

/**
 * Two-query pattern: tach rieng "phan trang" (chi lam viec voi id, an toan de
 * dung LIMIT/OFFSET o tang SQL) khoi "nap day du graph" (JOIN FETCH nhung
 * khong con Pageable nen khong kich hoat canh bao in-memory pagination).
 */
@Service
public class OrderPageService {

    private final OrderRepository orderRepository;

    public OrderPageService(OrderRepository orderRepository) {
        this.orderRepository = orderRepository;
    }

    public Page<Order> findOrdersWithItemsPaged(Pageable pageable) {
        Page<Long> idPage = orderRepository.findOrderIdsPaged(pageable); // query 1: chi id
        List<Long> ids = idPage.getContent();

        List<Order> orders = orderRepository.findAllWithItemsByIdIn(ids); // query 2: JOIN FETCH theo id
        orders.sort(Comparator.comparing(order -> ids.indexOf(order.getId()))); // IN khong dam bao giu thu tu

        return new PageImpl<>(orders, pageable, idPage.getTotalElements());
    }
}
