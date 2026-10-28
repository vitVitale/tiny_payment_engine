package com.tpe.contracts;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Declares the message type and schema version of a {@link Message} record (specs/01-shared-libraries.md §4). The
 * type is the record's simple name, stated explicitly so a class rename cannot silently change what goes on the
 * wire (the harness checks they agree); the golden sample of each version is {@code golden/<type>.v<version>.json}.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface Contract {

    String type();

    /** Starts at 1; a new version only adds optional fields (01 §4 evolution rules). */
    int version();
}
