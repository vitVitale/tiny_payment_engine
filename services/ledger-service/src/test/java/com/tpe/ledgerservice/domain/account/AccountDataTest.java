package com.tpe.ledgerservice.domain.account;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.tpe.ledgerservice.domain.LedgerError;
import com.tpe.ledgerservice.domain.LedgerException;
import com.tpe.paymentdomain.Iban;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Customer number and holder rules of specs/12-ledger-service.md §3.2. */
class AccountDataTest {

    private static final LocalDate DOB = LocalDate.parse("1985-04-12");

    @ParameterizedTest
    @ValueSource(strings = {"CUST-000123", "a", "A_b-9", "12345678901234567890123456789012345678901234567890"})
    void acceptsCustomerNumbersOfOneTo50Characters(String value) {
        assertThat(new CustomerId(value).value()).isEqualTo(value);
    }

    @ParameterizedTest
    @ValueSource(strings = {"CUST 1", "CUST/1", "Kündenr", "123456789012345678901234567890123456789012345678901"})
    void refusesMalformedCustomerNumbers(String value) {
        assertThatThrownBy(() -> new CustomerId(value))
                .isInstanceOfSatisfying(InvalidAccountDataException.class, e -> assertThat(e.violations())
                        .containsExactly(new AccountDataViolation("customerId", "INVALID_CUSTOMER_ID")));
    }

    @Test
    void personHolderMayHaveDateOfBirthAndCountry() {
        Holder holder = Holder.of(HolderType.PERSON, "Anna Schmidt", DOB, "DE", null);

        assertThat(holder.name().value()).isEqualTo("Anna Schmidt");
        assertThat(holder.dateOfBirth()).isEqualTo(DOB);
    }

    @Test
    void organisationNeedsAnOrganisationIdAndNoDateOfBirth() {
        assertThatThrownBy(() -> Holder.of(HolderType.ORGANISATION, "ACME GmbH", DOB, "DE", null))
                .isInstanceOfSatisfying(InvalidAccountDataException.class, e -> assertThat(e.violations())
                        .containsExactlyInAnyOrder(
                                new AccountDataViolation("holder.organisationId", "REQUIRED"),
                                new AccountDataViolation("holder.dateOfBirth", "NOT_ALLOWED")));

        assertThat(Holder.of(HolderType.ORGANISATION, "ACME GmbH", null, "DE", "529900T8BM49AURSDO55")
                .organisationId()).isEqualTo("529900T8BM49AURSDO55");
    }

    @Test
    void holderNeedsTypeAndAValidName() {
        assertThatThrownBy(() -> Holder.of(null, " ", null, null, null))
                .isInstanceOfSatisfying(InvalidAccountDataException.class, e -> assertThat(e.violations())
                        .containsExactlyInAnyOrder(
                                new AccountDataViolation("holder.type", "REQUIRED"),
                                new AccountDataViolation("holder.name", "REQUIRED")));
        assertThatThrownBy(() -> Holder.of(HolderType.PERSON, "x".repeat(71), null, null, null))
                .isInstanceOfSatisfying(InvalidAccountDataException.class, e -> assertThat(e.violations())
                        .containsExactly(new AccountDataViolation("holder.name", "TOO_LONG")));
    }

    @Test
    void countryOfResidenceIsAnIsoAlpha2CodeAndOrganisationIdAtMost35Characters() {
        assertThatThrownBy(() -> Holder.of(HolderType.ORGANISATION, "ACME", null, "deu", "x".repeat(36)))
                .isInstanceOfSatisfying(InvalidAccountDataException.class, e -> assertThat(e.violations())
                        .containsExactlyInAnyOrder(
                                new AccountDataViolation("holder.countryOfResidence", "INVALID_COUNTRY"),
                                new AccountDataViolation("holder.organisationId", "TOO_LONG")));
    }

    @Test
    void opensACustomerAccountOpenWithZeroBalancesAtRevision1() {
        Instant now = Instant.parse("2026-10-31T10:00:00.123Z");
        Holder holder = Holder.of(HolderType.PERSON, "Anna Schmidt", DOB, "DE", null);
        UUID id = UUID.randomUUID();
        Iban iban = new IbanGenerator("10010010").generate(3);

        Account account = Account.openCustomer(id, iban, new CustomerId("CUST-000123"), holder, now);

        assertThat(account.accountId()).isEqualTo(id);
        assertThat(account.kind()).isEqualTo(AccountKind.CUSTOMER);
        assertThat(account.status()).isEqualTo(AccountStatus.OPEN);
        assertThat(account.currency()).isEqualTo("EUR");
        assertThat(account.bookedMinor()).isZero();
        assertThat(account.availableMinor()).isZero();
        assertThat(account.revision()).isEqualTo(1);
        assertThat(account.openedAt()).isEqualTo(now);
        assertThat(account.closedAt()).isNull();
    }

    @Test
    void aCustomerAccountWithoutCustomerNumberIsRefused() {
        Holder holder = Holder.of(HolderType.PERSON, "Anna Schmidt", DOB, "DE", null);
        Iban iban = new IbanGenerator("10010010").generate(3);

        assertThatThrownBy(() -> Account.openCustomer(UUID.randomUUID(), iban, null, holder, Instant.EPOCH))
                .isInstanceOfSatisfying(LedgerException.class,
                        e -> assertThat(e.error()).isEqualTo(LedgerError.CUSTOMER_ID_MISSING));
    }

    @Test
    void availableBalanceIsBookedMinusHeld() {
        Account account = new Account(UUID.randomUUID(), new IbanGenerator("10010010").generate(3),
                AccountKind.CUSTOMER, new CustomerId("C1"), Holder.of(HolderType.PERSON, "A", null, null, null),
                AccountStatus.OPEN, Account.EUR, 10_000, 2_500, 0, 1, Instant.EPOCH, null);

        assertThat(account.availableMinor()).isEqualTo(7_500);
    }

    @Test
    void anAccountInAnotherCurrencyThanEuroIsRefused() {
        assertThatThrownBy(() -> new Account(UUID.randomUUID(), new IbanGenerator("10010010").generate(3),
                AccountKind.CUSTOMER, new CustomerId("C1"), Holder.of(HolderType.PERSON, "A", null, null, null),
                AccountStatus.OPEN, "USD", 0, 0, 0, 1, Instant.EPOCH, null))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
