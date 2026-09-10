package com.example.orderinventory.concurrency;

import com.example.orderinventory.inventory.InventoryRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Fix 3/3 cho lost update (muc 18): mot cau UPDATE atomic duy nhat,
 * {@code UPDATE inventory SET quantity_on_hand = quantity_on_hand - :qty
 * WHERE id = :id AND quantity_on_hand >= :qty}. Khong load entity vao bo
 * nho, khong can @Version, khong can SELECT ... FOR UPDATE tuong minh - ban
 * than cau UPDATE da atomic o tang database (Postgres tu khoa row trong luc
 * thuc thi UPDATE). Affected rows = 0 nghia la KHONG DU hang (hoac id
 * khong ton tai), affected rows = 1 nghia la thanh cong.
 */
@Service
public class AtomicUpdateInventoryReservationService {

    private final InventoryRepository inventoryRepository;

    public AtomicUpdateInventoryReservationService(InventoryRepository inventoryRepository) {
        this.inventoryRepository = inventoryRepository;
    }

    @Transactional
    public boolean tryDecrement(Long inventoryId, int quantity) {
        int affectedRows = inventoryRepository.decrementIfAvailable(inventoryId, quantity);
        return affectedRows == 1;
    }
}
