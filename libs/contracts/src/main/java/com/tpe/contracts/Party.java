package com.tpe.contracts;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.tpe.paymentdomain.Iban;
import com.tpe.paymentdomain.PartyName;
import java.util.UUID;

/**
 * A debtor or creditor (specs/02-messaging-contracts.md §1). {@code accountId} is the ledger account id, present
 * only when the party is our customer and the producer knows it.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record Party(PartyName name, Iban iban, @OptionalField UUID accountId) {}
