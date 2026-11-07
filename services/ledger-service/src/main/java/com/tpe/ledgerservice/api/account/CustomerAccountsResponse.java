package com.tpe.ledgerservice.api.account;

import com.tpe.ledgerservice.domain.account.Account;
import java.util.List;

/** {@code GET /v1/customers/{customerId}/accounts} (specs/12-ledger-service.md §3.2): every account, in any status. */
record CustomerAccountsResponse(List<AccountResponse> accounts) {

    static CustomerAccountsResponse of(List<Account> accounts) {
        return new CustomerAccountsResponse(accounts.stream().map(AccountResponse::of).toList());
    }
}
