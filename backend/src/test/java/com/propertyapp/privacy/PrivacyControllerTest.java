package com.propertyapp.privacy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.propertyapp.TestAccounts;
import com.propertyapp.audit.AuditLog;
import com.propertyapp.audit.InMemoryAuditLog;
import com.propertyapp.common.SecurityHeadersFilter;
import com.propertyapp.inspection.AiInspectionClient;
import com.propertyapp.inspection.InspectionReport;
import com.propertyapp.users.AccountStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
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
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Registration consent, AI-processing consent, export, erasure, secure files, audit log and headers. */
@SpringBootTest
class PrivacyControllerTest {

	@TempDir
	static Path photoDir;

	@DynamicPropertySource
	static void properties(DynamicPropertyRegistry registry) {
		registry.add("app.inspection.photo-dir", () -> photoDir.toString());
	}

	static final byte[] JPEG = {(byte) 0xff, (byte) 0xd8, (byte) 0xff, (byte) 0xe0, 1, 2, 3, 4};
	static final String PASSWORD = "correct horse battery";
	static final AtomicInteger IP = new AtomicInteger(1);

	@Autowired
	WebApplicationContext context;

	@Autowired
	AccountStore accounts;

	@Autowired
	AuditLog auditLog;

	@MockitoBean
	AiInspectionClient ai;

	MockMvc anonymous;

	@BeforeEach
	void setUp() {
		anonymous = MockMvcBuilders.webAppContextSetup(context).addFilters(context.getBean(SecurityHeadersFilter.class)).build();
		when(ai.inspect(any())).thenReturn(new InspectionReport("p1", "2026-11-25T10:00:00Z", "fake", List.of("fake"),
				new InspectionReport.PhotoCounts(1, 1, List.of()), List.of(), List.of(), 0, "Photos only."));
	}

	/** Each test registers from its own address, so the per-address sign-up limit is not shared. */
	static RequestPostProcessor from(String ip) {
		return r -> {
			r.setRemoteAddr(ip);
			return r;
		};
	}

	static String newIp() {
		return "10.1." + (IP.get() / 250) + "." + (IP.getAndIncrement() % 250 + 1);
	}

