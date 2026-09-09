package com.example.orderinventory.order;

/**
 * Interface-based projection cua Spring Data: khong can class implement,
 * Spring Data tu tao proxy tra ve dung gia tri cua tung getter tu ket qua
 * query (anh xa theo ten alias trong JPQL).
 */
public interface OrderItemSummaryView {

    String getProductSku();

    Integer getQuantity();
}
