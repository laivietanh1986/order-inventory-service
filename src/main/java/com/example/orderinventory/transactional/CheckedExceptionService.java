package com.example.orderinventory.transactional;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Bay 2/4 (muc 20): rollback rule mac dinh cua @Transactional CHI ap dung
 * cho unchecked exception (RuntimeException/Error) - checked exception
 * (Exception thuong, khong ke thua RuntimeException) KHONG kich hoat
 * rollback tru khi khai bao ro `rollbackFor`. Day la ke thua truc tiep tu
 * EJB CMT truoc day, hoan toan nguoc voi truc giac "nem loi thi phai
 * rollback".
 */
@Service
public class CheckedExceptionService {

    private final TxDemoRecordRepository recordRepository;

    public CheckedExceptionService(TxDemoRecordRepository recordRepository) {
        this.recordRepository = recordRepository;
    }

    /** BAY: nem checked exception sau khi save - theo mac dinh VAN COMMIT. */
    @Transactional
    public void saveThenThrowCheckedException(String label) throws Exception {
        recordRepository.save(new TxDemoRecord(label));
        throw new Exception("simulated checked failure - mac dinh KHONG rollback");
    }

    /** So sanh: nem unchecked exception - mac dinh CO rollback. */
    @Transactional
    public void saveThenThrowRuntimeException(String label) {
        recordRepository.save(new TxDemoRecord(label));
        throw new RuntimeException("simulated runtime failure - mac dinh CO rollback");
    }

    /** FIX: khai bao ro rollbackFor de checked exception cung kich hoat rollback. */
    @Transactional(rollbackFor = Exception.class)
    public void saveThenThrowCheckedExceptionWithRollbackFor(String label) throws Exception {
        recordRepository.save(new TxDemoRecord(label));
        throw new Exception("simulated checked failure - CO rollback vi khai bao rollbackFor");
    }
}
