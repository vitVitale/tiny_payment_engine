package com.tpe.csmgateway.application.incoming;

import com.tpe.paymentdomain.Bic;
import com.tpe.paymentdomain.EndToEndId;
import com.tpe.paymentdomain.MsgId;
import com.tpe.paymentdomain.TxId;
import com.tpe.paymentdomain.Uetr;
import java.io.StringReader;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Optional;
import java.util.function.Function;
import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;

/**
 * What names a {@code pacs.008} that failed validation, read as far as the XML allows (specs/15-csm-gateway.md §6.2):
 * enough to answer it {@code RJCT FF01}. Read without a DTD: a DOCTYPE is skipped, never processed, and an entity it
 * declares is never resolved; reading stops at the first error, keeping what came before (GW-AC-14, ISO-AC-02).
 *
 * @param msgId {@code null} if it could not be read
 * @param endToEndId {@code null} if it could not be read
 * @param uetr {@code null} if it could not be read
 */
public record IncomingIdentity(TxId txId, Bic debtorAgent, MsgId msgId, EndToEndId endToEndId, Uetr uetr) {

    private static final String MESSAGE = "FIToFICstmrCdtTrf";
    private static final String GROUP_MSG_ID = MESSAGE + "/GrpHdr/MsgId";
    private static final String FIRST_TRANSACTION = MESSAGE + "/CdtTrfTxInf";
    private static final String TRANSACTION = FIRST_TRANSACTION + "/";
    private static final String TX_ID = TRANSACTION + "PmtId/TxId";
    private static final String END_TO_END_ID = TRANSACTION + "PmtId/EndToEndId";
    private static final String UETR = TRANSACTION + "PmtId/UETR";
    private static final String DEBTOR_AGENT = TRANSACTION + "DbtrAgt/FinInstnId/BICFI";

    private static final XMLInputFactory FACTORY = factory();

    /** Whether {@code xml} holds a {@code pacs.008} message element, as far as it can be read. */
    public static boolean isCreditTransfer(String xml) {
        return parse(xml).message;
    }

    /** The identity of the {@code pacs.008} in {@code xml}; empty without a readable {@code TxId} and debtor agent. */
    public static Optional<IncomingIdentity> read(String xml) {
        Values values = parse(xml);
        TxId txId = value(values.txId, TxId::new);
        Bic debtorAgent = value(values.debtorAgent, Bic::new);
        if (txId == null || debtorAgent == null) {
            return Optional.empty();
        }
        return Optional.of(new IncomingIdentity(txId, debtorAgent, value(values.msgId, MsgId::new),
                value(values.endToEndId, EndToEndId::new), value(values.uetr, Uetr::new)));
    }

    private static Values parse(String xml) {
        var values = new Values();
        try {
            XMLStreamReader reader = FACTORY.createXMLStreamReader(new StringReader(xml));
            try {
                values.readFrom(reader);
            } finally {
                reader.close();
            }
        } catch (XMLStreamException | RuntimeException e) {
            // not well-formed from here on, or an entity it may not resolve: what was read so far counts
        }
        return values;
    }

    /** {@code null} for a value that is missing or not a valid domain value. */
    private static <T> T value(String text, Function<String, T> parse) {
        try {
            return text == null ? null : parse.apply(text.strip());
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static XMLInputFactory factory() {
        // the JDK's own implementation, whatever StAX provider the classpath holds: the properties below mean what they
        // say for it
        XMLInputFactory factory = XMLInputFactory.newDefaultFactory();
        factory.setProperty(XMLInputFactory.SUPPORT_DTD, false);
        factory.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);
        factory.setProperty(XMLInputFactory.IS_REPLACING_ENTITY_REFERENCES, false);
        factory.setProperty(XMLInputFactory.IS_NAMESPACE_AWARE, true);
        return factory;
    }

    /** The first value of each path, by local names below the message element. */
    private static final class Values {

        private final Deque<String> path = new ArrayDeque<>();
        private final StringBuilder text = new StringBuilder();
        private boolean message;
        /** The {@code CdtTrfTxInf} elements begun: every value of a transaction is read from the first one. */
        private int transactions;
        private String msgId;
        private String txId;
        private String endToEndId;
        private String uetr;
        private String debtorAgent;

        void readFrom(XMLStreamReader reader) throws XMLStreamException {
            while (reader.hasNext()) {
                switch (reader.next()) {
                    case XMLStreamConstants.START_ELEMENT -> {
                        String name = reader.getLocalName();
                        String parent = path.isEmpty() ? "" : path.peekLast();
                        // the path below the message element; "" outside it
                        path.addLast(!parent.isEmpty() ? parent + "/" + name : name.equals(MESSAGE) ? MESSAGE : "");
                        message |= MESSAGE.equals(path.peekLast());
                        transactions += FIRST_TRANSACTION.equals(path.peekLast()) ? 1 : 0;
                        text.setLength(0);
                    }
                    case XMLStreamConstants.CHARACTERS, XMLStreamConstants.CDATA -> text.append(reader.getText());
                    case XMLStreamConstants.END_ELEMENT -> {
                        keep(path.removeLast(), text.toString());
                        text.setLength(0);
                    }
                    default -> {
                        // a DOCTYPE, comments, processing instructions and entity references carry nothing to read
                    }
                }
            }
        }

        private void keep(String at, String value) {
            if (at.startsWith(TRANSACTION) && transactions > 1) {
                return;
            }
            switch (at) {
                case GROUP_MSG_ID -> msgId = msgId == null ? value : msgId;
                case TX_ID -> txId = txId == null ? value : txId;
                case END_TO_END_ID -> endToEndId = endToEndId == null ? value : endToEndId;
                case UETR -> uetr = uetr == null ? value : uetr;
                case DEBTOR_AGENT -> debtorAgent = debtorAgent == null ? value : debtorAgent;
                default -> {
                    // not part of the identity
                }
            }
        }
    }
}
