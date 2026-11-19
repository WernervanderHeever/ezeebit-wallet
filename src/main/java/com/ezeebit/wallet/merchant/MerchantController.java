package com.ezeebit.wallet.merchant;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;

@RestController
@RequestMapping("/api/v1/merchants")
public class MerchantController {

    private final MerchantService service;

    public MerchantController(MerchantService service) {
        this.service = service;
    }

    public record CreateMerchantRequest(@NotBlank String name,
                                        @NotBlank @Pattern(regexp = "[A-Za-z]{2}") String country,
                                        @NotBlank String localCurrency) {
    }

    public record OpenCurrencyRequest(@NotBlank String currency) {
    }

    public record MerchantResponse(long id, String name, String country, String localCurrency, Instant createdAt) {
        static MerchantResponse of(Merchant m) {
            return new MerchantResponse(m.getId(), m.getName(), m.getCountry(), m.getLocalCurrency().name(), m.getCreatedAt());
        }
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public MerchantResponse create(@Valid @RequestBody CreateMerchantRequest request) {
        return MerchantResponse.of(service.create(request.name(), request.country(), request.localCurrency()));
    }

    @GetMapping("/{merchantId}")
    public MerchantResponse get(@PathVariable long merchantId) {
        return MerchantResponse.of(service.require(merchantId));
    }

    @PostMapping("/{merchantId}/accounts")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void openCurrency(@PathVariable long merchantId, @Valid @RequestBody OpenCurrencyRequest request) {
        service.openCurrency(merchantId, request.currency());
    }
}
