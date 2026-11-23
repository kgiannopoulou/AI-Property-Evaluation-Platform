package com.propertyapp.inspection;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class AiInspectionClientTest {

	MockRestServiceServer server;
	AiInspectionClient client;

	@BeforeEach
	void setUp() {
		RestClient.Builder builder = RestClient.builder()
				.baseUrl("http://ai.test")
				.defaultHeader("Authorization", "Bearer secret");
		server = MockRestServiceServer.bindTo(builder).build();
		client = new AiInspectionClient(builder.build());
	}

	static AiInspectionClient.Request request() {
		return new AiInspectionClient.Request(
				"p1",
				List.of(new AiInspectionClient.PhotoRef("k1", "properties/p1/a.jpg", "kitchen")),
				Map.of("listingType", "sale"));
	}

	@Test
	void sendsStorageKeysAndParsesTheReport() {
		server.expect(requestTo("http://ai.test/v1/inspections"))
				.andExpect(method(HttpMethod.POST))
				.andExpect(header("Authorization", "Bearer secret"))
				.andExpect(content().json("""
						{"propertyId":"p1",
						 "photos":[{"photoId":"k1","storageKey":"properties/p1/a.jpg","area":"kitchen"}],
						 "context":{"listingType":"sale"}}"""))
				.andRespond(withSuccess("""
						{"propertyId":"p1","generatedAt":"2026-11-23T10:00:00Z","model":"claude-opus-5-5",
						 "servedBy":["claude-opus-5-5"],
						 "photos":{"submitted":1,"analysed":1,"skipped":[]},
						 "findings":[{"id":"f1","photoIds":["k1"],"category":"windows","categoryLabel":"Old or damaged windows",
						   "title":"Single-glazed window","description":"Old aluminium frame.","severity":"minor",
						   "confidence":0.74,"assessment":"observed","limitation":null,"recommendation":"Consider replacement.",
						   "estimatedCost":{"low":450,"high":900,"currency":"EUR","basis":"illustrative","source":"s",
						     "region":"Greece (Attica)","asOf":"2026-11","covers":"One window"},
						   "costNote":"Illustrative range, not verified local cost data."}],
						 "areaConditions":[{"area":"kitchen","condition":"fair","summary":"Dated."}],
						 "discardedFindings":0,"disclaimer":"Photos only.","unknownField":true}""",
						MediaType.APPLICATION_JSON));

		InspectionReport report = client.inspect(request());

		assertThat(report.findings()).hasSize(1);
		var f = report.findings().get(0);
		assertThat(f.photoIds()).containsExactly("k1");
		assertThat(f.confidence()).isEqualTo(0.74);
		assertThat(f.estimatedCost().low()).isEqualByComparingTo("450");
		assertThat(f.estimatedCost().basis()).isEqualTo("illustrative");
		assertThat(report.areaConditions().get(0).condition()).isEqualTo("fair");
		server.verify();
	}

	@Test
	void mapsErrorsToAiServiceException() {
		server.expect(requestTo("http://ai.test/v1/inspections"))
				.andRespond(withStatus(HttpStatus.BAD_GATEWAY).body("{\"error\":\"declined\"}"));
		assertThatThrownBy(() -> client.inspect(request()))
				.isInstanceOf(AiInspectionClient.AiServiceException.class)
				.hasMessage("declined")
				.satisfies(e -> assertThat(((AiInspectionClient.AiServiceException) e).upstreamStatus()).isEqualTo(502));
	}

	@Test
	void fallsBackToAGenericMessageWhenTheServiceSentNone() {
		server.expect(requestTo("http://ai.test/v1/inspections")).andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));
		assertThatThrownBy(() -> client.inspect(request()))
				.isInstanceOf(AiInspectionClient.AiServiceException.class)
				.hasMessage("The AI model is busy; try again shortly.");
	}
}
