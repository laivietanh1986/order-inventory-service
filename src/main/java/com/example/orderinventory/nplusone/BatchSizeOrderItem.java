package com.example.orderinventory.nplusone;

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
@Table(name = "batch_size_order_items")
@Getter
@Setter
@NoArgsConstructor
public class BatchSizeOrderItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "product_sku", nullable = false)
    private String productSku;

    @ManyToOne
    @JoinColumn(name = "order_id", nullable = false)
    private BatchSizeOrder order;

    public BatchSizeOrderItem(String productSku) {
        this.productSku = productSku;
    }
}
