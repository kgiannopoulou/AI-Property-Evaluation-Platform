package com.propertyapp.privacy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.propertyapp.audit.AuditLog;
import com.propertyapp.audit.JdbcAuditLog;
import com.propertyapp.common.Database;
import com.propertyapp.inspection.AiInspectionClient;
import com.propertyapp.inspection.InspectionReport;
import com.propertyapp.users.AccountStore;
import com.propertyapp.users.JdbcAccountStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The privacy flow against a real PostgreSQL with the migrations applied: JDBC stores, the V9
 * triggers, cascades and purge function. Runs when TEST_DATABASE_URL is set (CI sets it), e.g.
 * TEST_DATABASE_URL=jdbc:postgresql://localhost:5432/property_intelligence TEST_DATABASE_PASSWORD=...
 */
@SpringBootTest
@EnabledIfEnvironmentVariable(named = "TEST_DATABASE_URL", matches = ".+")
class PostgresPrivacyTest {

	@TempDir
	static Path photoDir;

	@DynamicPropertySource
	static void properties(DynamicPropertyRegistry registry) {
		registry.add("app.database.url", () -> System.getenv("TEST_DATABASE_URL"));
		registry.add("app.database.user", () -> System.getenv().getOrDefault("TEST_DATABASE_USER", "property"));
		registry.add("app.database.password", () -> System.getenv().getOrDefault("TEST_DATABASE_PASSWORD", ""));
		registry.add("app.inspection.photo-dir", () -> photoDir.toString());
	}

	static final String PASSWORD = "correct horse battery";
	static final byte[] JPEG = {(byte) 0xff, (byte) 0xd8, (byte) 0xff, (byte) 0xe0, 1, 2, 3, 4};

	@Autowired
	WebApplicationContext context;

	@Autowired
	Database database;

	@Autowired
	AccountStore accounts;

	@Autowired
	AuditLog auditLog;

	@Autowired
	PrivacyStore privacyStore;

	@Autowired
	RetentionService retention;

	@MockitoBean
	AiInspectionClient ai;

	@Test
	void usesTheDatabaseStores() {
		assertThat(accounts).isInstanceOf(JdbcAccountStore.class);
		assertThat(auditLog).isInstanceOf(JdbcAuditLog.class);
		assertThat(privacyStore).isInstanceOf(JdbcPrivacyStore.class);
	}

	@Test
	void registerConsentUploadExportEraseAndPurge() throws Exception {
		when(ai.inspect(any())).thenReturn(new InspectionReport("p1", "2026-11-25T10:00:00Z", "fake", List.of("fake"),
				new InspectionReport.PhotoCounts(1, 1, List.of()), List.of(), List.of(), 0, "Photos only."));
		MockMvc mvc = MockMvcBuilders.webAppContextSetup(context).build();
		JdbcTemplate jdbc = database.jdbc().orElseThrow();
		String email = "it-" + UUID.randomUUID() + "@example.test";

		String body = mvc.perform(post("/api/v1/auth/register").contentType(MediaType.APPLICATION_JSON).content("""
						{"email":"%s","password":"%s","fullName":"IT User","acceptTerms":true,"aiPhotoProcessing":true}
						""".formatted(email, PASSWORD)).with(r -> {
					r.setRemoteAddr("10.9.0.1");
					return r;
				}))
				.andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
		JsonNode n = JsonMapper.shared().readTree(body);
		String bearer = "Bearer " + n.path("token").asString();
		UUID id = UUID.fromString(n.path("user").path("id").asString());

		assertThat(jdbc.queryForObject("SELECT count(*) FROM consent_records WHERE user_id = ?", Integer.class, id)).isEqualTo(4);
		assertThat(jdbc.queryForObject("SELECT granted FROM current_consents WHERE user_id = ? AND purpose = 'ai-photo-processing'",
				Boolean.class, id)).isTrue();

		mvc.perform(multipart("/api/v1/inspections").file(new MockMultipartFile("photos", "k.jpg", "image/jpeg", JPEG))
						.param("propertyId", "p1").param("photoIds", "k1").param("areas", "kitchen")
						.header("Authorization", bearer).header("X-Property-Ref", "p1"))
				.andExpect(status().isOk());
		String key = jdbc.queryForObject("SELECT storage_key FROM stored_files WHERE owner_user_id = ?", String.class, id);
		assertThat(Files.exists(photoDir.resolve(key))).isTrue();

		mvc.perform(get("/api/v1/privacy/export").header("Authorization", bearer))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.account.email").value(email))
				.andExpect(jsonPath("$.usage[0].propertyRef").value("p:p1"))
				.andExpect(jsonPath("$.uploadedFiles[0].storageKey").value(key))
				.andExpect(jsonPath("$.activity[?(@.action=='auth.register')]").exists());

		mvc.perform(post("/api/v1/privacy/erasure").contentType(MediaType.APPLICATION_JSON).content("{\"password\":\"" + PASSWORD + "\"}")
						.header("Authorization", bearer))
				.andExpect(status().isOk());
		assertThat(jdbc.queryForObject("SELECT count(*) FROM users WHERE id = ?", Integer.class, id)).isZero();
		assertThat(jdbc.queryForObject("SELECT count(*) FROM consent_records WHERE user_id = ?", Integer.class, id)).isZero();
		assertThat(jdbc.queryForObject("SELECT count(*) FROM stored_files WHERE owner_user_id = ?", Integer.class, id)).isZero();
		assertThat(jdbc.queryForObject("SELECT count(*) FROM usage_events WHERE account_user_id = ?", Integer.class, id)).isZero();
		assertThat(Files.exists(photoDir.resolve(key))).isFalse();
		// Audit events survive without the actor; the erasure itself is recorded.
		assertThat(jdbc.queryForObject("""
				SELECT count(*) FROM audit_events WHERE action = 'privacy.erasure' AND target = ? AND actor_user_id IS NULL
				""", Integer.class, com.propertyapp.audit.Audit.hashed("user", id.toString()))).isEqualTo(1);

		var counts = retention.run();
		assertThat(counts).containsKeys("uploaded-files", "expired-sessions", "audit-events", "usage-events");
	}
}
