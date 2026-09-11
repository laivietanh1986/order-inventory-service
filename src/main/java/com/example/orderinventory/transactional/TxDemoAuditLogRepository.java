package com.example.orderinventory.transactional;

import org.springframework.data.jpa.repository.JpaRepository;

public interface TxDemoAuditLogRepository extends JpaRepository<TxDemoAuditLog, Long> {

    long countByMessage(String message);
}
