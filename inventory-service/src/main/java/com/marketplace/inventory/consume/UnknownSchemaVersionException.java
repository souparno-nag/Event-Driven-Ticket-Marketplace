package com.marketplace.inventory.consume;

/**
 * Thrown by {@code OrderCreatedListener} (T178) when a message's {@code schemaVersion} is not one
 * this service knows how to interpret — a real message this service will NEVER be able to make sense
 * of, not a transient problem more attempts could resolve (FR-003; contracts/inventory-consumer.md).
 *
 * <p>WHY this is a distinct exception type rather than a generic {@code IllegalArgumentException} or
 * similar: {@code KafkaConsumerConfig} (T177) classifies exceptions into retryable and non-retryable
 * by TYPE, via {@code DefaultErrorHandler#addNotRetryableExceptions(...)}. A generic exception type
 * is also thrown by ordinary programming mistakes elsewhere in this service's code, and classifying
 * every instance of a common type as "never retry, dead-letter immediately" would silently swallow
 * bugs that have nothing to do with schema versions. A dedicated type means only the ONE situation
 * this class exists to describe is ever routed this way.
 *
 * <p>WHY unrecognised, not merely "old" or "new": {@link com.marketplace.events.OrderCreated}'s own
 * compact constructor already rejects a version below 1 (a basic sanity bound every message shape
 * shares). This exception covers the version this service is not BUILT to understand yet — today,
 * anything other than version 1 — which {@code OrderCreated}'s own validation cannot know about,
 * because that knowledge belongs to whichever consumer is doing the interpreting, not to the shared
 * contract type every service compiles against.
 *
 * <p>Discarding a message with an unrecognised version is prohibited (contracts/inventory-consumer.md):
 * these messages move real seat inventory, and a silently dropped one strands a saga with no record of
 * why. Dead-lettering it — visible, inspectable, never retried against a shape that will never
 * change — is the only acceptable outcome.
 */
public class UnknownSchemaVersionException extends RuntimeException {

	public UnknownSchemaVersionException(int schemaVersion) {
		super("unrecognised OrderCreated schemaVersion: " + schemaVersion);
	}
}
