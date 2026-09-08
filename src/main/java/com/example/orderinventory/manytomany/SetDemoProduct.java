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

import java.util.HashSet;
import java.util.Set;

/**
 * Kich ban B: doi List sang Set. Voi Set, Hibernate co the dung equals/hashCode
 * (mac dinh la identity trong pham vi mot persistence context) de so sanh
 * snapshot luc load voi trang thai hien tai va tinh ra CHINH XAC phan tu nao
 * moi duoc them / bi bo - khong can xoa het roi insert lai.
 */
@Entity
@Table(name = "set_demo_products")
@Getter
@Setter
@NoArgsConstructor
public class SetDemoProduct {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String name;

    @ManyToMany(cascade = CascadeType.PERSIST)
    @JoinTable(
            name = "set_demo_product_tags",
            joinColumns = @JoinColumn(name = "product_id"),
            inverseJoinColumns = @JoinColumn(name = "tag_id")
    )
    private Set<SetDemoTag> tags = new HashSet<>();

    public SetDemoProduct(String name) {
        this.name = name;
    }
}
