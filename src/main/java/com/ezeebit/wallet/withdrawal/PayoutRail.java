package com.ezeebit.wallet.withdrawal;

import com.ezeebit.wallet.common.CurrencyCode;

import java.math.BigDecimal;

/**
 * The existing, asynchronous payout rail (assumed).
 *
 * <p>Assumption: the rail de-duplicates on {@link PayoutInstruction#reference()} - submitting the
 * same reference twice returns the original acceptance and pays out once. That is what makes it
 * safe for us to retry a submission whose outcome we did not hear (timeout).
 */
public interface PayoutRail {

    /**
     * @throws PayoutRailUnavailableException when the outcome is unknown or the rail is temporarily
     *                                        unavailable (timeout, 5xx). Safe to retry with the same reference.
     */
    SubmitResult submit(PayoutInstruction instruction);

    record PayoutInstruction(String reference, CurrencyCode currency, BigDecimal amount,
                             DestinationType destinationType, String destination) {
    }

    record SubmitResult(boolean accepted, String providerReference, String rejectionReason) {

        public static SubmitResult accepted(String providerReference) {
            return new SubmitResult(true, providerReference, null);
        }

        /** A definitive rejection: nothing was or will be paid out for this reference. */
        public static SubmitResult rejected(String reason) {
            return new SubmitResult(false, null, reason);
        }
    }
}
