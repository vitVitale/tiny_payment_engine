package com.tpe.iso20022model;

import static com.tpe.iso20022model.CsmDocuments.date;
import static com.tpe.iso20022model.CsmDocuments.dateTime;

import com.tpe.iso20022model.pacs008v08.AccountIdentification4Choice;
import com.tpe.iso20022model.pacs008v08.ActiveCurrencyAndAmount;
import com.tpe.iso20022model.pacs008v08.BranchAndFinancialInstitutionIdentification6;
import com.tpe.iso20022model.pacs008v08.CashAccount38;
import com.tpe.iso20022model.pacs008v08.ChargeBearerType1Code;
import com.tpe.iso20022model.pacs008v08.CreditTransferTransaction39;
import com.tpe.iso20022model.pacs008v08.Document;
import com.tpe.iso20022model.pacs008v08.FIToFICustomerCreditTransferV08;
import com.tpe.iso20022model.pacs008v08.FinancialInstitutionIdentification18;
import com.tpe.iso20022model.pacs008v08.GroupHeader93;
import com.tpe.iso20022model.pacs008v08.LocalInstrument2Choice;
import com.tpe.iso20022model.pacs008v08.PartyIdentification135;
import com.tpe.iso20022model.pacs008v08.PaymentIdentification7;
import com.tpe.iso20022model.pacs008v08.PaymentTypeInformation28;
import com.tpe.iso20022model.pacs008v08.RemittanceInformation16;
import com.tpe.iso20022model.pacs008v08.ServiceLevel8Choice;
import com.tpe.iso20022model.pacs008v08.SettlementInstruction7;
import com.tpe.iso20022model.pacs008v08.SettlementMethod1Code;
import com.tpe.paymentdomain.Bic;
import com.tpe.paymentdomain.EndToEndId;
import com.tpe.paymentdomain.Iban;
import com.tpe.paymentdomain.Money;
import com.tpe.paymentdomain.MsgId;
import com.tpe.paymentdomain.PartyName;
import com.tpe.paymentdomain.RemittanceInformation;
import com.tpe.paymentdomain.TxId;
import com.tpe.paymentdomain.Uetr;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Objects;

/**
 * Builds the {@code pacs.008.001.08} of one SCT Inst credit transfer in its CSM envelope
 * (specs/01-shared-libraries.md §2.2), with only the fields the EPC guidelines require: {@code NbOfTxs = 1},
 * {@code SttlmMtd = CLRG}, {@code SvcLvl/Cd = SEPA}, {@code LclInstrm/Cd = INST}, {@code ChrgBr = SLEV}. The header
 * goes from the debtor agent to the creditor agent. Stateless and thread-safe.
 */
public final class Pacs008Builder {

    static final String SERVICE_LEVEL = "SEPA";
    static final String LOCAL_INSTRUMENT = "INST";

    /** The name and account of the debtor or the creditor. */
    public record AccountHolder(PartyName name, Iban iban) {

        public AccountHolder {
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(iban, "iban");
        }
    }

    /**
     * One credit transfer. The caller owns the clock: {@code createdAt} is the {@code CreDtTm}, {@code settlementDate}
     * the {@code IntrBkSttlmDt}, {@code acceptedAt} the {@code AccptncDtTm}.
     *
     * @param msgId the message's {@code MsgId}, also the header's {@code BizMsgIdr}
     * @param remittance the unstructured {@code RmtInf}; {@code null} when the payment has none
     */
    public record CreditTransfer(
            MsgId msgId,
            Instant createdAt,
            TxId txId,
            EndToEndId endToEndId,
            Uetr uetr,
            Money amount,
            LocalDate settlementDate,
            Instant acceptedAt,
            AccountHolder debtor,
            Bic debtorAgent,
            Bic creditorAgent,
            AccountHolder creditor,
            RemittanceInformation remittance) {

        public CreditTransfer {
            Objects.requireNonNull(msgId, "msgId");
            Objects.requireNonNull(createdAt, "createdAt");
            Objects.requireNonNull(txId, "txId");
            Objects.requireNonNull(endToEndId, "endToEndId");
            Objects.requireNonNull(uetr, "uetr");
            Objects.requireNonNull(amount, "amount");
            Objects.requireNonNull(settlementDate, "settlementDate");
            Objects.requireNonNull(acceptedAt, "acceptedAt");
            Objects.requireNonNull(debtor, "debtor");
            Objects.requireNonNull(debtorAgent, "debtorAgent");
            Objects.requireNonNull(creditorAgent, "creditorAgent");
            Objects.requireNonNull(creditor, "creditor");
        }
    }

