package com.example.orderinventory.order;

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

@Entity
@Table(name = "orders")
@Getter
@Setter
@NoArgsConstructor
public class Order {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "customer_name", nullable = false)
    private String customerName;

    @Column(nullable = false)
    private String status;

    // mappedBy = "order": day la INVERSE side, chi de doc, KHONG anh huong den
    // SQL sinh ra. FK order_id trong bang order_items hoan toan do OrderItem.order
    // (owning side, @ManyToOne) quyet dinh. Neu chi sua list nay ma khong sua
    // item.setOrder(...), Hibernate se khong biet ma "dong bo nguoc" lai FK.
    @OneToMany(mappedBy = "order", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<OrderItem> items = new ArrayList<>();

    public Order(String customerName, String status) {
        this.customerName = customerName;
        this.status = status;
    }

    /**
     * Helper method bat buoc phai co cho quan he bidirectional: dong bo CA HAI
     * phia trong cung mot loi goi, tranh viec caller quen mat mot ben.
     */
    public void addItem(OrderItem item) {
        items.add(item);
        item.setOrder(this);
    }

    public void removeItem(OrderItem item) {
        items.remove(item);
        item.setOrder(null);
    }
}
