package com.example.orderinventory.cascade;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.ArrayList;
import java.util.List;

/**
 * Order/OrderItem "chuan" (cascade = ALL, orphanRemoval = true, giong cau
 * hinh cua Order that o package order). Duoc dung rieng cho bai anti-pattern
 * de khong lam ban entity that o package order.
 */
@Entity
@Table(name = "bad_cascade_orders")
@Getter
@Setter
@NoArgsConstructor
public class BadCascadeOrder {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "customer_name", nullable = false)
    private String customerName;

    @OneToMany(mappedBy = "order", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<BadCascadeOrderItem> items = new ArrayList<>();

    public BadCascadeOrder(String customerName) {
        this.customerName = customerName;
    }

    public void addItem(BadCascadeOrderItem item) {
        items.add(item);
        item.setOrder(this);
    }
}
