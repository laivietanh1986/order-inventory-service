package com.example.orderinventory.manytomany;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.JoinTable;
import jakarta.persistence.ManyToMany;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.ArrayList;
import java.util.List;

/**
 * Kich ban A: @ManyToMany voi List. Hibernate coi List (khong co index column)
 * la mot BAG - cac phan tu khong co dinh danh rieng trong bang join, nen no
 * khong the biet chinh xac hang nao "moi them" hay "van giu nguyen" giua hai
 * lan flush. Chien luoc an toan duy nhat: xoa TOAN BO hang cu roi insert lai
 * TOAN BO hang hien tai.
 */
@Entity
@Table(name = "bag_demo_products")
@Getter
@Setter
@NoArgsConstructor
public class BagDemoProduct {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String name;

    @ManyToMany(cascade = CascadeType.PERSIST)
    @JoinTable(
            name = "bag_demo_product_tags",
            joinColumns = @JoinColumn(name = "product_id"),
            inverseJoinColumns = @JoinColumn(name = "tag_id")
    )
    private List<BagDemoTag> tags = new ArrayList<>();

    public BagDemoProduct(String name) {
        this.name = name;
    }
}
