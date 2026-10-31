package com.tpe.testsupport.pact;

import au.com.dius.pact.provider.junitsupport.loader.PactSource;
import java.lang.annotation.ElementType;
import java.lang.annotation.Inherited;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.junit.jupiter.api.Tag;

/**
 * Marks a provider verification (specs/20-testing-strategy.md §10.3), next to {@code @Provider("<artifactId>")}. It
 * runs only in the {@code -Ppact-verify} step ({@code @Tag("pact-verify")}) and verifies, in one place so a provider
 * cannot opt into half of it:
 * <ul>
 *   <li>the pacts of this commit, collected in {@code pact.folder} (same-commit check);</li>
 *   <li>the pacts of the last release, the newest {@code pacts/released/<version>/} in {@code pact.released.dir}
 *       (backward check), unless {@code pact.released.skip=true} (the forward check).</li>
 * </ul>
 * See {@link PactVerificationLoader}.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
@Inherited
@Tag("pact-verify")
@PactSource(PactVerificationLoader.class)
public @interface PactVerification {}
