package com.tpe.paymentorchestrator.infrastructure.http;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import org.junit.jupiter.api.Test;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.client.RestClientException;
import tools.jackson.core.exc.StreamReadException;

class CutOffAnswersTest {

    /** As the JDK client reports a read timeout that cancels a body already under way. */
    @Test
    void anAnswerWhoseBodyBrokeOffInTransitIsCutOff() {
        var cancelled = new IOException("closed", new IOException("chunked transfer encoding, state: READING_DATA",
                new IOException("subscription cancelled")));

        assertThat(CutOffAnswers.cutOff(new RestClientException("Error while extracting response", cancelled)))
                .isTrue();
    }

    /** Asking again would get the same. */
    @Test
    void aBodyThatIsNoAnswerIsNotCutOff() {
        var malformed = new HttpMessageNotReadableException("JSON parse error",
                new StreamReadException(null, "Unexpected end-of-input"), null);

        assertThat(CutOffAnswers.cutOff(new RestClientException("Error while extracting response", malformed)))
                .isFalse();
    }
}