	static MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder b, String body) {
		return b.contentType(MediaType.APPLICATION_JSON).content(body);
	}

	record Registered(String email, String bearer, UUID id) {}

	Registered register(String extra) throws Exception {
		String email = "p-" + UUID.randomUUID() + "@example.test";
		String body = anonymous.perform(json(post("/api/v1/auth/register"), """
						{"email":"%s","password":"%s","fullName":"Privacy Tester","acceptTerms":true%s}
						""".formatted(email, PASSWORD, extra)).with(from(newIp())))
				.andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
		JsonNode n = JsonMapper.shared().readTree(body);
		return new Registered(email, "Bearer " + n.path("token").asString(), UUID.fromString(n.path("user").path("id").asString()));
	}

	MockMultipartFile photo() {
		return new MockMultipartFile("photos", "k.jpg", "image/jpeg", JPEG);
	}

	org.springframework.test.web.servlet.RequestBuilder upload(String bearer) {
		return multipart("/api/v1/inspections").file(photo()).param("propertyId", "p1").param("photoIds", "k1")
				.param("areas", "kitchen").header("Authorization", bearer).header("X-Property-Ref", "p1");
	}

	// Registration and consent ---------------------------------------------------------------------

	@Test
	void registrationNeedsTheTermsAndRecordsTheConsents() throws Exception {
		anonymous.perform(json(post("/api/v1/auth/register"),
						"{\"email\":\"no-terms@example.test\",\"password\":\"" + PASSWORD + "\",\"fullName\":\"No Terms\"}")
						.with(from(newIp())))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.detail").value("Accept the terms and privacy notice to create an account."));
		assertThat(accounts.userByEmail("no-terms@example.test")).isEmpty();

		var u = register(",\"marketingEmails\":true");
		anonymous.perform(get("/api/v1/privacy/consents").header("Authorization", u.bearer()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.policyVersion").value(ConsentService.POLICY_VERSION))
				.andExpect(jsonPath("$.consents[?(@.purpose=='terms')].granted").value(true))
				.andExpect(jsonPath("$.consents[?(@.purpose=='privacy-notice')].granted").value(true))
				.andExpect(jsonPath("$.consents[?(@.purpose=='marketing-email')].granted").value(true))
				.andExpect(jsonPath("$.consents[?(@.purpose=='ai-photo-processing')].granted").value(false));
	}

	@Test
	void requiredConsentsCannotBeWithdrawnOptionalOnesCan() throws Exception {
		var u = register(",\"marketingEmails\":true");
		anonymous.perform(json(post("/api/v1/privacy/consents"), "{\"purpose\":\"terms\",\"granted\":false}")
						.header("Authorization", u.bearer()))
				.andExpect(status().isConflict());
		anonymous.perform(json(post("/api/v1/privacy/consents"), "{\"purpose\":\"marketing-email\",\"granted\":false}")
						.header("Authorization", u.bearer()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.consents[?(@.purpose=='marketing-email')].granted").value(false));
		anonymous.perform(json(post("/api/v1/privacy/consents"), "{\"purpose\":\"profiling\",\"granted\":true}")
						.header("Authorization", u.bearer()))
				.andExpect(status().isBadRequest());
	}

	@Test
	void photosReachTheAiProviderOnlyWithConsentAndARefusalCostsNoAnalysis() throws Exception {
		var u = register("");
		anonymous.perform(upload(u.bearer()))
				.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.code").value("consent-required"))
				.andExpect(jsonPath("$.purpose").value("ai-photo-processing"));
		anonymous.perform(get("/api/v1/subscription").header("Authorization", u.bearer()))
				.andExpect(jsonPath("$.usage.used").value(0));
		assertThat(accounts.userById(u.id())).isPresent();

		anonymous.perform(json(post("/api/v1/privacy/consents"), "{\"purpose\":\"ai-photo-processing\",\"granted\":true,\"source\":\"prompt\"}")
						.header("Authorization", u.bearer()))
				.andExpect(status().isOk());
		anonymous.perform(upload(u.bearer())).andExpect(status().isOk());

		// Withdrawing stops further uploads.
		anonymous.perform(json(post("/api/v1/privacy/consents"), "{\"purpose\":\"ai-photo-processing\",\"granted\":false}")
						.header("Authorization", u.bearer()))
				.andExpect(status().isOk());
		anonymous.perform(upload(u.bearer())).andExpect(status().isForbidden());
	}

	// Secure file access -----------------------------------------------------------------------

	String uploadedKey(String bearer) throws Exception {
		anonymous.perform(upload(bearer)).andExpect(status().isOk());
		String list = anonymous.perform(get("/api/v1/files").header("Authorization", bearer))
				.andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
		return JsonMapper.shared().readTree(list).path(0).path("key").asString();
	}

	@Test
	void filesAreServedOnlyToTheirOwnerAndEveryAccessIsAudited() throws Exception {
		var owner = register(",\"aiPhotoProcessing\":true");
		var other = register(",\"aiPhotoProcessing\":true");
		String key = uploadedKey(owner.bearer());
		assertThat(key).startsWith("accounts/" + owner.id() + "/properties/p1/");

		anonymous.perform(get("/api/v1/files").param("key", key).header("Authorization", owner.bearer()))
				.andExpect(status().isOk())
				.andExpect(header().string("Content-Type", "image/jpeg"))
				.andExpect(header().string("X-Content-Type-Options", "nosniff"))
				.andExpect(header().string("Cache-Control", org.hamcrest.Matchers.containsString("no-store")));

		// Another account, a made-up key and a traversal attempt all look the same: 404.
		anonymous.perform(get("/api/v1/files").param("key", key).header("Authorization", other.bearer())).andExpect(status().isNotFound());
		anonymous.perform(get("/api/v1/files").param("key", "accounts/" + owner.id() + "/x.jpg").header("Authorization", other.bearer()))
				.andExpect(status().isNotFound());
		anonymous.perform(get("/api/v1/files").param("key", "../../etc/passwd").header("Authorization", other.bearer()))
				.andExpect(status().isNotFound());
		anonymous.perform(delete("/api/v1/files").param("key", key).header("Authorization", other.bearer())).andExpect(status().isNotFound());
		anonymous.perform(get("/api/v1/files").param("key", key)).andExpect(status().isUnauthorized());
		anonymous.perform(get("/api/v1/files").header("Authorization", other.bearer())).andExpect(jsonPath("$.length()").value(0));

		assertThat(auditLog.forUser(owner.id(), 50)).anyMatch(e -> e.action().equals("file.read") && e.outcome().equals("success"));
		assertThat(auditLog.forUser(other.id(), 50)).filteredOn(e -> e.action().startsWith("file.")).allMatch(e -> e.outcome().equals("denied"))
				.hasSizeGreaterThanOrEqualTo(4);

		anonymous.perform(delete("/api/v1/files").param("key", key).header("Authorization", owner.bearer())).andExpect(status().isNoContent());
		assertThat(Files.exists(photoDir.resolve(key))).isFalse();
		anonymous.perform(get("/api/v1/files").param("key", key).header("Authorization", owner.bearer())).andExpect(status().isNotFound());
	}

	@Test
	void teamMembersShareTheOwnersFiles() throws Exception {
		var owner = TestAccounts.create(context, "business");
		var member = register(",\"aiPhotoProcessing\":true");
		var ownerMvc = TestAccounts.mvc(context, owner);
		ownerMvc.perform(json(post("/api/v1/team"), "{\"name\":\"Agency\"}")).andExpect(status().isCreated());
		ownerMvc.perform(json(post("/api/v1/team/members"), "{\"email\":\"" + member.email() + "\"}")).andExpect(status().isCreated());

		String key = uploadedKey(member.bearer());
		assertThat(key).startsWith("accounts/" + owner.session().user().id() + "/");
		ownerMvc.perform(get("/api/v1/files").param("key", key)).andExpect(status().isOk());
	}

	// Export and erasure -------------------------------------------------------------------------

	@Test
	void exportHoldsTheUsersDataAndNoSecrets() throws Exception {
		var u = register(",\"aiPhotoProcessing\":true");
		var someoneElse = register("");
		uploadedKey(u.bearer());
		String body = anonymous.perform(get("/api/v1/privacy/export").header("Authorization", u.bearer()))
				.andExpect(status().isOk())
				.andExpect(header().string("Content-Disposition", "attachment; filename=\"my-data.json\""))
				.andExpect(jsonPath("$.format").value(PrivacyService.EXPORT_FORMAT))
				.andExpect(jsonPath("$.account.email").value(u.email()))
				.andExpect(jsonPath("$.consents.length()").value(4))
				.andExpect(jsonPath("$.uploadedFiles.length()").value(1))
				.andExpect(jsonPath("$.usage[0].propertyRef").value("p:p1"))
				.andExpect(jsonPath("$.activity[?(@.action=='auth.register')]").exists())
				.andReturn().getResponse().getContentAsString();
		String token = u.bearer().substring(7);
		assertThat(body).doesNotContain("passwordHash", "$2a$", token, someoneElse.email(), "ipHash");
	}

	@Test
	void erasureNeedsThePasswordAndRemovesTheAccountAndItsFiles() throws Exception {
		var u = register(",\"aiPhotoProcessing\":true");
		String key = uploadedKey(u.bearer());
		assertThat(Files.exists(photoDir.resolve(key))).isTrue();

		anonymous.perform(json(post("/api/v1/privacy/erasure"), "{\"password\":\"wrong password!\"}").header("Authorization", u.bearer()))
				.andExpect(status().isUnauthorized());
		assertThat(accounts.userById(u.id())).isPresent();

		anonymous.perform(json(post("/api/v1/privacy/erasure"), "{\"password\":\"" + PASSWORD + "\"}").header("Authorization", u.bearer()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.filesDeleted").value(1));

		assertThat(accounts.userById(u.id())).isEmpty();
		assertThat(Files.exists(photoDir.resolve(key))).isFalse();
		anonymous.perform(get("/api/v1/auth/me").header("Authorization", u.bearer())).andExpect(status().isUnauthorized());
		anonymous.perform(json(post("/api/v1/auth/login"), "{\"email\":\"" + u.email() + "\",\"password\":\"" + PASSWORD + "\"}")
						.with(from(newIp())))
				.andExpect(status().isUnauthorized());
		// The audit trail keeps what happened, but no longer who.
		var all = ((InMemoryAuditLog) auditLog).all();
		assertThat(all).noneMatch(e -> u.id().equals(e.actorUserId()));
		assertThat(all).anyMatch(e -> e.action().equals("privacy.erasure") && e.outcome().equals("success") && e.actorUserId() == null);
	}

	@Test
	void anActivePaidPlanMustBeCancelledBeforeErasure() throws Exception {
		var u = register("");
		Instant now = Instant.now();
		accounts.saveSubscription(new AccountStore.Subscription(null, u.id(), "premium", "active", "paid", "stripe", "sub_123", "cus_123",
				now, now.plus(30, ChronoUnit.DAYS), false, null, null));
		anonymous.perform(json(post("/api/v1/privacy/erasure"), "{\"password\":\"" + PASSWORD + "\"}").header("Authorization", u.bearer()))
				.andExpect(status().isConflict());
		assertThat(accounts.userById(u.id())).isPresent();
	}

	// Audit log, rate limits, headers ------------------------------------------------------------

	@Test
	void signInsAreAuditedWithoutEmailsOrAddresses() throws Exception {
		var u = register("");
		String ip = newIp();
		anonymous.perform(json(post("/api/v1/auth/login"), "{\"email\":\"" + u.email() + "\",\"password\":\"nope nope nope\"}").with(from(ip)))
				.andExpect(status().isUnauthorized());
		anonymous.perform(json(post("/api/v1/auth/login"), "{\"email\":\"" + u.email() + "\",\"password\":\"" + PASSWORD + "\"}").with(from(ip)))
				.andExpect(status().isOk());

		var all = ((InMemoryAuditLog) auditLog).all();
		assertThat(all).anyMatch(e -> e.action().equals("auth.login") && e.outcome().equals("failure")
				&& e.target().equals(com.propertyapp.audit.Audit.hashed("email", u.email())));
		assertThat(auditLog.forUser(u.id(), 10)).anyMatch(e -> e.action().equals("auth.login") && e.outcome().equals("success"));
		assertThat(all.toString()).doesNotContain(u.email(), ip, PASSWORD);

		anonymous.perform(get("/api/v1/privacy/activity").header("Authorization", u.bearer()))
				.andExpect(jsonPath("$[0].action").value("auth.login"));
	}

	@Test
	void signUpsAreLimitedPerAddress() throws Exception {
		String ip = newIp();
		for (int i = 0; i < 10; i++) {
			anonymous.perform(json(post("/api/v1/auth/register"), """
							{"email":"burst-%s@example.test","password":"%s","fullName":"Burst","acceptTerms":true}
							""".formatted(UUID.randomUUID(), PASSWORD)).with(from(ip)))
					.andExpect(status().isCreated());
		}
		anonymous.perform(json(post("/api/v1/auth/register"), """
						{"email":"burst-11@example.test","password":"%s","fullName":"Burst","acceptTerms":true}
						""".formatted(PASSWORD)).with(from(ip)))
				.andExpect(status().isTooManyRequests());
	}

	@Test
	void deniedAccessIsAudited() throws Exception {
		var u = register("");
		anonymous.perform(json(post("/api/v1/api-keys"), "{\"name\":\"k\"}").header("Authorization", u.bearer()))
				.andExpect(status().isForbidden());
		assertThat(auditLog.forUser(u.id(), 10)).anyMatch(e -> e.action().equals("access.denied") && e.details().get("reason").equals("upgrade-required"));
	}

	@Test
	void securityHeadersAndThePublicRetentionPolicy() throws Exception {
		anonymous.perform(get("/api/v1/privacy/retention"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.uploadedPhotosAndFloorPlans").value("180 days after upload"))
				.andExpect(header().string("X-Content-Type-Options", "nosniff"))
				.andExpect(header().string("X-Frame-Options", "DENY"))
				.andExpect(header().string("Content-Security-Policy", org.hamcrest.Matchers.startsWith("default-src 'none'")))
				.andExpect(header().string("Referrer-Policy", "no-referrer"))
				.andExpect(header().string("Cache-Control", "no-store"));
		anonymous.perform(get("/api/v1/privacy/export")).andExpect(status().isUnauthorized());
	}
}
