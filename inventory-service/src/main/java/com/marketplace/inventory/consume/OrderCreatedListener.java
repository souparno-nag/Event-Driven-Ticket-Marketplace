package com.marketplace.inventory.consume;

import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import com.marketplace.events.OrderCreated;
import com.marketplace.events.Topics;
import com.marketplace.inventory.service.ReservationService;

/**
 * The one place a real {@code order.created} message becomes a call into
 * {@link ReservationService} — the whole reason every other class in this build step exists
 * (contracts/inventory-consumer.md).
 *
 * <p>WHY this class carries no {@code @Transactional} of its own, even though the work it triggers is
 * entirely transactional: the transaction belongs to the DECISION, not to the act of receiving a
 * message. {@code ReservationService.decide(...)} already opens (and, on an optimistic-lock collision,
 * reopens) its own transaction via {@code TransactionTemplate} — annotating this listener as well
 * would nest a second transactional boundary around the container's own error-handling bookkeeping
 * (offset tracking, the {@code DefaultErrorHandler}'s retry accounting), which
 * contracts/inventory-consumer.md's own implementation notes call out as specifically the wrong thing
 * to enclose.
 *
 * <p>WHY throwing is the entire mechanism, with no manual acknowledgement anywhere: Spring Kafka
 * commits a listener's offset only after the method returns normally. A schema check that fails, or
 * any exception {@code ReservationService.decide(...)} lets escape, leaves the offset uncommitted —
 * which is what makes {@code KafkaConsumerConfig}'s {@code DefaultErrorHandler} redeliver the message
 * at all. Committing the offset by hand anywhere in this class would create a second place the offset
 * could advance past work that never actually happened.
 */
@Component
public class OrderCreatedListener {

	/**
	 * The only {@code schemaVersion} this listener currently knows how to interpret.
	 * {@link com.marketplace.events.OrderCreated}'s own compact constructor only enforces "at least
	 * 1" — a bound every message shape shares — never "a version THIS consumer understands", because
	 * that knowledge belongs to whichever consumer is doing the interpreting (FR-003).
	 */
	private static final int SUPPORTED_SCHEMA_VERSION = 1;

	private final ReservationService reservationService;

	public OrderCreatedListener(ReservationService reservationService) {
		this.reservationService = reservationService;
	}

	/**
	 * @param event already deserialized by the {@code ConsumerFactory} {@code KafkaConsumerConfig}
	 *              builds — a message this listener could not even parse never reaches this method at
	 *              all; it is routed to the dead-letter channel by the container's own error handler
	 *              before a {@code ConsumerRecord} is ever handed to a {@code @KafkaListener} method
	 * @throws UnknownSchemaVersionException if {@code event.schemaVersion()} is not
	 *                                        {@value #SUPPORTED_SCHEMA_VERSION} — classified
	 *                                        non-retryable by {@code KafkaConsumerConfig}, so this
	 *                                        throw reaches the dead-letter channel immediately rather
	 *                                        than after exhausting a redelivery schedule that could
	 *                                        never have produced a different answer (FR-003)
	 */
	@KafkaListener(topics = Topics.ORDER_CREATED, containerFactory = "kafkaListenerContainerFactory")
	public void onMessage(OrderCreated event) {
		if (event.schemaVersion() != SUPPORTED_SCHEMA_VERSION) {
			throw new UnknownSchemaVersionException(event.schemaVersion());
		}

		// The return value is deliberately ignored here: a caller triggered by a real Kafka message
		// has nothing further to do with the decision either way. A NEW decision was already recorded
		// and its outbox row already written, inside decide(...)'s own transaction; an empty Optional
		// means this exact message was already handled by an earlier delivery, and doing nothing
		// further is precisely the correct response to that (FR-030).
		reservationService.decide(event.messageId(), event.orderId(), event.showId(), event.seatIds());
	}
}
