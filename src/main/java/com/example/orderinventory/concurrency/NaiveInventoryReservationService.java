package com.example.orderinventory.concurrency;

import com.example.orderinventory.inventory.Inventory;
import com.example.orderinventory.inventory.InventoryRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Phien ban NAIVE, CO CHU DICH de tai hien lost update (muc 17): doc
 * quantityOnHand vao bo nho, tru 1, roi save gia tri da tinh san - khong co
 * @Version, khong SELECT ... FOR UPDATE, khong UPDATE ... WHERE quantity >=
 * :q. Hai transaction cung doc duoc gia tri CU truoc khi ben kia commit se
 * ghi de len nhau, mat mot lan tru ma khong co exception nao bao hieu.
 */
@Service
public class NaiveInventoryReservationService {

    private final InventoryRepository inventoryRepository;

    public NaiveInventoryReservationService(InventoryRepository inventoryRepository) {
        this.inventoryRepository = inventoryRepository;
    }

    @Transactional
    public void decrementQuantity(Long inventoryId) {
        decrementQuantity(inventoryId, () -> { });
    }

    /**
     * afterRead chay NGAY SAU khi doc xong, TRUOC khi tinh gia tri moi va
     * save - day la diem test dung de ep hai thread cung doc truoc khi ben
     * nao kip ghi (vi du bang CountDownLatch), thay vi dua vao may man cua
     * scheduler luon.
     */
    @Transactional
    public void decrementQuantity(Long inventoryId, Runnable afterRead) {
        Inventory inventory = inventoryRepository.findById(inventoryId)
                .orElseThrow(() -> new IllegalArgumentException("Inventory not found: " + inventoryId));

        int quantityAlreadyRead = inventory.getQuantityOnHand();
        afterRead.run();

        inventory.setQuantityOnHand(quantityAlreadyRead - 1);
        inventoryRepository.save(inventory);
    }
}
