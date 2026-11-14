package com.tpe.paymentapi;

/**
 * A {@code pain.001.001.09} with one transaction, the XML equivalent of the JSON payment the component tests send:
 * Anna Schmidt's {@code DE36100100100000000001} pays 123.45 EUR to Max Mustermann's {@code DE63200200200000000001}
 * at {@code PEERDEFFXXX}, end-to-end id {@code INV-2026-0042}, remittance {@code Invoice 42}, requested for
 * 2026-11-14. Variants replace a part of it.
 */
public record Pain001Sample(String xml) {

    public static final String MESSAGE_ID = "CLIENT-MSG-0042";

    private static final String TRANSACTION = """
                        <CdtTrfTxInf>
                            <PmtId>
                                <EndToEndId>INV-2026-0042</EndToEndId>
                            </PmtId>
                            <Amt>
                                <InstdAmt Ccy="EUR">123.45</InstdAmt>
                            </Amt>
                            <CdtrAgt>
                                <FinInstnId>
                                    <BICFI>PEERDEFFXXX</BICFI>
                                </FinInstnId>
                            </CdtrAgt>
                            <Cdtr>
                                <Nm>Max Mustermann</Nm>
                            </Cdtr>
                            <CdtrAcct>
                                <Id>
                                    <IBAN>DE63200200200000000001</IBAN>
                                </Id>
                            </CdtrAcct>
                            <RmtInf>
                                <Ustrd>Invoice 42</Ustrd>
                            </RmtInf>
                        </CdtTrfTxInf>
            """;

    private static final String INSTRUCTION = """
                    <PmtInf>
                        <PmtInfId>CLIENT-PMT-0042</PmtInfId>
                        <PmtMtd>TRF</PmtMtd>
                        <NbOfTxs>1</NbOfTxs>
                        <PmtTpInf>
                            <LclInstrm>
                                <Cd>INST</Cd>
                            </LclInstrm>
                        </PmtTpInf>
                        <ReqdExctnDt>
                            <Dt>2026-11-14</Dt>
                        </ReqdExctnDt>
                        <Dbtr>
                            <Nm>Anna Schmidt</Nm>
                        </Dbtr>
                        <DbtrAcct>
                            <Id>
                                <IBAN>DE36100100100000000001</IBAN>
                            </Id>
                        </DbtrAcct>
                        <DbtrAgt>
                            <FinInstnId>
                                <BICFI>TPEBDEFFXXX</BICFI>
                            </FinInstnId>
                        </DbtrAgt>
                        <ChrgBr>SLEV</ChrgBr>
            """ + TRANSACTION + """
                    </PmtInf>
            """;

    private static final String DOCUMENT = """
            <?xml version="1.0" encoding="UTF-8"?>
            <Document xmlns="urn:iso:std:iso:20022:tech:xsd:pain.001.001.09">
                <CstmrCdtTrfInitn>
                    <GrpHdr>
                        <MsgId>CLIENT-MSG-0042</MsgId>
                        <CreDtTm>2026-11-14T09:59:58.000Z</CreDtTm>
                        <NbOfTxs>1</NbOfTxs>
                        <InitgPty>
                            <Nm>Anna Schmidt</Nm>
                        </InitgPty>
                    </GrpHdr>
            """ + INSTRUCTION + """
                </CstmrCdtTrfInitn>
            </Document>
            """;

    public static Pain001Sample valid() {
        return new Pain001Sample(DOCUMENT);
    }

    /** {@code target} replaced by {@code replacement}; fails if the sample does not contain it. */
    public Pain001Sample with(String target, String replacement) {
        if (!xml.contains(target)) {
            throw new IllegalArgumentException("the sample has no " + target);
        }
        return new Pain001Sample(xml.replace(target, replacement));
    }

    /** The {@code <Tag>value</Tag>} element's value replaced. */
    public Pain001Sample withValue(String tag, String from, String to) {
        return with("<" + tag + ">" + from + "</" + tag + ">", "<" + tag + ">" + to + "</" + tag + ">");
    }

    /** The {@code <Tag>…</Tag>} element and its content removed, the first one if there are several. */
    public Pain001Sample without(String tag) {
        int start = xml.indexOf("<" + tag + ">");
        int end = xml.indexOf("</" + tag + ">", start);
        if (start < 0 || end < 0) {
            throw new IllegalArgumentException("the sample has no " + tag);
        }
        return new Pain001Sample(xml.substring(0, start) + xml.substring(end + tag.length() + 3));
    }

    /** Two transactions in the payment information. */
    public Pain001Sample withTwoTransactions() {
        return with("</CdtTrfTxInf>", "</CdtTrfTxInf>\n" + TRANSACTION.replace("INV-2026-0042", "INV-2026-0043"))
                .withValue("NbOfTxs", "1", "2");
    }

    /** Two payment informations, one transaction each. */
    public Pain001Sample withTwoPaymentInformations() {
        return with("</PmtInf>", "</PmtInf>\n" + INSTRUCTION.replace("CLIENT-PMT-0042", "CLIENT-PMT-0043"));
    }
}
