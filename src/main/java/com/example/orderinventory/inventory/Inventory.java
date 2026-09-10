package com.example.orderinventory.inventory;

import com.example.orderinventory.product.Product;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * OWNING side cua quan he 1-1 voi Product: giu cot FK (product_id). Khong
 * khai bao fetch nen mac dinh la EAGER (mac dinh cua @OneToOne), va o phia
 * owning nay, LAZY thuc su hoat dong duoc neu can (Hibernate biet chinh xac
 * FK nam trong bang cua no nen co the tao proxy ma khong can query truoc).
 *
 * CO CHU DICH khong co @Version: entity nay dung cho muc 17 (naive, tai hien
 * lost update) va cac fix pessimistic/atomic/deadlock o muc 18-19 - nhung ky
 * thuat do KHONG can @Version. Fix optimistic locking dung mot entity RIENG
 * ({@link com.example.orderinventory.concurrency.OptimisticInventory}), vi
 * neu them @Version vao day, Hibernate se TU DONG kiem tra version cho MOI
 * lan save() - vo tinh "sua" luon ca ban naive o muc 17, lam sai y do minh
 * hoa cua muc do.
 */
@Entity
@Table(name = "inventory")
@Getter
@Setter
@NoArgsConstructor
public class Inventory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @OneToOne
    @JoinColumn(name = "product_id", nullable = false, unique = true)
    private Product product;

    @Column(name = "quantity_on_hand", nullable = false)
    private Integer quantityOnHand;

    public Inventory(Product product, Integer quantityOnHand) {
        this.product = product;
        this.quantityOnHand = quantityOnHand;
    }
}
