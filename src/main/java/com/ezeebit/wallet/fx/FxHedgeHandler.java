package com.ezeebit.wallet.fx;

import com.ezeebit.wallet.outbox.OutboxHandler;
import com.ezeebit.wallet.outbox.OutboxMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Publishes each executed conversion to the hedging desk / liquidity provider so the platform's FX
 * position is closed out promptly. Here it only logs; the real integration is assumed to exist.
 * Delivered via the outbox, so a conversion is never left un-hedged because of a crash.
 */
@Component
public class FxHedgeHandler implements OutboxHandler {

    private static final Logger log = LoggerFactory.getLogger(FxHedgeHandler.class);

    private final FxConversionRepository conversions;

    public FxHedgeHandler(FxConversionRepository conversions) {
        this.conversions = conversions;
    }

    @Override
    public String messageType() {
        return ConversionService.HEDGE_MESSAGE;
    }

    @Override
    public void handle(OutboxMessage message) {
        FxConversion c = conversions.findById(message.getAggregateId()).orElseThrow();
        log.info("HEDGE conversion={} platform received {} {} and paid {} {} @ {}",
                c.getId(), c.getFromAmount().toPlainString(), c.getFromCurrency(),
                c.getToAmount().toPlainString(), c.getToCurrency(), c.getClientPrice().toPlainString());
    }
}
