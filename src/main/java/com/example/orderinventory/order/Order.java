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

import java.math.BigDecimal;
import java.time.Instant;
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

    @Column(name = "total_amount", nullable = false)
    private BigDecimal totalAmount = BigDecimal.ZERO;

    // Cot moi cho muc 14 (composite index): dong vai tro range/sort column
    // trong query WHERE ... AND created_at BETWEEN ? AND ? ORDER BY created_at
    // DESC. Co gia tri mac dinh o tang Java giong totalAmount o muc 12, DB
    // cung co DEFAULT CURRENT_TIMESTAMP (xem V12) de cac test insert bang raw
    // JDBC tu truoc (khong biet cot nay) van chay binh thuong.
    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    // mappedBy = "order": day la INVERSE side, chi de doc, KHONG anh huong den
    // SQL sinh ra. FK order_id trong bang order_items hoan toan do OrderItem.order
    // (owning side, @ManyToOne) quyet dinh. Neu chi sua list nay ma khong sua
    // item.setOrder(...), Hibernate se khong biet ma "dong bo nguoc" lai FK.
    @OneToMany(mappedBy = "order", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<OrderItem> items = new ArrayList<>();

    // Mot bag (List, khong @OrderColumn) khac - day chinh la nguyen lieu cho
    // MultipleBagFetchException o muc 11 khi JOIN FETCH dong thoi voi items.
    // Lich su trang thai la nhat ky (audit trail): chi PERSIST, khong REMOVE/
    // orphanRemoval - khong ai duoc phep "xoa" mot dong lich su da ghi.
    @OneToMany(mappedBy = "order", cascade = CascadeType.PERSIST)
    private List<OrderStatusHistory> statusHistory = new ArrayList<>();

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

    public void addStatusHistory(OrderStatusHistory entry) {
        statusHistory.add(entry);
        entry.setOrder(this);
    }
}
