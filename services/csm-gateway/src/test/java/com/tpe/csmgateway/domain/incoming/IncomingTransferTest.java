package com.tpe.csmgateway.domain.incoming;

import static org.assertj.core.api.Assertions.assertThat;

import com.tpe.csmgateway.domain.incoming.IncomingTransfer.AnswerStatus;
import com.tpe.csmgateway.domain.incoming.IncomingTransfer.DecidedBy;
import com.tpe.csmgateway.domain.transmit.TransmitStatus;
import com.tpe.paymentdomain.Bic;
import com.tpe.paymentdomain.MsgId;
import com.tpe.paymentdomain.TxId;
import java.time.Instant;
import org.junit.jupiter.api.Test;

/** An incoming transfer awaits its answer until one is decided (specs/15-csm-gateway.md §5). */
class IncomingTransferTest {

    private static final Bic PEER = new Bic("PEERDEFFXXX");
    private static final Instant RECEIVED = Instant.parse("2026-11-12T10:00:00Z");
    private static final Instant DEADLINE = RECEIVED.plusSeconds(5);

    @Test
    void aReceivedTransferIsNotAnsweredYet() {
        var transfer = IncomingTransfer.received(PEER, new TxId("TX-1"), new MsgId("MSG-1"), RECEIVED, DEADLINE,
                null);

        assertThat(transfer.answered()).isFalse();
        assertThat(transfer.answerStatus()).isNull();
    }

    @Test
    void aTransferRejectedOnReceiptIsAnsweredWithItsAnswerDueForTransmission() {
        var transfer = IncomingTransfer.rejectedOnReceipt(PEER, new TxId("TX-1"), new MsgId("MSG-1"), RECEIVED,
                DEADLINE, "FF01", DecidedBy.VALIDATION, new MsgId("ANS-1"), null);

        assertThat(transfer.answered()).isTrue();
        assertThat(transfer.answerStatus()).isEqualTo(AnswerStatus.RJCT);
        assertThat(transfer.transmitStatus()).isEqualTo(TransmitStatus.PENDING);
    }
}
