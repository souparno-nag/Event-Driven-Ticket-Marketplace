package com.marketplace.orders;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

// WHY @EnableScheduling lives here rather than closer to OutboxRelay: it switches on Spring's
// scheduling infrastructure for the WHOLE application context, which is an application-wide decision
// -- exactly the kind @SpringBootApplication already groups on this class -- not a property of any
// one @Scheduled method. Without it, OutboxRelay's @Scheduled annotation is inert: the method compiles
// and does nothing, on no timer at all, with no error raised anywhere to say so.
@EnableScheduling
@SpringBootApplication
public class OrderServiceApplication {

	public static void main(String[] args) {
		SpringApplication.run(OrderServiceApplication.class, args);
	}

}
