package com.example.orderinventory.transactional;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Ghi audit log qua hai kieu propagation khac nhau (muc 20, bay 3/4). Nam o
 * MOT BEAN RIENG (khong phai self-invocation) de @Transactional tren day
 * thuc su di qua proxy khi PropagationDemoService goi vao.
 */
@Service
public class AuditLogService {

    private final TxDemoAuditLogRepository auditLogRepository;

    public AuditLogService(TxDemoAuditLogRepository auditLogRepository) {
        this.auditLogRepository = auditLogRepository;
    }

    /**
     * REQUIRES_NEW: TAM DUNG transaction hien tai (neu co), mo mot
     * transaction VAT LY hoan toan moi voi connection rieng, commit ngay khi
     * method nay ket thuc - doc lap hoan toan voi so phan cua transaction
     * ben ngoai.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void logInNewTransaction(String message) {
        auditLogRepository.save(new TxDemoAuditLog(message));
    }

    /**
     * NESTED: tao mot SAVEPOINT ben trong CUNG transaction vat ly (cung
     * connection) voi transaction ben ngoai - khong doc lap. Neu transaction
     * ben ngoai rollback TOAN BO (khong chi rollback ve savepoint nay), moi
     * thu sau savepoint - ke ca ban ghi nay - cung bi cuon theo.
     */
    @Transactional(propagation = Propagation.NESTED)
    public void logInNestedTransaction(String message) {
        auditLogRepository.save(new TxDemoAuditLog(message));
    }
}
