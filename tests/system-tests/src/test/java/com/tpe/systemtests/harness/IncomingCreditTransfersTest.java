package com.tpe.systemtests.harness;

import static org.assertj.core.api.Assertions.assertThat;

import com.tpe.iso20022model.EnvelopeCodec;
import com.tpe.iso20022model.EnvelopeCodec.Accepted;
import com.tpe.iso20022model.EnvelopeCodec.Rejected;
import com.tpe.iso20022model.pacs008v08.Document;
import java.net.URI;
import org.junit.jupiter.api.Test;

/** E2E-I-09: the {@code pacs.008} a test sends csm-gateway through the simulator's raw endpoint. */
class IncomingCreditTransfersTest {

    private static final EnvelopeCodec ENVELOPES = new EnvelopeCodec();
    private static final String CREDITOR = "DE36100100100000000001";

    @Test
    void theBaseMessageIsAValidCreditTransferFromThePeerBankToOurs() {
        String xml = IncomingCreditTransfers.valid("SYSTESTTX-1", CREDITOR);

        assertThat(ENVELOPES.split(xml)).isInstanceOf(Accepted.class);
        var transaction = ((Document) ((Accepted) ENVELOPES.split(xml)).envelope().document()).getFIToFICstmrCdtTrf()
                .getCdtTrfTxInf().getFirst();
        assertThat(transaction.getPmtId().getTxId()).isEqualTo("SYSTESTTX-1");
        assertThat(transaction.getDbtrAgt().getFinInstnId().getBICFI()).isEqualTo("PEERDEFFXXX");
        assertThat(transaction.getCdtrAgt().getFinInstnId().getBICFI()).isEqualTo("TPEBDEFFXXX");
        assertThat(transaction.getCdtrAcct().getId().getIBAN()).isEqualTo(CREDITOR);
    }

    @Test
    void aSchemaInvalidMessageKeepsItsIdentityReadable() {
        String xml = IncomingCreditTransfers.schemaInvalid("SYSTESTTX-2", CREDITOR);

        assertThat(ENVELOPES.split(xml)).isInstanceOf(Rejected.class);
        assertThat(xml).contains("<TxId>SYSTESTTX-2</TxId>", "<BICFI>PEERDEFFXXX</BICFI>");
    }

    @Test
    void aMessageWithADoctypeReferencesItsExternalEntityInTheDebtorsName() {
        URI secret = URI.create("file:///tmp/secret.txt");

        String xml = IncomingCreditTransfers.withDoctype("SYSTESTTX-3", CREDITOR, secret);

        assertThat(xml).contains("<!DOCTYPE Envelope [<!ENTITY xxe SYSTEM \"file:///tmp/secret.txt\">]><Envelope ",
                "<Nm>&xxe;</Nm>", "<TxId>SYSTESTTX-3</TxId>");
        assertThat(ENVELOPES.split(xml)).isInstanceOf(Rejected.class);
    }
}
