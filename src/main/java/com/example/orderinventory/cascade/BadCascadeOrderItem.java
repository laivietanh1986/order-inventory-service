package com.example.orderinventory.cascade;

import com.example.orderinventory.product.Product;
import jakarta.persistence.CascadeType;
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
@Table(name = "bad_cascade_order_items")
@Getter
@Setter
@NoArgsConstructor
public class BadCascadeOrderItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne
    @JoinColumn(name = "order_id", nullable = false)
    private BadCascadeOrder order;

    // ANTI-PATTERN CO CHU DICH: Product la danh muc dung chung giua nhieu don
    // hang, KHONG phai thu chi ton tai gan voi mot OrderItem cu the. Gan
    // cascade = ALL (bao gom REMOVE) o day khien vong doi cua Product bi troi
    // buoc sai vao vong doi cua OrderItem/Order.
    @ManyToOne(cascade = CascadeType.ALL)
    @JoinColumn(name = "product_id", nullable = false)
    private Product product;

    public BadCascadeOrderItem(Product product) {
        this.product = product;
    }
}
