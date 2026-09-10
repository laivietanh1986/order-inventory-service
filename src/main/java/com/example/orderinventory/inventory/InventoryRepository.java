package com.example.orderinventory.inventory;

import jakarta.persistence.LockModeType;
import jakarta.persistence.QueryHint;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.QueryHints;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface InventoryRepository extends JpaRepository<Inventory, Long> {

    /**
     * Pessimistic (muc 18): sinh {@code SELECT ... FOR UPDATE}. Row bi khoa
     * ngay luc SELECT chay, giu den khi transaction hien tai commit/rollback
     * - transaction khac goi cung method nay tren cung id se BI CHAN (block)
     * cho toi khi lock duoc nha, khong doc duoc gia tri "cu" nhu kieu naive
     * o muc 17.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select i from Inventory i where i.id = :id")
    Optional<Inventory> findByIdForUpdate(@Param("id") Long id);

    /**
     * NOWAIT: thay vi CHO, bao loi NGAY (PessimisticLockException /
     * CannotAcquireLockException) neu row dang bi khoa boi transaction khac.
     * Dung khi thuc su thich "that bai nhanh" hon la xep hang cho lock.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints(@QueryHint(name = "jakarta.persistence.lock.timeout", value = "0"))
    @Query("select i from Inventory i where i.id = :id")
    Optional<Inventory> findByIdForUpdateNoWait(@Param("id") Long id);

    /**
     * SKIP LOCKED: bo qua row dang bi khoa (tra ve rong) thay vi cho hoac loi
     * - chia khoa cho job queue pattern, nhieu worker cung SELECT ... FOR
     * UPDATE SKIP LOCKED de tu dong "chia" cac row chua bi ai khoa cho nhau,
     * khong worker nao dam vao row ma worker khac dang xu ly.
     */
    @Query(value = "select * from inventory where id = :id for update skip locked", nativeQuery = true)
    Optional<Inventory> findByIdForUpdateSkipLocked(@Param("id") Long id);

    /**
     * Atomic conditional update (muc 18): dieu kien du hang "available >=
     * qty" nam NGAY TRONG cau UPDATE, kiem tra bang affected rows - khong can
     * doc entity vao bo nho, khong can lock tuong minh o tang Java, DB tu bao
     * dam tinh atomic cua doc-kiem tra-ghi trong MOT cau lenh duy nhat.
     */
    @Modifying
    @Query("update Inventory i set i.quantityOnHand = i.quantityOnHand - :qty "
            + "where i.id = :id and i.quantityOnHand >= :qty")
    int decrementIfAvailable(@Param("id") Long id, @Param("qty") int qty);
}
