package com.example.orderinventory.concurrency;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Entity RIENG cho fix optimistic locking (muc 18) - co chu dich TACH khoi
 * {@link com.example.orderinventory.inventory.Inventory} (dung cho muc 17)
 * de @Version khong vo tinh "sua" luon ban naive o muc 17.
 */
@Entity
@Table(name = "optimistic_inventory")
@Getter
@Setter
@NoArgsConstructor
public class OptimisticInventory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "quantity_on_hand", nullable = false)
    private Integer quantityOnHand;

    // Hibernate tu dong them "and version = ?" vao UPDATE va tang version
    // len 1 sau moi lan ghi thanh cong. Neu affected rows = 0 (vi mot
    // transaction khac da ghi va tang version truoc), Hibernate nem
    // OptimisticLockingFailureException thay vi am tham ghi de.
    @Version
    private Long version;

    public OptimisticInventory(Integer quantityOnHand) {
        this.quantityOnHand = quantityOnHand;
    }
}
