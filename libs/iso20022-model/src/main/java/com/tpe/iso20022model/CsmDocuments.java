package com.tpe.iso20022model;

import com.tpe.iso20022model.head001v02.BranchAndFinancialInstitutionIdentification6;
import com.tpe.iso20022model.head001v02.BusinessApplicationHeaderV02;
import com.tpe.iso20022model.head001v02.FinancialInstitutionIdentification18;
import com.tpe.iso20022model.head001v02.Party44Choice;
import com.tpe.paymentdomain.Bic;
import com.tpe.paymentdomain.MsgId;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import javax.xml.datatype.DatatypeFactory;
import javax.xml.datatype.XMLGregorianCalendar;

/** What the CSM builders share: the {@code head.001} header and the ISO date forms. */
final class CsmDocuments {

    private static final DateTimeFormatter MILLIS =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC);

    private CsmDocuments() {}

    /**
     * The header of a document we send: {@code BizMsgIdr} is the document's {@code MsgId}, {@code CreDt} its
     * {@code CreDtTm}.
     */
    static BusinessApplicationHeaderV02 header(Bic from, Bic to, MsgId msgId, CsmMessageType type, Instant createdAt) {
        BusinessApplicationHeaderV02 header = new BusinessApplicationHeaderV02();
        header.setFr(party(from));
        header.setTo(party(to));
        header.setBizMsgIdr(msgId.value());
        header.setMsgDefIdr(type.version());
        header.setCreDt(dateTime(createdAt));
        return header;
    }

    /** UTC with exactly three fraction digits (specs/00-engineering-baseline.md §6); finer digits are dropped. */
    static XMLGregorianCalendar dateTime(Instant instant) {
        // DatatypeFactory is not documented thread-safe; the default instance is cheap to create
        return DatatypeFactory.newDefaultInstance().newXMLGregorianCalendar(MILLIS.format(instant));
    }

    static XMLGregorianCalendar date(LocalDate date) {
        return DatatypeFactory.newDefaultInstance().newXMLGregorianCalendar(date.toString());
    }

    private static Party44Choice party(Bic bic) {
        FinancialInstitutionIdentification18 institution = new FinancialInstitutionIdentification18();
        institution.setBICFI(bic.value());
        BranchAndFinancialInstitutionIdentification6 agent = new BranchAndFinancialInstitutionIdentification6();
        agent.setFinInstnId(institution);
        Party44Choice party = new Party44Choice();
        party.setFIId(agent);
        return party;
    }
}
