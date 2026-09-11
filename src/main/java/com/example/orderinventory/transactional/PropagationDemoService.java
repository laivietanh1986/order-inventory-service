package com.example.orderinventory.transactional;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Bay 3/4 (muc 20): so sanh REQUIRES_NEW va NESTED khi transaction CHINH
 * that bai SAU KHI da ghi audit log.
 */
@Service
public class PropagationDemoService {

    private final TxDemoRecordRepository recordRepository;
    private final AuditLogService auditLogService;

    public PropagationDemoService(TxDemoRecordRepository recordRepository, AuditLogService auditLogService) {
        this.recordRepository = recordRepository;
        this.auditLogService = auditLogService;
    }

    @Transactional
    public void mainOperationWithRequiresNewAudit(String recordLabel, String auditMessage) {
        recordRepository.save(new TxDemoRecord(recordLabel));
        auditLogService.logInNewTransaction(auditMessage);
        throw new RuntimeException("gia lap loi nghiep vu SAU KHI da ghi audit - transaction chinh phai rollback");
    }

    @Transactional
    public void mainOperationWithNestedAudit(String recordLabel, String auditMessage) {
        recordRepository.save(new TxDemoRecord(recordLabel));
        auditLogService.logInNestedTransaction(auditMessage);
        throw new RuntimeException("gia lap loi nghiep vu SAU KHI da ghi audit - transaction chinh phai rollback");
    }
}
