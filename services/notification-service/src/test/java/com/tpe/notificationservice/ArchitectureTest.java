package com.tpe.notificationservice;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.junit.ArchTests;
import com.tpe.testsupport.architecture.ArchitectureRules;

@AnalyzeClasses(packages = "com.tpe.notificationservice", importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureTest {

    @ArchTest
    static final ArchTests RULES = ArchTests.in(ArchitectureRules.class);
}
