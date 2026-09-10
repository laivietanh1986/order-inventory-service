package com.example.orderinventory.concurrency;

import com.example.orderinventory.inventory.InventoryRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Muc 19 - deadlock co chu dich: mot order khoa NHIEU row inventory trong
 * mot transaction (vi du order gom 2 san pham). Neu order A khoa theo thu tu
 * [1, 2] va order B khoa theo thu tu [2, 1] CHAY SONG SONG, ca hai co the
 * cung giu duoc lock dau tien roi cung cho lock thu hai cua nhau - vong cho
 * doi vong tron (circular wait) ma Postgres se phat hien va huy MOT trong
 * hai transaction bang loi "deadlock detected".
 */
@Service
public class MultiInventoryReservationService {

    private final InventoryRepository inventoryRepository;

    public MultiInventoryReservationService(InventoryRepository inventoryRepository) {
        this.inventoryRepository = inventoryRepository;
    }

    /**
     * BUG: khoa cac inventoryId THEO DUNG THU TU duoc truyen vao - neu hai
     * order khoa chung 2 row nhung theo thu tu NGUOC NHAU, co the deadlock.
     * afterFirstLock chay ngay sau khi khoa xong row DAU TIEN - test dung no
     * de ep ca hai transaction cung giu lock dau tien truoc khi ben nao kip
     * xin lock thu hai, dam bao vong cho doi vong tron chac chan hinh thanh.
     */
    @Transactional
    public void reserveInGivenOrder(List<Long> inventoryIds, Runnable afterFirstLock) {
        boolean first = true;
        for (Long id : inventoryIds) {
            inventoryRepository.findByIdForUpdate(id)
                    .orElseThrow(() -> new IllegalArgumentException("Inventory not found: " + id));
            if (first) {
                afterFirstLock.run();
                first = false;
            }
        }
    }

    /**
     * FIX: sap xep inventoryId TANG DAN truoc khi khoa, bat ke thu tu san
     * pham trong don hang la gi. Moi order dang tranh chap cung mot tap row
     * gio deu xin lock theo CUNG MOT THU TU tuyet doi - khong con canh nao
     * "giu A cho B" trong khi canh kia "giu B cho A" duoc nua, nen khong the
     * hinh thanh vong tron: order den sau chi CHO (block) o dung 1 diem, roi
     * duoc tiep tuc binh thuong sau khi order truoc no commit.
     */
    @Transactional
    public void reserveDeadlockSafe(List<Long> inventoryIds) {
        List<Long> sortedIds = new ArrayList<>(inventoryIds);
        Collections.sort(sortedIds);
        for (Long id : sortedIds) {
            inventoryRepository.findByIdForUpdate(id)
                    .orElseThrow(() -> new IllegalArgumentException("Inventory not found: " + id));
        }
    }
}
