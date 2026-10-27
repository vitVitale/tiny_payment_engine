package com.tpe.paymentdomain;

import static com.tpe.paymentdomain.ValidationCode.INVALID_BANK_DIRECTORY;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * A {@link BankDirectory} built from its entries. The services bind them from the YAML file at
 * {@code tpe.bank-directory.location}; this class only checks that they form a valid directory.
 */
public final class InMemoryBankDirectory implements BankDirectory {

    private final String ownBankCode;
    private final Bic ownBic;
    private final Map<String, BankEntry> byBankCode;

    public InMemoryBankDirectory(String ownBankCode, Bic ownBic, List<BankEntry> banks) {
        this.ownBankCode = Objects.requireNonNull(ownBankCode, "ownBankCode");
        this.ownBic = Objects.requireNonNull(ownBic, "ownBic");
        Map<String, BankEntry> entries = new HashMap<>();
        for (BankEntry bank : banks) {
            if (entries.putIfAbsent(bank.bankCode(), bank) != null) {
                throw new DomainValidationException(
                        INVALID_BANK_DIRECTORY, "bank code " + bank.bankCode() + " is listed twice");
            }
        }
        BankEntry own = entries.get(ownBankCode);
        if (own == null) {
            throw new DomainValidationException(
                    INVALID_BANK_DIRECTORY, "own bank code " + ownBankCode + " has no entry");
        }
        if (!own.bic().equals(ownBic)) {
            throw new DomainValidationException(
                    INVALID_BANK_DIRECTORY, "own BIC " + ownBic.value() + " differs from the own bank's entry");
        }
        this.byBankCode = Map.copyOf(entries);
    }

    @Override
    public Optional<BankEntry> lookup(Iban iban) {
        return Optional.ofNullable(byBankCode.get(iban.bankCode()))
                .filter(bank -> bank.bic().countryCode().equals(iban.countryCode()));
    }

    @Override
    public boolean isOwn(Iban iban) {
        return iban.bankCode().equals(ownBankCode) && iban.countryCode().equals(ownBic.countryCode());
    }

    @Override
    public Bic ownBic() {
        return ownBic;
    }
}
