package com.example.orderinventory.transactional;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Bon bay @Transactional (muc 20), tren PostgreSQL that qua Testcontainers.
 * Khong dung @Transactional o class/method test - neu co, Spring se goi ca
 * test vao MOT transaction bao trum ca method dang test, lam sai lech chinh
 * hanh vi commit/rollback dang can quan sat.
 *
 * Chay: mvn test -Dtest=TransactionalPitfallsTest
 */
@SpringBootTest
@Testcontainers
class TransactionalPitfallsTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    private SelfInvocationService selfInvocationService;

    @Autowired
    private CheckedExceptionService checkedExceptionService;

    @Autowired
    private PropagationDemoService propagationDemoService;

    @Autowired
    private EventPublishingService eventPublishingService;

    @Autowired
    private EventVisibilityRecorder eventVisibilityRecorder;

    @Autowired
    private TxDemoRecordRepository recordRepository;

    @Autowired
    private TxDemoAuditLogRepository auditLogRepository;

    @BeforeEach
    void resetRecorder() {
        eventVisibilityRecorder.reset();
    }

    @AfterEach
    void cleanUpManually() {
        recordRepository.deleteAll();
        auditLogRepository.deleteAll();
    }

    /**
     * Bay 1/4: goi method @Transactional bang self-invocation ("this.")
     * hoan toan bo qua proxy cua Spring - khong co transaction nao duoc mo.
     * Goi CUNG method do TU BEN NGOAI (qua bean proxy) thi transaction duoc
     * mo binh thuong.
     */
    @Test
    void self_invocation_bo_qua_proxy_nen_khong_co_transaction_nao_duoc_mo() {
        boolean transactionActiveViaSelfInvocation = selfInvocationService.callInnerViaSelfInvocation();
        boolean transactionActiveViaProxy = selfInvocationService.callInnerTransactional();

        System.out.println("[LESSON20-SELF-INVOCATION] qua self-invocation: transaction active = "
                + transactionActiveViaSelfInvocation);
        System.out.println("[LESSON20-SELF-INVOCATION] qua proxy truc tiep: transaction active = "
                + transactionActiveViaProxy);

        assertThat(transactionActiveViaSelfInvocation).isFalse();
        assertThat(transactionActiveViaProxy).isTrue();
    }

    /** Bay 2/4: checked exception KHONG kich hoat rollback mac dinh - du bi nem ra, du lieu VAN COMMIT. */
    @Test
    void checked_exception_khong_kich_hoat_rollback_mac_dinh() {
        String label = "checked-" + UUID.randomUUID();

        assertThatThrownBy(() -> checkedExceptionService.saveThenThrowCheckedException(label))
                .isInstanceOf(Exception.class);

        long count = recordRepository.countByLabel(label);
        System.out.println("[LESSON20-CHECKED-EXCEPTION] so dong con lai sau checked exception = " + count);
        assertThat(count).isEqualTo(1); // VAN COMMIT - day chinh la cai bay
    }

    /** So sanh: unchecked (RuntimeException) kich hoat rollback mac dinh nhu ky vong. */
    @Test
    void runtime_exception_kich_hoat_rollback_mac_dinh() {
        String label = "runtime-" + UUID.randomUUID();

        assertThatThrownBy(() -> checkedExceptionService.saveThenThrowRuntimeException(label))
                .isInstanceOf(RuntimeException.class);

        assertThat(recordRepository.countByLabel(label)).isEqualTo(0);
    }

    /** Fix: khai bao ro rollbackFor de checked exception cung rollback. */
    @Test
    void rollbackFor_bat_checked_exception_cung_kich_hoat_rollback() {
        String label = "rollbackfor-" + UUID.randomUUID();

        assertThatThrownBy(() -> checkedExceptionService.saveThenThrowCheckedExceptionWithRollbackFor(label))
                .isInstanceOf(Exception.class);

        assertThat(recordRepository.countByLabel(label)).isEqualTo(0);
    }

    /** Bay 3/4 (REQUIRES_NEW): audit log song sot du transaction chinh rollback sau do. */
    @Test
    void requires_new_audit_song_sot_du_transaction_chinh_rollback() {
        String recordLabel = "main-requiresnew-" + UUID.randomUUID();
        String auditMessage = "audit-requiresnew-" + UUID.randomUUID();

        assertThatThrownBy(() ->
                propagationDemoService.mainOperationWithRequiresNewAudit(recordLabel, auditMessage))
                .isInstanceOf(RuntimeException.class);

        long mainCount = recordRepository.countByLabel(recordLabel);
        long auditCount = auditLogRepository.countByMessage(auditMessage);
        System.out.println("[LESSON20-PROPAGATION] REQUIRES_NEW: main record con lai=" + mainCount
                + ", audit log con lai=" + auditCount);

        assertThat(mainCount).isEqualTo(0); // transaction chinh rollback
        assertThat(auditCount).isEqualTo(1); // nhung audit da o mot transaction VAT LY KHAC, da commit roi
    }

    /** Bay 3/4 (NESTED): audit log bi cuon theo (savepoint nam trong CUNG transaction vat ly). */
    @Test
    void nested_audit_bi_cuon_theo_khi_transaction_chinh_rollback() {
        String recordLabel = "main-nested-" + UUID.randomUUID();
        String auditMessage = "audit-nested-" + UUID.randomUUID();

        assertThatThrownBy(() ->
                propagationDemoService.mainOperationWithNestedAudit(recordLabel, auditMessage))
                .isInstanceOf(RuntimeException.class);

        long mainCount = recordRepository.countByLabel(recordLabel);
        long auditCount = auditLogRepository.countByMessage(auditMessage);
        System.out.println("[LESSON20-PROPAGATION] NESTED: main record con lai=" + mainCount
                + ", audit log con lai=" + auditCount);

        assertThat(mainCount).isEqualTo(0);
        assertThat(auditCount).isEqualTo(0); // khac REQUIRES_NEW: NESTED bi cuon theo transaction chinh
    }

    /**
     * Bay 4/4: @EventListener thuong chay DONG BO truoc khi transaction
     * commit (mot transaction REQUIRES_NEW khac, dung connection rieng,
     * KHONG thay row). @TransactionalEventListener(AFTER_COMMIT) chi chay
     * SAU khi commit xong, nen luon thay duoc.
     */
    @Test
    void event_listener_dong_bo_khong_thay_du_lieu_truoc_commit_con_after_commit_thi_thay() {
        eventPublishingService.createRecordAndPublishEvent("event-demo-" + UUID.randomUUID());

        Boolean sawDuringSync = eventVisibilityRecorder.getSyncListenerSawRecord();
        Boolean sawAfterCommit = eventVisibilityRecorder.getAfterCommitListenerSawRecord();
        System.out.println("[LESSON20-EVENT] sync listener (truoc commit) thay row = " + sawDuringSync);
        System.out.println("[LESSON20-EVENT] after-commit listener thay row = " + sawAfterCommit);

        assertThat(sawDuringSync).isFalse();
        assertThat(sawAfterCommit).isTrue();
    }
}
