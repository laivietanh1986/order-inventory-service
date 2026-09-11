package com.example.orderinventory.transactional;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** Entity dung chung cho ca 4 bay @Transactional o muc 20 - chi can 1 field de theo doi commit/rollback. */
@Entity
@Table(name = "tx_demo_records")
@Getter
@Setter
@NoArgsConstructor
public class TxDemoRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String label;

    public TxDemoRecord(String label) {
        this.label = label;
    }
}