    public CsmEnvelope build(CreditTransfer transfer) {
        GroupHeader93 group = new GroupHeader93();
        group.setMsgId(transfer.msgId().value());
        group.setCreDtTm(dateTime(transfer.createdAt()));
        group.setNbOfTxs("1");
        group.setTtlIntrBkSttlmAmt(amount(transfer.amount()));
        group.setIntrBkSttlmDt(date(transfer.settlementDate()));
        SettlementInstruction7 settlement = new SettlementInstruction7();
        settlement.setSttlmMtd(SettlementMethod1Code.CLRG);
        group.setSttlmInf(settlement);
        group.setPmtTpInf(paymentType());

        PaymentIdentification7 identification = new PaymentIdentification7();
        identification.setEndToEndId(transfer.endToEndId().value());
        identification.setTxId(transfer.txId().value());
        identification.setUETR(transfer.uetr().value());

        CreditTransferTransaction39 transaction = new CreditTransferTransaction39();
        transaction.setPmtId(identification);
        transaction.setIntrBkSttlmAmt(amount(transfer.amount()));
        transaction.setAccptncDtTm(dateTime(transfer.acceptedAt()));
        transaction.setChrgBr(ChargeBearerType1Code.SLEV);
        transaction.setDbtr(party(transfer.debtor().name()));
        transaction.setDbtrAcct(account(transfer.debtor().iban()));
        transaction.setDbtrAgt(agent(transfer.debtorAgent()));
        transaction.setCdtrAgt(agent(transfer.creditorAgent()));
        transaction.setCdtr(party(transfer.creditor().name()));
        transaction.setCdtrAcct(account(transfer.creditor().iban()));
        if (transfer.remittance() != null) {
            RemittanceInformation16 remittance = new RemittanceInformation16();
            remittance.getUstrd().add(transfer.remittance().value());
            transaction.setRmtInf(remittance);
        }

        FIToFICustomerCreditTransferV08 message = new FIToFICustomerCreditTransferV08();
        message.setGrpHdr(group);
        message.getCdtTrfTxInf().add(transaction);
        Document document = new Document();
        document.setFIToFICstmrCdtTrf(message);
        return new CsmEnvelope(
                CsmDocuments.header(transfer.debtorAgent(), transfer.creditorAgent(), transfer.msgId(),
                        CsmMessageType.PACS_008, transfer.createdAt()),
                document);
    }

    private static PaymentTypeInformation28 paymentType() {
        ServiceLevel8Choice serviceLevel = new ServiceLevel8Choice();
        serviceLevel.setCd(SERVICE_LEVEL);
        LocalInstrument2Choice localInstrument = new LocalInstrument2Choice();
        localInstrument.setCd(LOCAL_INSTRUMENT);
        PaymentTypeInformation28 paymentType = new PaymentTypeInformation28();
        paymentType.getSvcLvl().add(serviceLevel);
        paymentType.setLclInstrm(localInstrument);
        return paymentType;
    }

    private static ActiveCurrencyAndAmount amount(Money money) {
        ActiveCurrencyAndAmount amount = new ActiveCurrencyAndAmount();
        // Money owns the minor-unit conversion (specs/00-engineering-baseline.md §5)
        amount.setValue(new BigDecimal(money.toDecimalString()));
        amount.setCcy(money.currency().getCurrencyCode());
        return amount;
    }

    private static PartyIdentification135 party(PartyName name) {
        PartyIdentification135 party = new PartyIdentification135();
        party.setNm(name.value());
        return party;
    }

    private static CashAccount38 account(Iban iban) {
        AccountIdentification4Choice identification = new AccountIdentification4Choice();
        identification.setIBAN(iban.value());
        CashAccount38 account = new CashAccount38();
        account.setId(identification);
        return account;
    }

    private static BranchAndFinancialInstitutionIdentification6 agent(Bic bic) {
        FinancialInstitutionIdentification18 institution = new FinancialInstitutionIdentification18();
        institution.setBICFI(bic.value());
        BranchAndFinancialInstitutionIdentification6 agent = new BranchAndFinancialInstitutionIdentification6();
        agent.setFinInstnId(institution);
        return agent;
    }
}
