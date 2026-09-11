package com.example.orderinventory.transactional;

import org.springframework.data.jpa.repository.JpaRepository;

public interface TxDemoRecordRepository extends JpaRepository<TxDemoRecord, Long> {

    long countByLabel(String label);
}
