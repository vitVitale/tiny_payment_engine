package com.tpe.paymentapi.application.verification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.tpe.contracts.vop.VerificationRequest;
import com.tpe.contracts.vop.VerificationResponse;
import com.tpe.contracts.vop.VopResult;
import com.tpe.paymentapi.application.submission.Failure;
import com.tpe.paymentapi.application.submission.VopUnavailableException;
import com.tpe.paymentapi.application.validation.FieldError;
import com.tpe.paymentapi.application.validation.ValidationFailedException;
import com.tpe.paymentdomain.Bic;
import com.tpe.paymentdomain.Iban;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/** specs/10-payment-api.md §3.4, UC-02 1a: the checks before vop-service and what it answers. */
class PayeeVerificationTest {

    private static final String DEBTOR = "DE36100100100000000001";
    private static final String PAYEE = "DE63200200200000000001";
    private static final VerificationResponse VERIFIED = new VerificationResponse("VOP-01JABCDEFGHJKMNPQRSTVWXYZ0",
            VopResult.CLOSE_MATCH, "Max Mustermann GmbH", Instant.parse("2026-11-14T10:30:00Z"),
            new Bic("PEERDEFFXXX"));

    private final FakeVop vop = new FakeVop();
    private final PayeeVerification verification = new PayeeVerification(vop);

    @Test
    void aValidRequestIsForwardedAndItsAnswerReturnedUnchanged() {
        vop.answer = new PayeeVerifications.Verified(VERIFIED);

        assertThat(verification.verify(new PayeeVerification.Request(DEBTOR, PAYEE, "Max Mustermann", "HRB 1")))
                .isSameAs(VERIFIED);

        assertThat(vop.asked).containsExactly(new VerificationRequest(new Iban(DEBTOR), new Iban(PAYEE),
                "Max Mustermann", "HRB 1"));
    }

    @Test
    void aBlankOrganisationIdIsNone() {
        vop.answer = new PayeeVerifications.Verified(VERIFIED);

        verification.verify(new PayeeVerification.Request(DEBTOR, PAYEE, "Max Mustermann", " "));

        assertThat(vop.asked.getFirst().payeeOrganisationId()).isNull();
    }

    /** UC-02 1a: no call for an IBAN that fails its checksum. */
    @Test
    void anInvalidIbanIsAFieldErrorWithoutAsking() {
        assertThatThrownBy(() -> verification.verify(
                new PayeeVerification.Request(DEBTOR, "DE00200200200000000001", "Max Mustermann", null)))
                .isInstanceOfSatisfying(ValidationFailedException.class, e -> assertThat(e.errors())
                        .containsExactly(new FieldError("payeeIban", "INVALID_IBAN")));
        assertThat(vop.asked).isEmpty();
    }

    @Test
    void everyInvalidFieldIsReported() {
        assertThatThrownBy(() -> verification.verify(
                new PayeeVerification.Request(null, " ", "M".repeat(71), "O".repeat(36))))
                .isInstanceOfSatisfying(ValidationFailedException.class, e -> assertThat(e.errors())
                        .containsExactly(new FieldError("debtorIban", "REQUIRED"),
                                new FieldError("payeeIban", "REQUIRED"), new FieldError("payeeName", "TOO_LONG"),
                                new FieldError("payeeOrganisationId", "TOO_LONG")));
        assertThatThrownBy(() -> verification.verify(new PayeeVerification.Request(DEBTOR, PAYEE, " ", null)))
                .isInstanceOfSatisfying(ValidationFailedException.class, e -> assertThat(e.errors())
                        .containsExactly(new FieldError("payeeName", "REQUIRED")));
        assertThat(vop.asked).isEmpty();
    }

    @Test
    void aPayeeBankOutsideTheDirectoryIsUnknown() {
        vop.answer = new PayeeVerifications.PayeeBankUnknown();

        assertThatThrownBy(() -> verification.verify(
                new PayeeVerification.Request(DEBTOR, PAYEE, "Max Mustermann", null)))
                .isInstanceOf(PayeeBankUnknownException.class);
    }

    /** vop-service enforces the responder timeout itself; no answer means it is down, and nothing is retried. */
    @ParameterizedTest
    @EnumSource(Failure.class)
    void aVopServiceThatDoesNotAnswerIsUnavailable(Failure failure) {
        vop.answer = new PayeeVerifications.Unavailable(failure, "down");

        assertThatThrownBy(() -> verification.verify(
                new PayeeVerification.Request(DEBTOR, PAYEE, "Max Mustermann", null)))
                .isInstanceOf(VopUnavailableException.class);
        assertThat(vop.asked).hasSize(1);
    }

    private static final class FakeVop implements PayeeVerifications {

        PayeeVerifications.Answer answer;
        final List<VerificationRequest> asked = new ArrayList<>();

        @Override
        public Answer verify(VerificationRequest request) {
            asked.add(request);
            return answer;
        }
    }
}
