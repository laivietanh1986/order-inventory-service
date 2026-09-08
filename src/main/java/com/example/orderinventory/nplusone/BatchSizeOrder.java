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
import org.hibernate.annotations.BatchSize;

import java.util.ArrayList;
import java.util.List;

/**
 * Vu khi 3: @BatchSize(size = 20). Van la @OneToMany LAZY binh thuong, nhung
 * khi collection dau tien can duoc khoi tao, Hibernate KHONG chi load rieng
 * cho 1 parent - no gom toi 20 parent id dang cho "chua co items" trong cung
 * persistence context vao MOT cau "WHERE order_id IN (?, ?, ..., 20 gia tri)".
 * N query rieng le bien thanh ceil(N/20) query theo lo.
 */
@Entity
@Table(name = "batch_size_orders")
@Getter
@Setter
@NoArgsConstructor
public class BatchSizeOrder {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "customer_name", nullable = false)
    private String customerName;

    @OneToMany(mappedBy = "order", cascade = CascadeType.PERSIST)
    @BatchSize(size = 20)
    private List<BatchSizeOrderItem> items = new ArrayList<>();

    public BatchSizeOrder(String customerName) {
        this.customerName = customerName;
    }

    public void addItem(BatchSizeOrderItem item) {
        items.add(item);
        item.setOrder(this);
    }
}
