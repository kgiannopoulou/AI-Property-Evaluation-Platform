package com.propertyapp.valuation;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = "app.database.url=")
class ValuationControllerTest {

	@Autowired
	MockMvc mvc;

	static final String SUBJECT = """
			{"listingType":"sale","propertyType":"apartment","sizeSqm":70,"bedrooms":2,"bathrooms":1,"floor":2,
			 "elevator":true,"parking":false,"yearBuilt":1978,"condition":"fair","energyClass":"D","askingPrice":220000,
			 "location":{"lat":37.9665,"lon":23.744}}
			""";

	@Test
	void withoutMarketDataTheEndpointSaysSoInsteadOfGuessing() throws Exception {
		mvc.perform(post("/api/v1/valuations").contentType(MediaType.APPLICATION_JSON)
						.content("{\"subject\":" + SUBJECT + "}"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("insufficient-data"))
				.andExpect(jsonPath("$.estimate").doesNotExist())
				.andExpect(jsonPath("$.negotiation").doesNotExist())
				.andExpect(jsonPath("$.dataQuality.metrics.marketDataConnected").value(false))
				.andExpect(jsonPath("$.conditionScore.score").isNumber());
	}

	@Test
	void userSuppliedSalesAreEnoughForAValuation() throws Exception {
		String sale = """
				{"listingType":"sold","propertyType":"apartment","sizeSqm":%d,"price":%d,"observedOn":"2026-09-01",
				 "distanceM":150,"verified":true}
				""";
		String body = "{\"subject\":" + SUBJECT + ",\"additionalComparables\":["
				+ String.join(",", sale.formatted(70, 203000), sale.formatted(72, 210000), sale.formatted(68, 196000))
				+ "]}";
		mvc.perform(post("/api/v1/valuations").contentType(MediaType.APPLICATION_JSON).content(body))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("valued"))
				.andExpect(jsonPath("$.estimate.mid").isNumber())
				.andExpect(jsonPath("$.negotiation.maxOffer").isNumber());
	}

	@Test
	void rejectsInvalidInput() throws Exception {
		mvc.perform(post("/api/v1/valuations").contentType(MediaType.APPLICATION_JSON)
						.content("{\"subject\":" + SUBJECT.replace("\"fair\"", "\"great\"") + "}"))
				.andExpect(status().isBadRequest());
	}
}
