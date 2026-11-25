package com.propertyapp.subscriptions;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.propertyapp.TestAccounts;
import com.propertyapp.inspection.AiInspectionClient;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.json.JsonMapper;

/** Plans and limits are enforced by the backend, whatever the website shows. */
@SpringBootTest
class EnforcementTest {

	@TempDir
	static Path photoDir;

	@DynamicPropertySource
	static void properties(DynamicPropertyRegistry registry) {
		registry.add("app.inspection.photo-dir", () -> photoDir.toString());
		registry.add("app.database.url", () -> "");
		registry.add("app.billing.demo-mode", () -> "true");
	}

	@Autowired
	WebApplicationContext context;

	@MockitoBean
	AiInspectionClient ai;

	static final String SUBJECT = """
			{"listingType":"sale","propertyType":"apartment","sizeSqm":70,"bedrooms":2,"bathrooms":1,"floor":2,
			 "elevator":true,"parking":false,"yearBuilt":1978,"condition":"fair","energyClass":"D","askingPrice":220000}""";
	static final String THREE_SALES = "[" + String.join(",",
			sale(70, 203000), sale(72, 210000), sale(68, 196000)) + "]";

	static String sale(int size, int price) {
		return "{\"listingType\":\"sold\",\"propertyType\":\"apartment\",\"sizeSqm\":" + size + ",\"price\":" + price
				+ ",\"observedOn\":\"2026-09-01\",\"distanceM\":150,\"verified\":true}";
	}

	static final String VALUATION = "{\"subject\":" + SUBJECT + ",\"additionalComparables\":" + THREE_SALES + "}";

	MockMvc anonymous() {
		return MockMvcBuilders.webAppContextSetup(context).build();
	}

