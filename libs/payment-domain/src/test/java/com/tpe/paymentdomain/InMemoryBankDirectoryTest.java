package com.tpe.paymentdomain;

import static com.tpe.paymentdomain.Rejections.assertRejected;
import static com.tpe.paymentdomain.ValidationCode.INVALID_BANK_DIRECTORY;
import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class InMemoryBankDirectoryTest {

    private static final Bic OWN_BIC = new Bic("TPEBDEFFXXX");
    private static final Bic PEER_BIC = new Bic("PEERDEFFXXX");
    private static final URI PEER_VOP = URI.create("http://csm-simulator:8090/vop/v1/verifications");

    private static final BankEntry OWN = new BankEntry("10010010", OWN_BIC, true, Optional.empty());
    private static final BankEntry PEER = new BankEntry("20020020", PEER_BIC, true, Optional.of(PEER_VOP));
    private static final BankEntry UNREACHABLE = new BankEntry("30030030", new Bic("SLOWDEFF"), false, Optional.empty());

    private static final Iban OWN_IBAN = new Iban("DE36100100100000000001");
    private static final Iban PEER_IBAN = new Iban("DE07200200200123456789");

    private static BankDirectory directory() {
        return new InMemoryBankDirectory("10010010", OWN_BIC, List.of(OWN, PEER, UNREACHABLE));
    }

    @Test
    void looksUpTheEntryByTheIbansBankCode() {
        assertThat(directory().lookup(PEER_IBAN)).contains(PEER);
        assertThat(directory().lookup(OWN_IBAN)).contains(OWN);
    }

    @Test
    void unknownBankCodeIsEmpty() {
        assertThat(directory().lookup(new Iban("DE89370400440532013000"))).isEmpty();
        assertThat(directory().lookup(new Iban("AT451001000000000001"))).isEmpty();
    }

    @Test
    void isOwnOnlyForTheOwnBankCode() {
        assertThat(directory().isOwn(OWN_IBAN)).isTrue();
        assertThat(directory().isOwn(PEER_IBAN)).isFalse();
        assertThat(directory().isOwn(new Iban("DE89370400440532013000"))).isFalse();
    }

    // Bank codes are national: a PL IBAN can carry the same 8 digits as a DE bank code
    private static final Iban PL_IBAN_WITH_OWN_CODE = new Iban("PL27100100100000000000000001");
    private static final Iban PL_IBAN_WITH_PEER_CODE = new Iban("PL77200200200000000000000001");

    @Test
    void sameBankCodeInAnotherCountryIsNotOwn() {
        assertThat(PL_IBAN_WITH_OWN_CODE.bankCode()).isEqualTo("10010010");

        assertThat(directory().isOwn(PL_IBAN_WITH_OWN_CODE)).isFalse();
    }

    @Test
    void sameBankCodeInAnotherCountryIsNotFound() {
        assertThat(directory().lookup(PL_IBAN_WITH_OWN_CODE)).isEmpty();
        assertThat(directory().lookup(PL_IBAN_WITH_PEER_CODE)).isEmpty();
    }

    @Test
    void exposesTheOwnBic() {
        assertThat(directory().ownBic()).isEqualTo(OWN_BIC);
    }

    @Test
    void entriesCarryReachabilityAndVopEndpoint() {
        BankEntry peer = directory().lookup(PEER_IBAN).orElseThrow();

        assertThat(peer.bic()).isEqualTo(PEER_BIC);
        assertThat(peer.sctInstReachable()).isTrue();
        assertThat(peer.vopEndpoint()).contains(PEER_VOP);
        assertThat(directory().lookup(OWN_IBAN).orElseThrow().vopEndpoint()).isEmpty();
    }

    @Test
    void rejectsADirectoryWithoutItsOwnBank() {
        assertRejected(() -> new InMemoryBankDirectory("10010010", OWN_BIC, List.of(PEER)), INVALID_BANK_DIRECTORY);
    }

    @Test
    void rejectsDuplicateBankCodes() {
        BankEntry peerAgain = new BankEntry("20020020", new Bic("OTHRDEFF"), false, Optional.empty());

        assertRejected(
                () -> new InMemoryBankDirectory("10010010", OWN_BIC, List.of(OWN, PEER, peerAgain)),
                INVALID_BANK_DIRECTORY);
    }

    @Test
    void rejectsAnOwnBicThatDiffersFromTheOwnEntry() {
        assertRejected(
                () -> new InMemoryBankDirectory("10010010", new Bic("TPEBDEFF"), List.of(OWN, PEER)),
                INVALID_BANK_DIRECTORY);
        assertRejected(
                () -> new InMemoryBankDirectory("10010010", PEER_BIC, List.of(OWN, PEER)), INVALID_BANK_DIRECTORY);
    }

    @Test
    void bankEntryRejectsAnEmptyBankCode() {
        assertRejected(() -> new BankEntry("", OWN_BIC, true, Optional.empty()), INVALID_BANK_DIRECTORY);
        assertRejected(() -> new BankEntry(" ", OWN_BIC, true, Optional.empty()), INVALID_BANK_DIRECTORY);
    }
}
