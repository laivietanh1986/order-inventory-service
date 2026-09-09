package com.example.orderinventory.bagfetch;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface BagFetchFixParentRepository extends JpaRepository<BagFetchFixParent, Long> {

    // Ca hai ben deu da la Set (khong con la bag) -> Hibernate loai trung
    // dung dua tren equals/hashCode cho ca hai, JOIN FETCH dong thoi ca hai
    // CUNG LUC vua khong nem MultipleBagFetchException vua khong bi nhan doi
    // phan tu do cartesian product (khac voi truong hop List+Set - xem docs).
    @Query("SELECT DISTINCT p FROM BagFetchFixParent p " +
            "JOIN FETCH p.itemsA JOIN FETCH p.itemsB WHERE p.id = :id")
    BagFetchFixParent findWithBothSetsById(Long id);
}
