package com.example.orderinventory.tag;

import com.example.orderinventory.product.Product;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

/**
 * Join entity tuong minh thay cho @ManyToMany truc tiep giua Product va Tag.
 * Moi dong o day co @Id RIENG cua no, nen day khong con la mot "bag" vo danh:
 * them mot ProductTag moi chi la MOT INSERT, khong dung toi cac dong khac. No
 * cung la noi tu nhien de gan them metadata rieng cho tung lien ket
 * (created_at, sort_order) ma mot bang join thuan tuy khong the co.
 */
@Entity
@Table(name = "product_tags", uniqueConstraints = @UniqueConstraint(columnNames = {"product_id", "tag_id"}))
@Getter
@Setter
@NoArgsConstructor
public class ProductTag {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne
    @JoinColumn(name = "product_id", nullable = false)
    private Product product;

    @ManyToOne
    @JoinColumn(name = "tag_id", nullable = false)
    private Tag tag;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "sort_order", nullable = false)
    private Integer sortOrder;

    public ProductTag(Product product, Tag tag, Integer sortOrder) {
        this.product = product;
        this.tag = tag;
        this.sortOrder = sortOrder;
        this.createdAt = Instant.now();
    }
}
