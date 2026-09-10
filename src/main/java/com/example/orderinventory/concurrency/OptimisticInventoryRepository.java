package com.example.orderinventory.concurrency;

import org.springframework.data.jpa.repository.JpaRepository;

public interface OptimisticInventoryRepository extends JpaRepository<OptimisticInventory, Long> {
}
