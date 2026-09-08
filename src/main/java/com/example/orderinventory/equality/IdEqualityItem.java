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
 * CACH SAI: equals/hashCode dua tren id do @GeneratedValue sinh ra. Truoc khi
 * persist, id = null nen moi instance transient deu "bang nhau" va co cung
 * hashCode; sau khi persist, id doi tu null sang mot gia tri that, lam
 * hashCode() tra ve gia tri KHAC voi luc object duoc them vao HashSet.
 */
@Entity
@Table(name = "id_equality_items")
@Getter
@Setter
public class IdEqualityItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "product_sku", nullable = false)
    private String productSku;

    @Column(nullable = false)
    private Integer quantity;

    protected IdEqualityItem() {
    }

    public IdEqualityItem(String productSku, Integer quantity) {
        this.productSku = productSku;
        this.quantity = quantity;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof IdEqualityItem other)) return false;
        return Objects.equals(id, other.id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id); // id = null luc transient -> hashCode doi sau persist
    }
}
