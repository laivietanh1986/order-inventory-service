package com.example.orderinventory.order;

/**
 * Muc tieu cua constructor expression (SELECT new ...): chi nap dung nhung
 * cot can dung, khong keo theo ca entity hay quan he lien quan.
 */
public class OrderItemSummaryDto {

    private final String productSku;
    private final Integer quantity;

    public OrderItemSummaryDto(String productSku, Integer quantity) {
        this.productSku = productSku;
        this.quantity = quantity;
    }

    public String getProductSku() {
        return productSku;
    }

    public Integer getQuantity() {
        return quantity;
    }
}
