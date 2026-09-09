package com.example.orderinventory.bagfetch;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.util.HashSet;
import java.util.Set;

/**
 * Minh hoa Fix 1 cho MultipleBagFetchException (muc 11): doi CA HAI collection
 * tu List sang Set. Chi doi MOT ben thoi (giu ben kia la List) tranh duoc
 * MultipleBagFetchException (Hibernate khong con thay hai bag), nhung van con
 * mot van de khac: ben con la List se bi NHAN DOI phan tu theo cartesian
 * product cua phia Set (da kiem chung bang thuc nghiem - xem docs muc 11).
 * Doi CA HAI ve Set moi giai quyet triet de: khong ben nao la bag nua, ca hai
 * deu tu loai trung dung dua tren equals/hashCode.
 */
@Entity
@Table(name = "bagfetch_fix_parents")
@Getter
@NoArgsConstructor
public class BagFetchFixParent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @OneToMany(mappedBy = "parent", cascade = CascadeType.PERSIST)
    private Set<BagFetchFixItemA> itemsA = new HashSet<>();

    @OneToMany(mappedBy = "parent", cascade = CascadeType.PERSIST)
    private Set<BagFetchFixItemB> itemsB = new HashSet<>();

    public void addItemA(BagFetchFixItemA item) {
        itemsA.add(item);
        item.setParent(this);
    }

    public void addItemB(BagFetchFixItemB item) {
        itemsB.add(item);
        item.setParent(this);
    }
}
