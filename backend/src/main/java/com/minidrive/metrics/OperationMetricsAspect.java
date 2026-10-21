package com.minidrive.metrics;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

@Aspect
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class OperationMetricsAspect {
    private final MeterRegistry registry;

    public OperationMetricsAspect(MeterRegistry registry) {
        this.registry = registry;
    }

    @Around("@annotation(com.minidrive.metrics.MeasuredOperation)")
    public Object measure(ProceedingJoinPoint call) throws Throwable {
        MeasuredOperation measured = ((MethodSignature) call.getSignature())
                .getMethod().getAnnotation(MeasuredOperation.class);
        Timer.Sample sample = Timer.start(registry);
        String outcome = "success";
        try {
            return call.proceed();
        } catch (Throwable failure) {
            outcome = "failure";
            throw failure;
        } finally {
            sample.stop(registry.timer(measured.metric(), "operation", measured.value(), "outcome", outcome));
        }
    }
}
