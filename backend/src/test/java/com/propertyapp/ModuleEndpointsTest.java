package com.propertyapp;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.propertyapp.floorplan.FloorPlanReader;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.context.WebApplicationContext;

/** Every Phase 8 module answers on its endpoint (no database, AI service mocked). */
@SpringBootTest
@AutoConfigureMockMvc
class ModuleEndpointsTest {

	@TempDir
	static Path photoDir;

	@DynamicPropertySource
	static void properties(DynamicPropertyRegistry registry) {
		registry.add("app.inspection.photo-dir", () -> photoDir.toString());
		registry.add("app.database.url", () -> "");
	}

	@Autowired
	WebApplicationContext context;

	MockMvc mvc;

	@BeforeEach
	void signIn() {
		mvc = com.propertyapp.TestAccounts.mvc(context, "business");
	}

	@MockitoBean
	FloorPlanReader floorPlans;

	static final String SUBJECT = """
			{"listingType":"sale","propertyType":"apartment","sizeSqm":70,"bedrooms":2,"bathrooms":1,"floor":2,
			 "elevator":true,"parking":false,"yearBuilt":1978,"condition":"fair","energyClass":"D","askingPrice":220000,
			 "location":{"lat":37.9665,"lon":23.744}}""";

	private org.springframework.test.web.servlet.ResultActions postJson(String path, String body) throws Exception {
		return mvc.perform(post(path).contentType(MediaType.APPLICATION_JSON).content(body));
	}

	@Test
	void calculators() throws Exception {
		postJson("/api/v1/financial", "{\"price\":200000,\"taxBasis\":\"transfer-tax\",\"mortgage\":{\"loanToValue\":0.8,\"annualRatePct\":4,\"years\":25}}")
				.andExpect(status().isOk()).andExpect(jsonPath("$.totalPurchaseCosts").value(16754));
		postJson("/api/v1/investment", "{\"purchasePrice\":200000,\"purchaseCosts\":0,\"renovationCosts\":0,\"monthlyRent\":1000,"
				+ "\"monthlyCommonCharges\":0,\"annualPropertyTax\":0,\"annualInsurance\":0,\"loanAmount\":0,\"loanRatePct\":0,\"loanYears\":0}")
				.andExpect(status().isOk()).andExpect(jsonPath("$.grossYieldPct").value(6.0));
		postJson("/api/v1/renovation", "{\"sizeSqm\":70,\"bathrooms\":1,\"yearBuilt\":1978,\"condition\":\"fair\"}")
				.andExpect(status().isOk()).andExpect(jsonPath("$.scenarios.length()").value(3));
		postJson("/api/v1/building", "{\"yearBuilt\":1975,\"propertySizeSqm\":70,\"elevatorCount\":1}")
				.andExpect(status().isOk()).andExpect(jsonPath("$.components.length()").value(5));
		postJson("/api/v1/documents/check", "{\"listingType\":\"sale\",\"declared\":{},\"documents\":[]}")
				.andExpect(status().isOk()).andExpect(jsonPath("$.readiness").value("blocked"));
		postJson("/api/v1/comparison", "{\"properties\":[{\"id\":\"a\",\"title\":\"A\",\"askingPrice\":200000,\"sizeSqm\":70},"
				+ "{\"id\":\"b\",\"title\":\"B\",\"askingPrice\":180000,\"sizeSqm\":70}]}")
				.andExpect(status().isOk()).andExpect(jsonPath("$.ranking[0].id").value("b"));
	}

	@Test
	void dataBackedModulesSayWhenNoDatabaseIsConnected() throws Exception {
		postJson("/api/v1/market", "{\"listingType\":\"sale\",\"propertyType\":\"apartment\",\"sizeSqm\":70}")
				.andExpect(status().isOk()).andExpect(jsonPath("$.marketDataConnected").value(false));
		postJson("/api/v1/location", "{\"lat\":37.9665,\"lon\":23.744}")
				.andExpect(status().isOk()).andExpect(jsonPath("$.dataConnected").value(false))
				.andExpect(jsonPath("$.risks[0].status").value("not-assessed"));
		postJson("/api/v1/negotiation", "{\"valuation\":{\"subject\":" + SUBJECT + "}}")
				.andExpect(status().isOk()).andExpect(jsonPath("$.price").doesNotExist());
	}

	@Test
	void pdfReport() throws Exception {
		postJson("/api/v1/reports/pdf", "{\"title\":\"Test flat\",\"valuation\":{\"subject\":" + SUBJECT + "}}")
				.andExpect(status().isOk())
				.andExpect(content().contentType(MediaType.APPLICATION_PDF))
				.andExpect(header().string("Content-Disposition", "attachment; filename=\"property-report.pdf\""));
	}

	@Test
	void floorPlanUploadIsStoredReadAndChecked() throws Exception {
		when(floorPlans.read(anyString(), anyString())).thenReturn(new FloorPlanReader.Reading("p1", "m", "m", true, "clear",
				"metres", "1:50", List.of(new FloorPlanReader.Room("Bedroom", "bedroom", 12.0, "printed", null, null, "12 m²", 0.9, List.of())),
				null, "", List.of(), "AI transcription"));
		byte[] jpeg = {(byte) 0xff, (byte) 0xd8, (byte) 0xff, (byte) 0xe0, 1, 2, 3, 4};
		mvc.perform(multipart("/api/v1/floor-plans").file(new MockMultipartFile("plan", "plan.jpg", "image/jpeg", jpeg))
						.param("propertyId", "p1").param("bedrooms", "2"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.reading.rooms[0].areaSqm").value(12.0))
				.andExpect(jsonPath("$.checks[0].check").value("Bedrooms"))
				.andExpect(jsonPath("$.checks[0].status").value("mismatch"));
		mvc.perform(multipart("/api/v1/floor-plans").file(new MockMultipartFile("plan", "plan.txt", "text/plain", "hello".getBytes()))
						.param("propertyId", "p1"))
				.andExpect(status().isBadRequest());
	}

	@Test
	void validationErrors() throws Exception {
		postJson("/api/v1/financial", "{\"price\":-5,\"taxBasis\":\"transfer-tax\"}").andExpect(status().isBadRequest());
		postJson("/api/v1/comparison", "{\"properties\":[{\"id\":\"a\",\"title\":\"A\",\"askingPrice\":1,\"sizeSqm\":1}]}")
				.andExpect(status().isBadRequest());
	}
}
