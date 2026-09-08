package com.example.orderinventory.cascade;

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
 * Kich ban 1: KHONG cascade gi ca. Muon persist/remove item phai tu lam qua
 * chinh entity do, khong the "nho" vao thao tac tren Order.
 */
@Entity
@Table(name = "no_cascade_orders")
@Getter
@Setter
@NoArgsConstructor
public class NoCascadeOrder {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "customer_name", nullable = false)
    private String customerName;

    @OneToMany(mappedBy = "order")
    private List<NoCascadeOrderItem> items = new ArrayList<>();

    public NoCascadeOrder(String customerName) {
        this.customerName = customerName;
    }

    public void addItem(NoCascadeOrderItem item) {
        items.add(item);
        item.setOrder(this);
    }
}
