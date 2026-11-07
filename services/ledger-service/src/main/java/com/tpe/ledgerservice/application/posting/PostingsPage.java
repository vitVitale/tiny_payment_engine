package com.tpe.ledgerservice.application.posting;

import java.util.List;

/**
 * A page of an account's postings, newest first.
 *
 * @param nextBeforePostingId the {@code beforePostingId} of the next page, {@code null} on the last one
 */
public record PostingsPage(List<AccountPosting> postings, Long nextBeforePostingId) {

    public PostingsPage {
        postings = List.copyOf(postings);
    }
}
