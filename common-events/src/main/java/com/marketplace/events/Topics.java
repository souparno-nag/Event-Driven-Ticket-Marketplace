package com.marketplace.events;

import java.util.List;

/**
 * The names of the channels the saga's messages travel on.
 *
 * <p>WHY these live in the contract module rather than in each service's configuration: a channel
 * name is as much a part of the contract as the message shape. A publisher that invents a name no
 * consumer subscribes to produces no error anywhere — the messages are simply written into a
 * channel nobody reads, and the saga stalls with every service reporting healthy. Holding the names
 * in one compiled constant makes a mismatch a compile error instead.
 *
 * <p>Names are lowercase dot-separated {@code subject.past-tense-verb}. The subject leads so
 * related channels sort together, and the verb is past tense because every one of these announces
 * something that already happened; a channel named {@code order.reserve} would invite a service to
 * treat it as a command it may decline, which is not what a choreographed saga does.
 *
 * <p>Each channel is paired with a dead-letter channel — see {@link #dlt(String)}.
 */
public final class Topics {

	// The seven message channels, one per message type. Declared as `static final String` rather
	// than as an enum specifically so they are compile-time constants: Java requires that for an
	// annotation argument, and consumers are wired up as @KafkaListener(topics = Topics.ORDER_CREATED).
	// TRADEOFF: an enum pairing each message type with its channel would be tidier and would let the
	// compiler check exhaustiveness, but it cannot appear inside an annotation, which is where these
	// values are needed most. Rejected on that alone.

	/** Published by order-service when an order is accepted. Starts every saga. */
	public static final String ORDER_CREATED = "order.created";

	/** Published by inventory-service when all requested seats were held. */
	public static final String SEATS_RESERVED = "seats.reserved";

	/** Published by inventory-service when the seats could not be held. Ends the saga. */
	public static final String SEATS_REJECTED = "seats.rejected";

	/** Published by payment-service when the charge succeeded. */
	public static final String PAYMENT_SUCCEEDED = "payment.succeeded";

	/** Published by payment-service when the charge did not succeed. Triggers compensation. */
	public static final String PAYMENT_FAILED = "payment.failed";

	/** Published by order-service on the happy path. The saga's successful terminal message. */
	public static final String ORDER_CONFIRMED = "order.confirmed";

	/** Published by order-service on any failure path. Tells inventory to release its holds. */
	public static final String ORDER_CANCELLED = "order.cancelled";

	/**
	 * Every message channel, in saga order.
	 *
	 * <p>{@code List.of} returns an unmodifiable list, so a caller cannot add a channel at runtime
	 * that the provisioning step never created.
	 *
	 * <p>WHY the list exists at all rather than callers naming channels one by one: provisioning,
	 * health checks, and the drift test all need to walk the complete set, and a hand-maintained
	 * second copy of that set is exactly the kind of thing that silently loses an entry.
	 */
	public static final List<String> ALL = List.of(
			ORDER_CREATED,
			SEATS_RESERVED,
			SEATS_REJECTED,
			PAYMENT_SUCCEEDED,
			PAYMENT_FAILED,
			ORDER_CONFIRMED,
			ORDER_CANCELLED);

	/** The suffix identifying a dead-letter channel. Package-private so the drift test can read it. */
	static final String DLT_SUFFIX = ".DLT";

	/**
	 * The dead-letter channel paired with {@code topic}.
	 *
	 * <p>A message that still cannot be processed after its retries are exhausted is moved here
	 * rather than retried forever. WHY that matters more than usual in this system: messages are
	 * keyed by saga id, so one permanently unprocessable message does not merely block its own
	 * order — it blocks the whole partition, and with it every other order whose key hashes to the
	 * same partition. Moving it aside lets the partition drain (FR-025).
	 *
	 * <p>WHY one dead-letter channel per message type instead of a single shared one: a failed
	 * message stays identifiable as the saga step that produced it, so inspection and replay tooling
	 * can deserialize it without first sniffing what type it is.
	 *
	 * <p>The {@code .DLT} suffix is Spring Kafka's {@code DeadLetterPublishingRecoverer} default
	 * (R5), so consumers get this wiring with no configuration. It is duplicated in the shell script
	 * that provisions the channels, which is a real drift risk and is why a test asserts the two
	 * agree.
	 *
	 * @param topic a channel name, normally one of the constants in this class
	 * @return the paired dead-letter channel name
	 */
	public static String dlt(String topic) {
		return topic + DLT_SUFFIX;
	}

	// Not instantiable: this is a namespace for constants, not a thing with behaviour or state.
	private Topics() {
	}
}
