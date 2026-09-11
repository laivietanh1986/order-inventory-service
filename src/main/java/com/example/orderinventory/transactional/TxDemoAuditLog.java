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

/** Ban ghi audit log dung de minh hoa REQUIRES_NEW vs NESTED o muc 20. */
@Entity
@Table(name = "tx_demo_audit_logs")
@Getter
@Setter
@NoArgsConstructor
public class TxDemoAuditLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String message;

    public TxDemoAuditLog(String message) {
        this.message = message;
    }
}
