package com.ezeebit.wallet.withdrawal;

public class PayoutRailUnavailableException extends RuntimeException {

    public PayoutRailUnavailableException(String message) {
        super(message);
    }
}
