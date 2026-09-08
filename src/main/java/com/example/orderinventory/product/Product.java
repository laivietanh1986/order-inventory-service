package com.example.orderinventory.product;

import com.example.orderinventory.inventory.Inventory;
import com.example.orderinventory.tag.ProductTag;
import com.example.orderinventory.tag.Tag;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "products")
@Getter
@Setter
@NoArgsConstructor
public class Product {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String name;

    @Column(nullable = false, unique = true)
    private String sku;

    @Column(nullable = false)
    private Integer quantity;

    @Column(nullable = false)
    private BigDecimal price;

    // INVERSE side (mappedBy). fetch = LAZY o day CHI la mot goi y - phia
    // inverse cua @OneToOne khong the tao lazy proxy neu khong bat bytecode
    // enhancement, vi Hibernate khong biet FK (no nam ben bang inventory) nen
    // phai truy van truoc de biet gia tri la mot Inventory hay null.
    @OneToOne(mappedBy = "product", fetch = FetchType.LAZY)
    private Inventory inventory;

    // Join entity tuong minh thay vi @ManyToMany truc tiep voi Tag: moi
    // ProductTag co @Id rieng, nen them 1 tag chi la 1 INSERT, khong dung gi
    // toi cac dong con lai (khac han bag semantics cua @ManyToMany + List).
    @OneToMany(mappedBy = "product", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<ProductTag> productTags = new ArrayList<>();

    public Product(String name, String sku, Integer quantity, BigDecimal price) {
        this.name = name;
        this.sku = sku;
        this.quantity = quantity;
        this.price = price;
    }

    public ProductTag addTag(Tag tag, int sortOrder) {
        ProductTag productTag = new ProductTag(this, tag, sortOrder);
        productTags.add(productTag);
        return productTag;
    }
}
