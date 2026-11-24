package com.propertyapp.negotiation;

import com.propertyapp.subscriptions.Access.Metered;
import com.propertyapp.subscriptions.Access.RequiresFeature;
import com.propertyapp.subscriptions.PlanCatalog.Feature;
import com.propertyapp.valuation.ValuationEngine;
import jakarta.validation.Valid;
import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** Negotiation Assistant: offer suggestions and evidence-based arguments. */
@RestController
class NegotiationController {

	private final NegotiationAssistant assistant;

	NegotiationController(NegotiationAssistant assistant) {
		this.assistant = assistant;
	}

	@RequiresFeature(Feature.NEGOTIATION)
	@Metered
	@PostMapping("/api/v1/negotiation")
	NegotiationAssistant.Result handle(@Valid @RequestBody NegotiationAssistant.Request request) {
		return assistant.advise(request);
	}

	@Configuration
	static class Config {
		@Bean
		NegotiationAssistant negotiationAssistant(ValuationEngine engine, Clock clock) {
			return new NegotiationAssistant(engine, clock);
		}
	}
}
