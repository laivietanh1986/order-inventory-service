package com.example.orderinventory.transactional;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Bay 4/4 (muc 20): publish su kien TRONG LUC transaction dang mo (truoc
 * commit). {@link org.springframework.context.event.EventListener} thuong
 * chay DONG BO ngay tai diem publishEvent() goi - tuc la VAN O BEN TRONG
 * transaction nay, TRUOC khi no commit.
 */
@Service
public class EventPublishingService {

    private final TxDemoRecordRepository recordRepository;
    private final ApplicationEventPublisher eventPublisher;

    public EventPublishingService(TxDemoRecordRepository recordRepository, ApplicationEventPublisher eventPublisher) {
        this.recordRepository = recordRepository;
        this.eventPublisher = eventPublisher;
    }

    @Transactional
    public Long createRecordAndPublishEvent(String label) {
        TxDemoRecord saved = recordRepository.save(new TxDemoRecord(label));
        eventPublisher.publishEvent(new TxDemoRecordCreatedEvent(saved.getId()));
        return saved.getId();
    }
}
