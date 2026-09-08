package com.example.orderinventory.cascade;

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
@Table(name = "cascade_remove_order_items")
@Getter
@Setter
@NoArgsConstructor
public class CascadeRemoveOrderItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "product_sku", nullable = false)
    private String productSku;

    @ManyToOne
    @JoinColumn(name = "order_id", nullable = false)
    private CascadeRemoveOrder order;

    public CascadeRemoveOrderItem(String productSku) {
        this.productSku = productSku;
    }
}
