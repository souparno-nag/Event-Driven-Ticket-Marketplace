package com.marketplace.inventory;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

// WHY @EnableScheduling lives here rather than closer to OutboxRelay or LapsedReservationSweeper: it
// switches on Spring's scheduling infrastructure for the WHOLE application context, which is an
// application-wide decision -- exactly the kind @SpringBootApplication already groups on this class --
// not a property of any one @Scheduled method. Without it, both classes' @Scheduled annotations are
// inert: each method compiles and does nothing, on no timer at all, with no error raised anywhere to
// say so.
//
// CORRECTION: this was missing from T130-T133's own port of OutboxRelay, and went unnoticed because
// every test exercising that class so far called pollAndPublish() directly, bypassing Spring's
// scheduler entirely rather than proving it actually fires on its own. Caught while wiring
// LapsedReservationSweeper (T161), which made the same silent gap concrete a second time. Ported from
// order-service's own OrderServiceApplication, which carries the identical annotation for the
// identical reason.
@EnableScheduling
@SpringBootApplication
public class InventoryServiceApplication {

	public static void main(String[] args) {
		SpringApplication.run(InventoryServiceApplication.class, args);
	}

}
