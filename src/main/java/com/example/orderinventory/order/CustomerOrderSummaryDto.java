package com.example.orderinventory.order;

import java.math.BigDecimal;

/**
 * Ket qua cua mot aggregate query (COUNT/SUM/MAX) - luon dung 1 dong, bat ke
 * khach hang co bao nhieu order dang sau.
 */
public class CustomerOrderSummaryDto {

    private final Long orderCount;
    private final BigDecimal totalAmount;
    private final BigDecimal maxOrderAmount;

    public CustomerOrderSummaryDto(Long orderCount, BigDecimal totalAmount, BigDecimal maxOrderAmount) {
        this.orderCount = orderCount;
        this.totalAmount = totalAmount;
        this.maxOrderAmount = maxOrderAmount;
    }

    public Long getOrderCount() {
        return orderCount;
    }

    public BigDecimal getTotalAmount() {
        return totalAmount;
    }

    public BigDecimal getMaxOrderAmount() {
        return maxOrderAmount;
    }
}
