package com.example.orderinventory.order;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;

public interface OrderItemRepository extends JpaRepository<OrderItem, Long> {

    // Cach 2: constructor expression (SELECT new ...). Chi SELECT dung 2 cot
    // can dung, khong JOIN sang orders du OrderItem.order la @ManyToOne mac
    // dinh EAGER (muc 5) - vi query tu khai bao ro can gi, khong di qua
    // duong "load ca entity" nua.
    @Query("SELECT new com.example.orderinventory.order.OrderItemSummaryDto(i.productSku, i.quantity) " +
            "FROM OrderItem i")
    List<OrderItemSummaryDto> findAllAsConstructorExpression();

    // Cach 3: interface-based projection. Cung chi SELECT 2 cot, Spring Data
    // tu tao proxy hien thuc interface tu ket qua tra ve.
    @Query("SELECT i.productSku AS productSku, i.quantity AS quantity FROM OrderItem i")
    List<OrderItemSummaryView> findAllAsInterfaceProjection();
}
