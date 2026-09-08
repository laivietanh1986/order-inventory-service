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
 * Kich ban 3: orphanRemoval = true, cascade CHI co PERSIST (co tinh KHONG co
 * REMOVE) de tach bach hai co che: orphanRemoval theo doi viec mot phan tu bi
 * tach khoi collection (items.remove(...)) va tu xoa no, hoan toan doc lap
 * voi viec Order co bi xoa hay khong.
 */
@Entity
@Table(name = "orphan_removal_orders")
@Getter
@Setter
@NoArgsConstructor
public class OrphanRemovalOrder {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "customer_name", nullable = false)
    private String customerName;

    @OneToMany(mappedBy = "order", cascade = CascadeType.PERSIST, orphanRemoval = true)
    private List<OrphanRemovalOrderItem> items = new ArrayList<>();

    public OrphanRemovalOrder(String customerName) {
        this.customerName = customerName;
    }

    public void addItem(OrphanRemovalOrderItem item) {
        items.add(item);
        item.setOrder(this);
    }
}
