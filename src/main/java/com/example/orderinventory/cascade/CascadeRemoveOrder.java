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
 * Kich ban 2: chi CascadeType.REMOVE, KHONG orphanRemoval. REMOVE chi kich
 * hoat khi entityManager.remove() duoc goi TREN CHINH Order — tach mot item
 * ra khoi collection (items.remove(...)) khong lien quan gi den cascade nay.
 */
@Entity
@Table(name = "cascade_remove_orders")
@Getter
@Setter
@NoArgsConstructor
public class CascadeRemoveOrder {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "customer_name", nullable = false)
    private String customerName;

    @OneToMany(mappedBy = "order", cascade = CascadeType.REMOVE)
    private List<CascadeRemoveOrderItem> items = new ArrayList<>();

    public CascadeRemoveOrder(String customerName) {
        this.customerName = customerName;
    }

    public void addItem(CascadeRemoveOrderItem item) {
        items.add(item);
        item.setOrder(this);
    }
}
