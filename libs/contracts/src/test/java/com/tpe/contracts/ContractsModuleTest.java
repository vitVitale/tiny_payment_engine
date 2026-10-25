package com.tpe.contracts;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** Placeholder until the module has real code: proves the module builds and runs its tests. */
class ContractsModuleTest {

    @Test
    void rootPackageFollowsModuleName() {
        assertThat(getClass().getPackageName()).isEqualTo("com.tpe.contracts");
    }
}
