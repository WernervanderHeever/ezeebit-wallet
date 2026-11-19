package com.ezeebit.wallet.outbox;

public class PermanentOutboxFailure extends RuntimeException {

    public PermanentOutboxFailure(String message) {
        super(message);
    }
}
