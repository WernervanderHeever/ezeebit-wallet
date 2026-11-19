package com.ezeebit.wallet.ledger;

public enum AccountPurpose {
    /** Merchant: spendable balance. */
    AVAILABLE(false),
    /** Merchant: funds reserved for in-flight withdrawals. Still the merchant's, not spendable. */
    WITHDRAWAL_HOLD(false),
    /** Platform: counter-account for money arriving from outside. */
    DEPOSIT_CLEARING(true),
    /** Platform: the FX book. Net balance per currency = open position to hedge. */
    FX_LIQUIDITY(true),
    /** Platform: counter-account for money paid out to external destinations. */
    PAYOUT_CLEARING(true);

    private final boolean platformAccount;

    AccountPurpose(boolean platformAccount) {
        this.platformAccount = platformAccount;
    }

    public boolean isPlatformAccount() {
        return platformAccount;
    }
}
