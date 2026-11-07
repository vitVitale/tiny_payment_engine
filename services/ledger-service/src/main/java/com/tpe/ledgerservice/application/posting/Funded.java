package com.tpe.ledgerservice.application.posting;

import com.tpe.ledgerservice.domain.account.Account;
import com.tpe.ledgerservice.domain.posting.JournalEntry;

/** A booked funding: its journal entry and the customer account with its new balances. */
public record Funded(JournalEntry entry, Account account, long amountMinor) {}
