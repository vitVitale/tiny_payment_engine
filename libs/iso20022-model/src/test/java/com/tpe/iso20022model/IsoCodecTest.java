package com.tpe.iso20022model;

import static com.tpe.iso20022model.GoldenSamples.golden;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class IsoCodecTest {

    private final IsoCodec codec = new IsoCodec();

    static Stream<Arguments> versions() {
        return Stream.of(
                Arguments.of("head.001.001.02", com.tpe.iso20022model.head001v02.BusinessApplicationHeaderV02.class),
                Arguments.of("pain.001.001.09", com.tpe.iso20022model.pain001v09.Document.class),
                Arguments.of("pain.002.001.10", com.tpe.iso20022model.pain002v10.Document.class),
                Arguments.of("pacs.008.001.08", com.tpe.iso20022model.pacs008v08.Document.class),
                Arguments.of("pacs.002.001.10", com.tpe.iso20022model.pacs002v10.Document.class),
                Arguments.of("pacs.028.001.03", com.tpe.iso20022model.pacs028v03.Document.class),
                Arguments.of("camt.054.001.08", com.tpe.iso20022model.camt054v08.Document.class));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("versions")
    void ISO_AC_03_roundTripOfTheGoldenSampleEqualsTheOriginal(String version, Class<?> type) {
        Object original = codec.unmarshal(golden(version + ".xml"), type);

        Object roundTripped = codec.unmarshal(codec.marshal(original), type);

        assertThat(roundTripped).isEqualTo(original).hasSameHashCodeAs(original).isNotSameAs(original);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("versions")
    void marshalledGoldenSampleIsSchemaValid(String version, Class<?> type) {
        String xml = codec.marshal(codec.unmarshal(golden(version + ".xml"), type));

        assertThat(xml).contains("urn:iso:std:iso:20022:tech:xsd:" + version);
        assertThat(new XsdValidator().validate(xml)).isEmpty();
    }

    @Test
    void equalityIsByValue() {
        var original = codec.unmarshal(golden("pacs.008.001.08.xml"), com.tpe.iso20022model.pacs008v08.Document.class);
        var changed = codec.unmarshal(golden("pacs.008.001.08.xml"), com.tpe.iso20022model.pacs008v08.Document.class);

        changed.getFIToFICstmrCdtTrf().getGrpHdr().setMsgId("TPEM01JAQ5V6K1W8Y3Z4X9R2T7M5NC");

        assertThat(changed).isNotEqualTo(original);
    }

    @Test
    void unmarshalReadsTheDocumentContent() {
        var document = codec.unmarshal(golden("pacs.008.001.08.xml"), com.tpe.iso20022model.pacs008v08.Document.class);

        var transfer = document.getFIToFICstmrCdtTrf();
        assertThat(transfer.getGrpHdr().getMsgId()).isEqualTo("TPEM01JAQ5V6K1W8Y3Z4X9R2T7M5NB");
        assertThat(transfer.getCdtTrfTxInf()).singleElement().satisfies(tx -> {
            assertThat(tx.getPmtId().getTxId()).isEqualTo("TPE01JAQ5V6K1W8Y3Z4X9R2T7M5NA");
            assertThat(tx.getIntrBkSttlmAmt().getValue()).isEqualByComparingTo(new BigDecimal("123.45"));
            assertThat(tx.getIntrBkSttlmAmt().getCcy()).isEqualTo("EUR");
            assertThat(tx.getCdtrAcct().getId().getIBAN()).isEqualTo("DE07200200200123456789");
        });
    }

    @Test
    void unmarshalRejectsADocumentOfAnotherType() {
        assertThatThrownBy(() -> codec.unmarshal(golden("pacs.002.001.10.xml"), com.tpe.iso20022model.pacs008v08.Document.class))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void unmarshalRejectsADoctypeWithoutResolvingIt() {
        String xml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <!DOCTYPE Document [<!ENTITY a "expanded">]>
                <Document xmlns="urn:iso:std:iso:20022:tech:xsd:pacs.002.001.10"><FIToFIPmtStsRpt><GrpHdr><MsgId>&a;</MsgId><CreDtTm>2026-10-21T09:15:31Z</CreDtTm></GrpHdr></FIToFIPmtStsRpt></Document>
                """;

        assertThatThrownBy(() -> codec.unmarshal(xml, com.tpe.iso20022model.pacs002v10.Document.class))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void unmarshalRejectsMalformedXml() {
        assertThatThrownBy(() -> codec.unmarshal("<Document", com.tpe.iso20022model.pacs002v10.Document.class))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void marshalRejectsAnObjectThatIsNotAnIsoDocument() {
        assertThatThrownBy(() -> codec.marshal("not a document")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void isSafeUnderConcurrentUse() throws Exception {
        List<Arguments> versions = versions().toList();
        List<Object> originals = versions.stream()
                .<Object>map(arguments -> codec.unmarshal(golden(arguments.get()[0] + ".xml"), (Class<?>) arguments.get()[1]))
                .toList();
        IsoCodec fresh = new IsoCodec(); // contexts and root elements are created by the racing threads
        int threads = 8;
        CountDownLatch start = new CountDownLatch(1);
        List<Callable<Integer>> workers = new ArrayList<>();
        for (int t = 0; t < threads; t++) {
            int offset = t;
            workers.add(() -> {
                start.await();
                int roundTrips = 0;
                for (int i = 0; i < 200; i++) {
                    int index = (i + offset) % versions.size();
                    Object original = originals.get(index);
                    Object copy = fresh.unmarshal(fresh.marshal(original), original.getClass());
                    assertThat(copy).isEqualTo(original);
                    roundTrips++;
                }
                return roundTrips;
            });
        }
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            List<Future<Integer>> results = workers.stream().map(pool::submit).toList();
            start.countDown();
            for (Future<Integer> result : results) {
                assertThat(result.get()).isEqualTo(200);
            }
        } finally {
            pool.shutdownNow();
        }
    }
}
