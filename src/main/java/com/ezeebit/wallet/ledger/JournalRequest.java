package com.ezeebit.wallet.ledger;

import java.util.List;

/**
 * One business event to record. (type, referenceType, referenceId) is unique in the database,
 * so the same event can never be posted twice.
 */
public record JournalRequest(JournalEntryType type,
                             String referenceType,
                             String referenceId,
                             String description,
                             List<JournalLine> lines) {
}
