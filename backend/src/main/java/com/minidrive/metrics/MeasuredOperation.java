package com.minidrive.metrics;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** Marks a bounded application workflow for duration and outcome measurement. */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface MeasuredOperation {
    String value();
    String metric() default "minidrive_file_operation_seconds";
}
