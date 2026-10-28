package com.tpe.contracts;

import com.fasterxml.jackson.annotation.JacksonAnnotationsInside;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a record component that may be absent: {@link ContractValidator} does not require it and it is not written
 * when {@code null}. Every other component is required, so contract records use no primitives: a missing
 * primitive would be read as its default and could not be told apart.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.RECORD_COMPONENT, ElementType.FIELD, ElementType.METHOD, ElementType.PARAMETER})
@JacksonAnnotationsInside
@JsonInclude(JsonInclude.Include.NON_NULL)
public @interface OptionalField {}
