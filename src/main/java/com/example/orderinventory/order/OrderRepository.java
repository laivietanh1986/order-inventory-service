package com.example.orderinventory.order;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;

public interface OrderRepository extends JpaRepository<Order, Long> {

    // Vu khi 1: JOIN FETCH tuong minh trong JPQL. DISTINCT can thiet de loai
    // bo cac Order bi lap lai trong danh sach Java do moi row SQL tra ve la
    // mot cap (order, item) - order co 2 item se xuat hien 2 lan trong result
    // set truoc khi duoc gop lai.
    @Query("SELECT DISTINCT o FROM Order o JOIN FETCH o.items")
    List<Order> findAllWithItemsJoinFetch();

    // Ban KHONG DISTINCT de lam ro cartesian product: cung 1 cau SQL, nhung
    // danh sach Java tra ve se co Order bi lap lai theo so item no co.
    @Query("SELECT o FROM Order o JOIN FETCH o.items")
    List<Order> findAllWithItemsJoinFetchNoDistinct();

    // Vu khi 2: @EntityGraph - khai bao "toi can items duoc nap san" ma khong
    // phai tu viet JPQL rieng. Spring Data dich no thanh mot fetch graph hint
    // gan vao query, ve co ban Hibernate cung dung LEFT JOIN FETCH ben duoi.
    @EntityGraph(attributePaths = "items")
    @Query("SELECT o FROM Order o")
    List<Order> findAllWithItemsEntityGraph();
}
