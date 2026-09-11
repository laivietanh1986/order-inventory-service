package com.example.orderinventory.transactional;

import org.springframework.stereotype.Component;

/**
 * Test-support: ghi lai ket qua quan sat duoc tu 2 listener o muc 20 (bay
 * 4/4), de test co the assert sau khi createRecordAndPublishEvent() da
 * chay xong.
 */
@Component
public class EventVisibilityRecorder {

    private volatile Boolean syncListenerSawRecord;
    private volatile Boolean afterCommitListenerSawRecord;

    public void recordSyncListenerVisibility(boolean visible) {
        this.syncListenerSawRecord = visible;
    }

    public void recordAfterCommitListenerVisibility(boolean visible) {
        this.afterCommitListenerSawRecord = visible;
    }

    public Boolean getSyncListenerSawRecord() {
        return syncListenerSawRecord;
    }

    public Boolean getAfterCommitListenerSawRecord() {
        return afterCommitListenerSawRecord;
    }

    public void reset() {
        syncListenerSawRecord = null;
        afterCommitListenerSawRecord = null;
    }
}
