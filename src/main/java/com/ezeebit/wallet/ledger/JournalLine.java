package com.ezeebit.wallet.ledger;

import java.math.BigDecimal;

/** A signed movement on one account: positive increases the balance, negative decreases it. */
public record JournalLine(AccountRef account, BigDecimal amount) {

    public static JournalLine credit(AccountRef account, BigDecimal amount) {
        return new JournalLine(account, amount);
    }

    public static JournalLine debit(AccountRef account, BigDecimal amount) {
        return new JournalLine(account, amount.negate());
    }
}
