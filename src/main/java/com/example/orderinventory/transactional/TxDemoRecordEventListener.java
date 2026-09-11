package com.example.orderinventory.transactional;

import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Bay 4/4 (muc 20): hai kieu listener cho CUNG mot su kien, khac nhau o THOI
 * DIEM chay. Ca hai deu kiem tra "row co thuc su nhin thay duoc tu mot
 * TRANSACTION KHAC (REQUIRES_NEW - connection rieng) hay khong" - day la
 * phep thu chinh xac hon nhieu so voi doc bang chinh connection dang mo
 * transaction (vi connection do luon thay du lieu cua chinh no, du chua
 * commit).
 */
@Component
public class TxDemoRecordEventListener {

    private final TxDemoRecordRepository recordRepository;
    private final EventVisibilityRecorder recorder;
    private final TransactionTemplate requiresNewTransactionTemplate;

    public TxDemoRecordEventListener(TxDemoRecordRepository recordRepository, EventVisibilityRecorder recorder,
            PlatformTransactionManager transactionManager) {
        this.recordRepository = recordRepository;
        this.recorder = recorder;
        this.requiresNewTransactionTemplate = new TransactionTemplate(transactionManager);
        this.requiresNewTransactionTemplate.setPropagationBehavior(Propagation.REQUIRES_NEW.value());
    }

    /**
     * @EventListener thuong: Spring goi DONG BO ngay tai diem publishEvent()
     * - tuc la VAN O BEN TRONG transaction cua EventPublishingService, TRUOC
     * khi no commit.
     */
    @EventListener
    public void onCreatedSynchronously(TxDemoRecordCreatedEvent event) {
        recorder.recordSyncListenerVisibility(isVisibleInFreshTransaction(event.recordId()));
    }

    /**
     * @TransactionalEventListener(AFTER_COMMIT): Spring HOAN LAI viec goi
     * method nay cho toi khi transaction publish su kien COMMIT XONG - luc
     * nay du lieu chac chan da duoc ghi that su, nhin thay duoc tu bat ky
     * connection nao khac.
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onCreatedAfterCommit(TxDemoRecordCreatedEvent event) {
        recorder.recordAfterCommitListenerVisibility(isVisibleInFreshTransaction(event.recordId()));
    }

    /** REQUIRES_NEW: tam dung transaction hien tai (neu co), dung mot connection HOAN TOAN KHAC de doc. */
    private boolean isVisibleInFreshTransaction(Long id) {
        return Boolean.TRUE.equals(requiresNewTransactionTemplate.execute(status -> recordRepository.existsById(id)));
    }
}
