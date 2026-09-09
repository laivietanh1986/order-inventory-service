package com.example.orderinventory.order;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Aggregate query (COUNT/SUM/MAX) so voi load ca danh sach roi
 * stream().reduce() o tang Java. Seed 1 khach hang voi 50.000 order (dung
 * JdbcTemplate batch insert de seed nhanh - KHONG di qua Hibernate, vi ban
 * than viec seed khong phai noi dung bai hoc nay).
 * Chay: mvn test -Dtest=CustomerOrderSummaryTest
 */
@DataJpaTest
class CustomerOrderSummaryTest {

    private static final String CUSTOMER_NAME = "Bulk Customer";
    private static final int ORDER_COUNT = 50_000;

    @Autowired
    private OrderRepository orderRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void seed50000OrdersForOneCustomer() {
        List<Object[]> batchArgs = new ArrayList<>(ORDER_COUNT);
        for (int i = 1; i <= ORDER_COUNT; i++) {
            batchArgs.add(new Object[]{CUSTOMER_NAME, "CREATED", BigDecimal.valueOf(i)});
        }
        jdbcTemplate.batchUpdate(
                "INSERT INTO orders (customer_name, status, total_amount) VALUES (?, ?, ?)",
                batchArgs);
    }

    @Test
    void aggregate_query_tra_ve_dung_1_dong_thay_vi_nap_ca_50000_order() {
        long start = System.nanoTime();
        CustomerOrderSummaryDto summary = orderRepository.findCustomerOrderSummary(CUSTOMER_NAME);
        long aggregateDurationNanos = System.nanoTime() - start;

        assertThat(summary.getOrderCount()).isEqualTo(ORDER_COUNT);
        // tong 1 + 2 + ... + 50000 = 50000 * 50001 / 2
        assertThat(summary.getTotalAmount()).isEqualByComparingTo(BigDecimal.valueOf(50_000L * 50_001L / 2));
        assertThat(summary.getMaxOrderAmount()).isEqualByComparingTo(BigDecimal.valueOf(ORDER_COUNT));

        long startLoadAll = System.nanoTime();
        List<Order> allOrders = orderRepository.findByCustomerName(CUSTOMER_NAME);
        long countViaJava = allOrders.size();
        BigDecimal totalViaJava = allOrders.stream()
                .map(Order::getTotalAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal maxViaJava = allOrders.stream()
                .map(Order::getTotalAmount)
                .max(BigDecimal::compareTo)
                .orElse(BigDecimal.ZERO);
        long loadAllDurationNanos = System.nanoTime() - startLoadAll;

        assertThat(countViaJava).isEqualTo(ORDER_COUNT);
        assertThat(totalViaJava).isEqualByComparingTo(summary.getTotalAmount());
        assertThat(maxViaJava).isEqualByComparingTo(summary.getMaxOrderAmount());

        // Aggregate query (1 dong ket qua) phai nhanh hon han cach nap toan bo
        // 50.000 row roi tinh o tang Java - chenh lech phai o muc do bac,
        // khong phai vai phan tram (moi truong CI/may that co the dao dong,
        // nen chi doi hoi nhanh hon RO RET, khong ap mot ty le cu the).
        assertThat(aggregateDurationNanos).isLessThan(loadAllDurationNanos);
    }
}
