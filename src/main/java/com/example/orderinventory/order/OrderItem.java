package com.example.orderinventory.order;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "order_items")
@Getter
@Setter
@NoArgsConstructor
public class OrderItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "product_sku", nullable = false)
    private String productSku;

    @Column(nullable = false)
    private Integer quantity;

    // OWNING side cua quan he: @ManyToOne luon la owning side va giu cot FK.
    // Gia tri cua field nay tai thoi diem flush moi la thu quyet dinh order_id
    // duoc INSERT/UPDATE trong DB la gi — khong lien quan gi den viec entity
    // co nam trong Order.items hay khong.
    @ManyToOne
    @JoinColumn(name = "order_id")
    private Order order;

    public OrderItem(String productSku, Integer quantity) {
        this.productSku = productSku;
        this.quantity = quantity;
    }
}
