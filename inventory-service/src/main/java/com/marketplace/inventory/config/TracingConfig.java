package com.marketplace.inventory.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import brave.propagation.Propagation;
import brave.propagation.tracecontext.TraceContextPropagation;

/**
 * Supplies the one bean Spring Boot's own tracing autoconfiguration would not assemble on its own —
 * ported from order-service's {@code TracingConfig} unchanged. This service carries the identical
 * combination of dependencies (Boot 3.3.13, {@code micrometer-tracing-bridge-brave},
 * {@code brave-propagation-tracecontext}) that produces the failure below, so the fix applies
 * verbatim.
 *
 * <p>WHY this bean exists at all — the failure it works around, found by direct inspection rather
 * than assumed from documentation: with only {@code micrometer-tracing-bridge-brave} on the
 * classpath and no user-supplied {@code Propagation.Factory} bean, Spring Boot's
 * {@code BravePropagationConfigurations} has three competing autoconfiguration branches for
 * providing one — {@code PropagationWithBaggage}, {@code PropagationWithoutBaggage}, and
 * {@code NoPropagation} — each gated behind its own {@code @ConditionalOnProperty}. Every attempt to
 * satisfy those conditions through configuration ({@code management.tracing.enabled},
 * {@code management.tracing.baggage.enabled}, {@code management.tracing.propagation.type}, in every
 * combination tried) still left {@code NoPropagation}'s no-op factory winning by
 * {@code @ConditionalOnMissingBean} — confirmed each time by inspecting the running propagator
 * directly: {@code propagator.fields()} came back empty, and {@code propagator.inject(...)} silently
 * wrote nothing into any carrier. No exception, no warning — the outbox row simply stays untraced
 * forever, breaking the second half of SC-015's single connected trace exactly where this service's
 * own outbox picks up the first half from order-service.
 *
 * <p>The fix is to stop asking Spring Boot's autoconfiguration to assemble this bean at all.
 * Declaring it here directly means the SAME {@code @ConditionalOnMissingBean(Propagation.Factory)}
 * that let the no-op win now finds THIS bean first, and every one of Boot's three competing branches
 * backs off entirely — no property gymnastics required, and one class, not three interacting
 * conditions, is where the actual behaviour is decided.
 *
 * <p>{@link TraceContextPropagation#FACTORY} is a small, dependency-free constant from
 * {@code brave-propagation-tracecontext} — a library that was already on the classpath transitively
 * (see this module's {@code pom.xml}) but had never been reachable through Boot's own configuration
 * path.
 */
@Configuration
public class TracingConfig {

	@Bean
	public Propagation.Factory propagationFactory() {
		return TraceContextPropagation.FACTORY;
	}
}
