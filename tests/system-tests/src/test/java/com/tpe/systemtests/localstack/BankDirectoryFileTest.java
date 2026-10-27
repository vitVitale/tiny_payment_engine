package com.tpe.systemtests.localstack;

import static org.assertj.core.api.Assertions.assertThat;

import com.tpe.paymentdomain.BankDirectory;
import com.tpe.paymentdomain.BankEntry;
import com.tpe.paymentdomain.Bic;
import com.tpe.paymentdomain.Iban;
import com.tpe.paymentdomain.InMemoryBankDirectory;
import java.io.IOException;
import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

/**
 * Configuration-consistency test: deploy/config/bank-directory.yml is a valid {@link BankDirectory} with the own
 * bank and the simulated peer bank of 01 §1.4.
 */
class BankDirectoryFileTest {

    @Test
    void fileIsAValidDirectoryWithTheOwnAndThePeerBank() throws IOException {
        BankDirectory directory = directoryFile();

        assertThat(directory.ownBic()).isEqualTo(new Bic("TPEBDEFFXXX"));
        assertThat(directory.isOwn(new Iban("DE36100100100000000001"))).isTrue();
        assertThat(directory.lookup(new Iban("DE36100100100000000001")))
                .contains(new BankEntry("10010010", new Bic("TPEBDEFFXXX"), true, Optional.empty()));
        assertThat(directory.lookup(new Iban("DE07200200200123456789")))
                .contains(new BankEntry("20020020", new Bic("PEERDEFFXXX"), true,
                        Optional.of(URI.create("http://csm-simulator:8090/vop/v1/verifications"))));
    }

    @SuppressWarnings("unchecked")
    private static BankDirectory directoryFile() throws IOException {
        Map<String, Object> file = new Yaml().load(Repository.read("deploy/config/bank-directory.yml"));
        var bankDirectory = (Map<String, Object>) ((Map<String, Object>) file.get("tpe")).get("bank-directory");
        List<BankEntry> banks = ((List<Map<String, Object>>) bankDirectory.get("banks")).stream()
                .map(bank -> new BankEntry(
                        (String) bank.get("bankCode"),
                        new Bic((String) bank.get("bic")),
                        (Boolean) bank.get("sctInstReachable"),
                        Optional.ofNullable((String) bank.get("vopEndpoint")).map(URI::create)))
                .toList();
        return new InMemoryBankDirectory(
                (String) bankDirectory.get("ownBankCode"), new Bic((String) bankDirectory.get("ownBic")), banks);
    }
}
