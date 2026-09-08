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
import java.util.UUID;

/**
 * CACH DUNG: mot business key (o day la UUID) duoc client gan ngay luc khoi
 * tao object, KHONG phu thuoc @GeneratedValue. Khong co setter cho field nay
 * nen no bat bien trong suot vong doi object (transient, managed, detached
 * deu cung mot gia tri) - hashCode() vi vay luon on dinh.
 */
@Entity
@Table(name = "business_key_equality_items")
@Getter
public class BusinessKeyEqualityItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "business_key", nullable = false, updatable = false, unique = true)
    private String businessKey;

    @Setter
    @Column(name = "product_sku", nullable = false)
    private String productSku;

    @Setter
    @Column(nullable = false)
    private Integer quantity;

    protected BusinessKeyEqualityItem() {
    }

    public BusinessKeyEqualityItem(String productSku, Integer quantity) {
        this.businessKey = UUID.randomUUID().toString();
        this.productSku = productSku;
        this.quantity = quantity;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof BusinessKeyEqualityItem other)) return false;
        return Objects.equals(businessKey, other.businessKey);
    }

    @Override
    public int hashCode() {
        return Objects.hash(businessKey);
    }
}
