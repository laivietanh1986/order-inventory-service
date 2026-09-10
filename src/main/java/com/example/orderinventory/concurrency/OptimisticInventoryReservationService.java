package com.example.orderinventory.concurrency;

import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Fix 1/3 cho lost update (muc 18): optimistic locking bang @Version tren
 * {@link OptimisticInventory} (entity RIENG, xem ghi chu tren entity do). Doc,
 * tru, save y het ban NAIVE o muc 17 - khac biet DUY NHAT la entity co
 * @Version, nen Hibernate tu them "and version = ?" vao UPDATE. Neu mot
 * transaction khac da commit truoc, affected rows = 0 va Hibernate nem
 * OptimisticLockingFailureException thay vi am tham ghi de -> phai retry.
 */
@Service
public class OptimisticInventoryReservationService {

    private final OptimisticInventoryRepository optimisticInventoryRepository;

    public OptimisticInventoryReservationService(OptimisticInventoryRepository optimisticInventoryRepository) {
        this.optimisticInventoryRepository = optimisticInventoryRepository;
    }

    /** Mot lan thu - de test tu quan sat OptimisticLockingFailureException khi co xung dot. */
    @Transactional
    public void decrementOnce(Long inventoryId) {
        decrementOnce(inventoryId, () -> { });
    }

    /**
     * afterRead chay NGAY SAU khi doc xong quantityOnHand (va version), TRUOC
     * khi tinh gia tri moi va save - test dung tham so nay CHI o lan thu dau
     * tien, de ep hai thread cung doc duoc CUNG mot version truoc khi ben nao
     * kip ghi, dam bao xung dot xay ra chac chan (khong phu thuoc may man
     * scheduler) thay vi thinh thoang moi tai hien duoc.
     */
    @Transactional
    public void decrementOnce(Long inventoryId, Runnable afterRead) {
        OptimisticInventory inventory = optimisticInventoryRepository.findById(inventoryId)
                .orElseThrow(() -> new IllegalArgumentException("Inventory not found: " + inventoryId));
        int newQuantity = inventory.getQuantityOnHand() - 1;
        afterRead.run();
        inventory.setQuantityOnHand(newQuantity);
        optimisticInventoryRepository.save(inventory);
    }

    /**
     * Retry loop: thu lai TOAN BO chu trinh doc-tru-save moi khi va phai
     * OptimisticLockingFailureException, toi da maxAttempts lan. Tra ve so
     * lan da THU (1 = thanh cong ngay lan dau, khong retry lan nao).
     */
    public int decrementWithRetry(Long inventoryId, int maxAttempts) {
        return decrementWithRetry(inventoryId, maxAttempts, () -> { });
    }

    /** Nhu decrementWithRetry, nhung afterReadOnFirstAttempt chi ap dung cho LAN THU DAU TIEN. */
    public int decrementWithRetry(Long inventoryId, int maxAttempts, Runnable afterReadOnFirstAttempt) {
        int attempts = 0;
        while (true) {
            attempts++;
            try {
                if (attempts == 1) {
                    decrementOnce(inventoryId, afterReadOnFirstAttempt);
                } else {
                    decrementOnce(inventoryId);
                }
                return attempts;
            } catch (OptimisticLockingFailureException e) {
                if (attempts >= maxAttempts) {
                    throw e;
                }
                // Khong sleep: muon do dung "so lan retry can thiet" o
                // muc do tranh chap hien tai, khong phai do hieu ung backoff.
            }
        }
    }
}
