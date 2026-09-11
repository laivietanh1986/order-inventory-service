package com.example.orderinventory.order;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

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

    // ANTI-PATTERN co chu dich (muc 10): JOIN FETCH mot collection ket hop
    // Pageable. Hibernate KHONG the ap dung LIMIT/OFFSET o tang SQL cho truong
    // hop nay (se cat nham giua chung mot collection), nen no nap TOAN BO ket
    // qua vao JVM roi moi tu cat lay dung trang - kem theo canh bao
    // "firstResult/maxResults specified with collection fetch; applying in
    // memory". countQuery duoc chi dinh tuong minh vi JPQL co "fetch" khong
    // hop le trong mot cau COUNT.
    @Query(value = "SELECT o FROM Order o JOIN FETCH o.items",
            countQuery = "SELECT COUNT(o) FROM Order o")
    Page<Order> findAllWithItemsJoinFetchPaged(Pageable pageable);

    // Two-query pattern (cach sua): query 1 CHI lay id, khong JOIN FETCH gi
    // ca -> Pageable ap dung LIMIT/OFFSET binh thuong o tang SQL, khong con
    // canh bao HHH nao.
    @Query("SELECT o.id FROM Order o ORDER BY o.id")
    Page<Long> findOrderIdsPaged(Pageable pageable);

    // Query 2: JOIN FETCH nhung KHONG dung Pageable/firstResult/maxResults -
    // no chi bi gioi han boi so luong id trong danh sach (da duoc phan trang
    // tu query 1), nen khong kich hoat canh bao "applying in memory".
    @Query("SELECT DISTINCT o FROM Order o JOIN FETCH o.items WHERE o.id IN :ids")
    List<Order> findAllWithItemsByIdIn(@Param("ids") List<Long> ids);

    // Cach 1 (muc 12) de lay danh sach: load ca entity roi tu map o tang Java
    // se dung o test qua findAll()/findByCustomerName() da co san.
    List<Order> findByCustomerName(String customerName);

    // Aggregate query: DB tu tinh COUNT/SUM/MAX, tra ve DUY NHAT 1 dong bat ke
    // khach hang co bao nhieu order - khong can nap ca danh sach roi
    // stream().reduce() o tang Java.
    @Query("SELECT new com.example.orderinventory.order.CustomerOrderSummaryDto(" +
            "COUNT(o), SUM(o.totalAmount), MAX(o.totalAmount)) " +
            "FROM Order o WHERE o.customerName = :customerName")
    CustomerOrderSummaryDto findCustomerOrderSummary(@Param("customerName") String customerName);

    // Muc 21: day dieu kien chuyen trang thai XUONG cau UPDATE thay vi
    // check-then-act o tang Java ("if (order.getStatus() == fromStatus)").
    // Affected rows = 1 nghia la CHINH lenh goi nay da thuc hien duoc buoc
    // chuyen trang thai; affected rows = 0 nghia la trang thai KHONG con la
    // fromStatus nua (da bi mot lenh goi khac doi truoc, hoac id khong ton
    // tai) - khong bao gio co chuyen "doc thay CREATED roi ghi de" nhu kieu
    // check-then-act.
    @Modifying
    @Query("update Order o set o.status = :toStatus where o.id = :id and o.status = :fromStatus")
    int updateStatusIfCurrentlyIs(@Param("id") Long id, @Param("fromStatus") String fromStatus,
            @Param("toStatus") String toStatus);

    @Query("select o.status from Order o where o.id = :id")
    String findStatusById(@Param("id") Long id);
}
