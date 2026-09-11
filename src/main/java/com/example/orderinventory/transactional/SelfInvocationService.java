package com.example.orderinventory.transactional;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Bay 1/4 (muc 20): self-invocation. @Transactional cua Spring la
 * PROXY-BASED AOP - annotation chi co hieu luc khi method duoc goi TU BEN
 * NGOAI, qua proxy Spring tao ra quanh bean. Goi "this.method()" tu chinh
 * mot method KHAC trong CUNG class bo qua proxy hoan toan (day la mot Java
 * method call binh thuong, JVM khong biet gi ve @Transactional), nen
 * @Transactional tren method duoc goi bi IM LANG bo qua - khong loi, khong
 * canh bao, chi don gian la khong co transaction nao duoc mo ra.
 */
@Service
public class SelfInvocationService {

    /**
     * KHONG @Transactional - goi innerTransactional() bang "this." (ngam
     * dinh), tuc la mot loi goi Java thuan tuy, khong di qua proxy.
     */
    public boolean callInnerViaSelfInvocation() {
        return callInnerTransactional();
    }

    @Transactional
    public boolean callInnerTransactional() {
        return TransactionSynchronizationManager.isActualTransactionActive();
    }
}
