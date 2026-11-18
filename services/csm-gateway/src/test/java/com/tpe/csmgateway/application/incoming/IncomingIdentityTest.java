package com.tpe.csmgateway.application.incoming;

import static com.tpe.csmgateway.IncomingPacs008.PEER_BIC;
import static com.tpe.csmgateway.IncomingPacs008.pacs008;
import static com.tpe.csmgateway.IncomingPacs008.schemaInvalid;
import static org.assertj.core.api.Assertions.assertThat;

import com.tpe.csmgateway.IncomingPacs008;
import com.tpe.paymentdomain.EndToEndId;
import com.tpe.paymentdomain.MsgId;
import com.tpe.paymentdomain.TxId;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Reading the identity of a {@code pacs.008} that failed validation (specs/15-csm-gateway.md §6.2, GW-AC-14). */
class IncomingIdentityTest {

    private static final Instant AT = Instant.parse("2026-11-15T10:00:00Z");

    @Test
    void aSchemaInvalidPacs008IsNamedByItsTxIdAndDebtorAgent() {
        IncomingIdentity identity = IncomingIdentity.read(schemaInvalid(pacs008("MSG-1", "TX-1", PEER_BIC, AT)))
                .orElseThrow();

        assertThat(identity).isEqualTo(new IncomingIdentity(new TxId("TX-1"), PEER_BIC, new MsgId("MSG-1"),
                new EndToEndId("E2E-TX-1"), IncomingPacs008.uetr("TX-1")));
    }

    @Test
    void whatComesBeforeTheXmlBreaksStillCounts() {
        String valid = pacs008("MSG-1", "TX-1", PEER_BIC, AT);
        String truncated = valid.substring(0, valid.indexOf("<Cdtr>"));

        assertThat(IncomingIdentity.read(truncated)).hasValueSatisfying(identity -> {
            assertThat(identity.txId()).isEqualTo(new TxId("TX-1"));
            assertThat(identity.debtorAgent()).isEqualTo(PEER_BIC);
        });
    }

    @Test
    void GW_AC_14_aDoctypeIsSkippedAndItsEntitiesAreNeverResolved(@TempDir Path dir) throws Exception {
        Path secret = Files.writeString(dir.resolve("secret.txt"), "LEAKED");
        String valid = pacs008("MSG-1", "TX-1", PEER_BIC, AT);
        String withDoctype = valid.replaceFirst("<Envelope ", "<!DOCTYPE Envelope [<!ENTITY xxe SYSTEM \""
                + secret.toUri() + "\"><!ENTITY inner \"INNER\">]><Envelope ")
                .replace("<EndToEndId>E2E-TX-1</EndToEndId>", "<EndToEndId>&inner;</EndToEndId>")
                .replace("<Nm>Hans Mueller</Nm>", "<Nm>&xxe;</Nm>");

        var identity = IncomingIdentity.read(withDoctype);

        assertThat(identity).hasValueSatisfying(read -> {
            assertThat(read.txId()).isEqualTo(new TxId("TX-1"));
            assertThat(read.debtorAgent()).isEqualTo(PEER_BIC);
            assertThat(read.endToEndId()).as("an internal entity is not expanded either").isNull();
        });
        assertThat(identity.toString()).doesNotContain("LEAKED", "INNER");
    }

    @Test
    void anEntityInTheTxIdLeavesItUnreadable(@TempDir Path dir) throws Exception {
        Path secret = Files.writeString(dir.resolve("secret.txt"), "LEAKED");
        String valid = pacs008("MSG-1", "TX-1", PEER_BIC, AT);
        String withDoctype = valid.replaceFirst("<Envelope ", "<!DOCTYPE Envelope [<!ENTITY xxe SYSTEM \""
                + secret.toUri() + "\">]><Envelope ").replace("<TxId>TX-1</TxId>", "<TxId>&xxe;</TxId>");

        assertThat(IncomingIdentity.read(withDoctype)).isEmpty();
    }

    @Test
    void aPacs008IsRecognisedEvenWithoutItsIdentity() {
        String valid = pacs008("MSG-1", "TX-1", PEER_BIC, AT);

        assertThat(IncomingIdentity.isCreditTransfer(valid.replace("<TxId>TX-1</TxId>", ""))).isTrue();
        assertThat(IncomingIdentity.isCreditTransfer("<Document><FIToFIPmtStsRpt/></Document>")).isFalse();
        assertThat(IncomingIdentity.isCreditTransfer("<not-even-xml")).isFalse();
    }

    @Test
    void withoutATxIdOrADebtorAgentThereIsNoIdentity() {
        String valid = pacs008("MSG-1", "TX-1", PEER_BIC, AT);

        assertThat(IncomingIdentity.read(valid.replace("<TxId>TX-1</TxId>", ""))).isEmpty();
        assertThat(IncomingIdentity.read(valid.replaceAll("(?s)<DbtrAgt>.*?</DbtrAgt>", ""))).isEmpty();
        assertThat(IncomingIdentity.read("<not-even-xml")).isEmpty();
        assertThat(IncomingIdentity.read("<TxId>TX-1</TxId>")).as("outside a pacs.008").isEmpty();
    }
}