	static MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder b, String body) {
		return b.contentType(MediaType.APPLICATION_JSON).content(body);
	}

	static MockHttpServletRequestBuilder valuation(String ref) {
		var b = json(post("/api/v1/valuations"), VALUATION);
		return ref == null ? b : b.header("X-Property-Ref", ref);
	}

	@Test
	void anonymousCallsAreRejectedButPlansArePublic() throws Exception {
		anonymous().perform(valuation(null)).andExpect(status().isUnauthorized())
				.andExpect(header().string("WWW-Authenticate", "Bearer"))
				.andExpect(jsonPath("$.code").value("unauthenticated"));
		anonymous().perform(valuation(null).header("Authorization", "Bearer not-a-token")).andExpect(status().isUnauthorized());
		anonymous().perform(get("/api/v1/plans")).andExpect(status().isOk())
				.andExpect(jsonPath("$.plans[1].monthlyPriceCents").value(999))
				.andExpect(jsonPath("$.plans[3].customPricing").value(true))
				.andExpect(jsonPath("$.note", containsString("hypotheses")));
	}

	@Test
	void freePlanGetsTheBasicValuationAndTwoAnalysesAMonth() throws Exception {
		MockMvc free = TestAccounts.mvc(context, "free");
		free.perform(valuation("prop-1")).andExpect(status().isOk())
				.andExpect(jsonPath("$.estimate.mid").isNumber())
				.andExpect(jsonPath("$.negotiation").doesNotExist())
				.andExpect(jsonPath("$.comparables").isEmpty())
				.andExpect(jsonPath("$.dataQuality.warnings[-1]", containsString("part of Premium")))
				.andExpect(header().string("X-Analyses-Used", "1"))
				.andExpect(header().string("X-Analyses-Remaining", "1"));
		// Re-running the same property does not count again.
		free.perform(valuation("prop-1")).andExpect(status().isOk()).andExpect(header().string("X-Analyses-Used", "1"));
		// A failed request does not count.
		free.perform(json(post("/api/v1/valuations"), "{\"subject\":{}}").header("X-Property-Ref", "prop-x")).andExpect(status().isBadRequest());
		free.perform(valuation("prop-2")).andExpect(status().isOk()).andExpect(header().string("X-Analyses-Remaining", "0"));
		free.perform(valuation("prop-3")).andExpect(status().isTooManyRequests())
				.andExpect(jsonPath("$.code").value("quota-exceeded"))
				.andExpect(jsonPath("$.limit").value(2))
				.andExpect(jsonPath("$.used").value(2))
				.andExpect(jsonPath("$.resetsOn").isString());
		// Without a property reference every call is a new analysis, so it is refused too.
		free.perform(valuation(null)).andExpect(status().isTooManyRequests());
		free.perform(get("/api/v1/subscription")).andExpect(status().isOk())
				.andExpect(jsonPath("$.plan").value("free"))
				.andExpect(jsonPath("$.usage.used").value(2))
				.andExpect(jsonPath("$.usage.remaining").value(0));
	}

	@Test
	void premiumFeaturesAskFreeUsersToUpgrade() throws Exception {
		MockMvc free = TestAccounts.mvc(context, "free");
		free.perform(json(post("/api/v1/location"), "{\"lat\":37.96,\"lon\":23.74}")).andExpect(status().isForbidden())
				.andExpect(jsonPath("$.code").value("upgrade-required"))
				.andExpect(jsonPath("$.requiredPlan").value("premium"));
		free.perform(json(post("/api/v1/reports/pdf"), "{\"title\":\"T\",\"valuation\":{\"subject\":" + SUBJECT + "}}"))
				.andExpect(status().isForbidden());
		free.perform(json(post("/api/v1/comparison"), "{\"properties\":[" + String.join(",",
						"{\"id\":\"a\",\"title\":\"A\",\"askingPrice\":1,\"sizeSqm\":50}",
						"{\"id\":\"b\",\"title\":\"B\",\"askingPrice\":2,\"sizeSqm\":50}",
						"{\"id\":\"c\",\"title\":\"C\",\"askingPrice\":3,\"sizeSqm\":50}") + "]}"))
				.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.detail", containsString("compares up to 2")));
		var photos = multipart("/api/v1/inspections").param("propertyId", "p1");
		for (int i = 0; i < 7; i++) {
			photos.file(new MockMultipartFile("photos", "p" + i + ".jpg", "image/jpeg", new byte[] {(byte) 0xff, (byte) 0xd8, (byte) 0xff, 1}))
					.param("photoIds", "ph" + i).param("areas", "kitchen");
		}
		free.perform(photos).andExpect(status().isForbidden()).andExpect(jsonPath("$.feature").value("full-inspection"));
		free.perform(get("/api/v1/subscription")).andExpect(jsonPath("$.usage.used").value(0)); // refused calls cost nothing
	}

	@Test
	void premiumGetsEvidenceButNotInvestmentOrClientReports() throws Exception {
		MockMvc premium = TestAccounts.mvc(context, "premium");
		premium.perform(valuation("p1")).andExpect(status().isOk()).andExpect(jsonPath("$.negotiation.maxOffer").isNumber())
				.andExpect(header().string("X-Analyses-Limit", "20"));
		premium.perform(json(post("/api/v1/investment"), "{\"purchasePrice\":200000,\"monthlyRent\":900}"))
				.andExpect(status().isForbidden()).andExpect(jsonPath("$.requiredPlan").value("professional"));
		String clientReport = "{\"title\":\"T\",\"valuation\":{\"subject\":" + SUBJECT + "},\"client\":{\"clientName\":\"Maria\"}}";
		premium.perform(json(post("/api/v1/reports/pdf"), clientReport)).andExpect(status().isForbidden());
		TestAccounts.mvc(context, "professional").perform(json(post("/api/v1/reports/pdf"), clientReport)).andExpect(status().isOk());
		premium.perform(json(post("/api/v1/api-keys"), "{\"name\":\"k\"}")).andExpect(status().isForbidden());
	}

	@Test
	void businessApiKeysBulkAnalysisAndTeams() throws Exception {
		var owner = TestAccounts.create(context, "business");
		MockMvc business = TestAccounts.mvc(context, owner);

		String created = business.perform(json(post("/api/v1/api-keys"), "{\"name\":\"CRM\"}")).andExpect(status().isCreated())
				.andReturn().getResponse().getContentAsString();
		var node = JsonMapper.shared().readTree(created);
		String secret = node.path("secret").asString();
		String keyId = node.path("key").path("id").asString();
		anonymous().perform(valuation("api-1").header("X-API-Key", secret)).andExpect(status().isOk());
		anonymous().perform(json(post("/api/v1/billing/checkout"), "{\"plan\":\"premium\"}").header("X-API-Key", secret))
				.andExpect(status().isForbidden());
		business.perform(delete("/api/v1/api-keys/" + keyId)).andExpect(status().isNoContent());
		anonymous().perform(valuation("api-2").header("X-API-Key", secret)).andExpect(status().isUnauthorized());

		String bulk = "{\"items\":[" + String.join(",",
				"{\"propertyRef\":\"b1\",\"request\":" + VALUATION + "}",
				"{\"propertyRef\":\"b2\",\"request\":" + VALUATION + "}",
				"{\"propertyRef\":\"b1\",\"request\":" + VALUATION + "}") + "]}";
		business.perform(json(post("/api/v1/bulk/valuations"), bulk)).andExpect(status().isOk())
				.andExpect(jsonPath("$.analysesCounted").value(2))
				.andExpect(jsonPath("$.results.length()").value(3))
				.andExpect(jsonPath("$.analysesLimit").value(PlanCatalog.BUSINESS_DEFAULT_ANALYSES));
		TestAccounts.mvc(context, "professional").perform(json(post("/api/v1/bulk/valuations"), bulk)).andExpect(status().isForbidden());

		var member = TestAccounts.create(context, "free");
		business.perform(json(post("/api/v1/team"), "{\"name\":\"Demo Realty\"}")).andExpect(status().isCreated());
		business.perform(json(post("/api/v1/team/members"), "{\"email\":\"" + member.session().user().email() + "\"}"))
				.andExpect(status().isCreated()).andExpect(jsonPath("$.members.length()").value(2));
		MockMvc asMember = TestAccounts.mvc(context, member);
		asMember.perform(get("/api/v1/subscription")).andExpect(jsonPath("$.plan").value("business"))
				.andExpect(jsonPath("$.teamMember").value(true))
				.andExpect(jsonPath("$.canManageBilling").value(false));
		asMember.perform(valuation("m1")).andExpect(status().isOk()).andExpect(jsonPath("$.negotiation").exists());
		// The member's analysis counts against the owner's allowance: api-1, b1, b2, m1.
		business.perform(get("/api/v1/subscription")).andExpect(jsonPath("$.usage.used").value(4));
		asMember.perform(json(post("/api/v1/billing/checkout"), "{\"plan\":\"premium\"}")).andExpect(status().isForbidden());
		business.perform(delete("/api/v1/team/members/" + member.session().user().id())).andExpect(status().isOk());
		asMember.perform(get("/api/v1/subscription")).andExpect(jsonPath("$.plan").value("free"));
	}

	@Test
	void demoBillingSwitchesPlansAndBusinessIsAnEnquiry() throws Exception {
		MockMvc user = TestAccounts.mvc(context, "free");
		user.perform(json(post("/api/v1/billing/checkout"), "{\"plan\":\"premium\"}")).andExpect(status().isOk())
				.andExpect(jsonPath("$.mode").value("demo"));
		user.perform(get("/api/v1/subscription")).andExpect(jsonPath("$.plan").value("premium"))
				.andExpect(jsonPath("$.usage.limit").value(20)).andExpect(jsonPath("$.billingMode").value("demo"));
		user.perform(json(post("/api/v1/billing/checkout"), "{\"plan\":\"professional\"}")).andExpect(status().isOk());
		user.perform(get("/api/v1/subscription")).andExpect(jsonPath("$.plan").value("professional"));
		user.perform(post("/api/v1/billing/cancel")).andExpect(status().isOk()).andExpect(jsonPath("$.plan").value("free"));
		user.perform(json(post("/api/v1/billing/business-enquiry"),
				"{\"company\":\"Demo Realty\",\"contactEmail\":\"ops@example.test\",\"seats\":8,\"monthlyAnalyses\":500}"))
				.andExpect(status().isCreated());
		user.perform(json(post("/api/v1/billing/checkout"), "{\"plan\":\"unknown\"}")).andExpect(status().isBadRequest());
	}

	@Test
	void accountEndpoints() throws Exception {
		String body = anonymous().perform(json(post("/api/v1/auth/register"),
						"{\"email\":\"new.user@example.test\",\"password\":\"correct horse battery\",\"fullName\":\"New User\",\"acceptTerms\":true}"))
				.andExpect(status().isCreated()).andExpect(jsonPath("$.token").isString())
				.andExpect(jsonPath("$.user.passwordHash").doesNotExist())
				.andReturn().getResponse().getContentAsString();
		String token = JsonMapper.shared().readTree(body).path("token").asString();
		anonymous().perform(get("/api/v1/auth/me").header("Authorization", "Bearer " + token))
				.andExpect(jsonPath("$.user.email").value("new.user@example.test")).andExpect(jsonPath("$.plan").value("free"));
		anonymous().perform(json(post("/api/v1/auth/login"), "{\"email\":\"new.user@example.test\",\"password\":\"wrong password!\"}"))
				.andExpect(status().isUnauthorized()).andExpect(jsonPath("$.detail").value("Email or password is incorrect."));
		anonymous().perform(post("/api/v1/auth/logout").header("Authorization", "Bearer " + token)).andExpect(status().isNoContent());
		anonymous().perform(get("/api/v1/auth/me").header("Authorization", "Bearer " + token)).andExpect(status().isUnauthorized());
	}
}
