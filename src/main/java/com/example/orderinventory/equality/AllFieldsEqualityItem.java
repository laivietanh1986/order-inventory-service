package com.example.orderinventory.equality;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.util.Objects;

/**
 * CACH TAM ON: equals/hashCode dua tren toan bo field nghiep vu (KHONG bao
 * gom id). hashCode khong doi khi persist (vi khong phu thuoc id), nhung van
 * la field MUTABLE - neu bat ky field nao dung trong equals bi sua sau khi
 * object da nam trong HashSet, van vo hop dong y het truong hop id.
 */
@Entity
@Table(name = "all_fields_equality_items")
@Getter
@Setter
public class AllFieldsEqualityItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "product_sku", nullable = false)
    private String productSku;

    @Column(nullable = false)
    private Integer quantity;

    protected AllFieldsEqualityItem() {
    }

    public AllFieldsEqualityItem(String productSku, Integer quantity) {
        this.productSku = productSku;
        this.quantity = quantity;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof AllFieldsEqualityItem other)) return false;
        return Objects.equals(productSku, other.productSku) && Objects.equals(quantity, other.quantity);
    }

    @Override
    public int hashCode() {
        return Objects.hash(productSku, quantity);
    }
}
