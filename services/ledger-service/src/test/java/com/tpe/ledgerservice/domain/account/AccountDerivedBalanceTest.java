package com.tpe.ledgerservice.domain.account;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

import com.tpe.contracts.TechnicalAccounts;
import com.tpe.paymentdomain.Iban;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

/** A technical account read with its derived balance (specs/12-ledger-service.md §3.3, §6.3). */
class AccountDerivedBalanceTest {

    private static final Instant AT = Instant.parse("2026-01-05T08:00:00Z");

    @Test
    void aTechnicalAccountReadsWithItsDerivedBalanceAsBookedAndAvailable() {
        Account technical = account(AccountKind.TECHNICAL, null).withDerivedBalance(-7_500);

        assertThat(technical.bookedMinor()).isEqualTo(-7_500);
        assertThat(technical.availableMinor()).isEqualTo(-7_500);
    }

    @Test
    void aCustomerAccountHasNoDerivedBalance() {
        assertThatIllegalStateException().isThrownBy(
                () -> account(AccountKind.CUSTOMER, new CustomerId("C1")).withDerivedBalance(1));
    }

    private static Account account(AccountKind kind, CustomerId customerId) {
        return new Account(TechnicalAccounts.CSM_SETTLEMENT_ID, new Iban("DE89370400440532013000"), kind, customerId,
                customerId == null ? null : Holder.of(HolderType.PERSON, "Anna Schmidt", null, null, null),
                AccountStatus.OPEN, List.of(), Account.EUR, 0, 0, 0, 0, 1, AT, null);
    }
}
