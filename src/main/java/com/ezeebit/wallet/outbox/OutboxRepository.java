package com.ezeebit.wallet.outbox;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;

public interface OutboxRepository extends JpaRepository<OutboxMessage, Long> {

    /** Due messages, plus messages whose worker died mid-flight (lease expired). */
    @Query("select m.id from OutboxMessage m "
            + "where (m.status = :pending and m.nextAttemptAt <= :now) "
            + "   or (m.status = :inProgress and m.lockedUntil < :now) "
            + "order by m.id")
    List<Long> findDueIds(@Param("now") Instant now,
                          @Param("pending") OutboxStatus pending,
                          @Param("inProgress") OutboxStatus inProgress,
                          Pageable page);

    /**
     * Atomically claims a message for this worker. Returns 1 if claimed, 0 if another worker got it.
     * Works across multiple application instances without SELECT ... SKIP LOCKED.
     */
    @Modifying
    @Query("update OutboxMessage m set m.status = :inProgress, m.lockedUntil = :leaseUntil, "
            + "m.attempts = m.attempts + 1, m.updatedAt = :now "
            + "where m.id = :id and ((m.status = :pending and m.nextAttemptAt <= :now) "
            + "   or (m.status = :inProgress and m.lockedUntil < :now))")
    int claim(@Param("id") long id,
              @Param("now") Instant now,
              @Param("leaseUntil") Instant leaseUntil,
              @Param("pending") OutboxStatus pending,
              @Param("inProgress") OutboxStatus inProgress);

    List<OutboxMessage> findByMessageTypeAndAggregateId(String messageType, String aggregateId);

    List<OutboxMessage> findByStatus(OutboxStatus status);
}
