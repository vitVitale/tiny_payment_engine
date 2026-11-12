package com.tpe.contracts.vop;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.tpe.contracts.ContractsModule;
import com.tpe.paymentdomain.Bic;
import com.tpe.paymentdomain.DomainValidationException;
import com.tpe.paymentdomain.Iban;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/** The JSON of vop-service's internal API (specs/13-vop-service.md §3.1) with {@link ContractsModule}. */
class VopApiJsonTest {

    private final JsonMapper json = JsonMapper.builder().addModule(new ContractsModule()).build();

    @Test
    void aVerificationRequestReadsIbansAsValueObjects() {
        VerificationRequest request = json.readValue("""
                {"debtorIban":"DE36100100100000000001","payeeIban":"de07 2002 0020 0123 4567 89",
                 "payeeName":"Max Mustermann","payeeOrganisationId":null}""", VerificationRequest.class);

        assertThat(request).isEqualTo(new VerificationRequest(new Iban("DE36100100100000000001"),
                new Iban("DE07200200200123456789"), "Max Mustermann", null));
    }

    @Test
    void anInvalidIbanFailsTheReadWithTheDomainValidation() {
        assertThatThrownBy(() -> json.readValue("""
                {"debtorIban":"DE00100100100000000001","payeeIban":"DE07200200200123456789","payeeName":"Max"}""",
                VerificationRequest.class))
                .hasRootCauseInstanceOf(DomainValidationException.class);
    }

    @Test
    void aVerificationResponseWritesEveryField() {
        var response = new VerificationResponse("VOP-01JAB", VopResult.CLOSE_MATCH, "Max Mustermann GmbH",
                Instant.parse("2026-11-12T10:30:00Z"), new Bic("PEERDEFFXXX"));

        assertThat(json.writeValueAsString(response)).isEqualTo("""
                {"vopReference":"VOP-01JAB","result":"CLOSE_MATCH","actualName":"Max Mustermann GmbH",\
                "expiresAt":"2026-11-12T10:30:00.000Z","respondedBy":"PEERDEFFXXX"}""");
        assertThat(json.writeValueAsString(new VerificationResponse("VOP-01JAB", VopResult.MATCH, null,
                Instant.parse("2026-11-12T10:30:00Z"), new Bic("TPEBDEFFXXX"))))
                .contains("\"actualName\":null");
    }

    @Test
    void aCheckResponseLeavesOutWhatDoesNotApply() {
        assertThat(json.writeValueAsString(ReferenceCheckResponse.valid(VopResult.CLOSE_MATCH)))
                .isEqualTo("{\"valid\":true,\"result\":\"CLOSE_MATCH\"}");
        assertThat(json.writeValueAsString(ReferenceCheckResponse.invalid(VopReferenceProblem.EXPIRED)))
                .isEqualTo("{\"valid\":false,\"problem\":\"EXPIRED\"}");
    }

    @Test
    void aCheckRequestRoundTrips() {
        var request = new ReferenceCheckRequest(new Iban("DE36100100100000000001"),
                new Iban("DE07200200200123456789"), "Max Mustermann");

        assertThat(json.readValue(json.writeValueAsString(request), ReferenceCheckRequest.class)).isEqualTo(request);
    }
}
