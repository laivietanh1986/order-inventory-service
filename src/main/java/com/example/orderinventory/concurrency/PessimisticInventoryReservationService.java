package com.example.orderinventory.concurrency;

import com.example.orderinventory.inventory.Inventory;
import com.example.orderinventory.inventory.InventoryRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Fix 2/3 cho lost update (muc 18): pessimistic locking bang {@code SELECT
 * ... FOR UPDATE} (qua {@link InventoryRepository#findByIdForUpdate}).
 * Transaction thu hai goi cung method tren cung id se BI CHAN cho den khi
 * transaction thu nhat commit/rollback - khong bao gio doc phai gia tri "cu"
 * nhu kieu naive o muc 17, nen khong can retry nhu optimistic.
 */
@Service
public class PessimisticInventoryReservationService {

    private final InventoryRepository inventoryRepository;

    public PessimisticInventoryReservationService(InventoryRepository inventoryRepository) {
        this.inventoryRepository = inventoryRepository;
    }

    @Transactional
    public void decrement(Long inventoryId) {
        Inventory inventory = inventoryRepository.findByIdForUpdate(inventoryId)
                .orElseThrow(() -> new IllegalArgumentException("Inventory not found: " + inventoryId));
        inventory.setQuantityOnHand(inventory.getQuantityOnHand() - 1);
        inventoryRepository.save(inventory);
    }

    /** NOWAIT: nem loi ngay neu row dang bi khoa, thay vi cho. */
    @Transactional
    public void decrementNoWait(Long inventoryId) {
        Inventory inventory = inventoryRepository.findByIdForUpdateNoWait(inventoryId)
                .orElseThrow(() -> new IllegalArgumentException("Inventory not found: " + inventoryId));
        inventory.setQuantityOnHand(inventory.getQuantityOnHand() - 1);
        inventoryRepository.save(inventory);
    }

    /** SKIP LOCKED: bo qua (tra ve rong) neu row dang bi khoa, thay vi cho hoac loi. */
    @Transactional
    public Optional<Long> decrementSkipLocked(Long inventoryId) {
        Optional<Inventory> found = inventoryRepository.findByIdForUpdateSkipLocked(inventoryId);
        if (found.isEmpty()) {
            return Optional.empty();
        }
        Inventory inventory = found.get();
        inventory.setQuantityOnHand(inventory.getQuantityOnHand() - 1);
        inventoryRepository.save(inventory);
        return Optional.of(inventory.getId());
    }

    /**
     * Test-support: khoa row roi GIU nguyen cho toi khi releaseSignal duoc
     * mo, de test co the deterministically tao ra tinh huong "row dang bi
     * khoa boi transaction khac" thay vi phu thuoc may man cua timing.
     * afterLockAcquired chay NGAY sau khi SELECT ... FOR UPDATE thanh cong.
     */
    @Transactional
    public void holdLockUntilReleased(Long inventoryId, Runnable afterLockAcquired, CountDownLatch releaseSignal)
            throws InterruptedException {
        inventoryRepository.findByIdForUpdate(inventoryId)
                .orElseThrow(() -> new IllegalArgumentException("Inventory not found: " + inventoryId));
        afterLockAcquired.run();
        releaseSignal.await(10, TimeUnit.SECONDS);
    }
}
