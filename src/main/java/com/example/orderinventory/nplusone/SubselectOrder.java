package com.example.orderinventory.nplusone;

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
import org.hibernate.annotations.Fetch;
import org.hibernate.annotations.FetchMode;

import java.util.ArrayList;
import java.util.List;

/**
 * Vu khi 4: @Fetch(FetchMode.SUBSELECT). Khi collection dau tien can khoi
 * tao, Hibernate KHONG load theo tung parent hay theo lo id - no chay LAI
 * chinh dieu kien cua cau query da nap cac parent, boc no lam mot subquery:
 *   SELECT items.* FROM subselect_order_items
 *   WHERE order_id IN (SELECT id FROM subselect_orders)
 * Chi 1 query DUY NHAT cho TOAN BO items, bat ke co bao nhieu order.
 */
@Entity
@Table(name = "subselect_orders")
@Getter
@Setter
@NoArgsConstructor
public class SubselectOrder {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "customer_name", nullable = false)
    private String customerName;

    @OneToMany(mappedBy = "order", cascade = CascadeType.PERSIST)
    @Fetch(FetchMode.SUBSELECT)
    private List<SubselectOrderItem> items = new ArrayList<>();

    public SubselectOrder(String customerName) {
        this.customerName = customerName;
    }

    public void addItem(SubselectOrderItem item) {
        items.add(item);
        item.setOrder(this);
    }
}
