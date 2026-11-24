package com.propertyapp.inspection;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.test.web.servlet.request.MockMultipartHttpServletRequestBuilder;

@SpringBootTest
@AutoConfigureMockMvc
class InspectionControllerTest {

	@TempDir
	static Path photoDir;

	@DynamicPropertySource
	static void properties(DynamicPropertyRegistry registry) {
		registry.add("app.inspection.photo-dir", () -> photoDir.toString());
	}

	static final byte[] JPEG = {(byte) 0xff, (byte) 0xd8, (byte) 0xff, (byte) 0xe0, 1, 2, 3, 4};
	static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', 0x0d, 0x0a, 0x1a, 0x0a, 1, 2};

	@Autowired
	WebApplicationContext context;

	MockMvc mvc;

	@BeforeEach
	void signIn() {
		mvc = com.propertyapp.TestAccounts.mvc(context, "premium");
	}

	@MockitoBean
	AiInspectionClient ai;

	static MockMultipartFile photo(byte[] data) {
		return new MockMultipartFile("photos", "photo.jpg", "image/jpeg", data);
	}

	static InspectionReport report() {
		var finding = new InspectionReport.Finding(
				"f1", List.of("k1"), "mould-damp", "Possible mould or dampness", "Possible: mould on ceiling",
				"Dark patches in the ceiling corner.", "moderate", 0.8, "potential-concern",
				"Photos cannot show how far moisture has spread.", "Ask for a moisture survey.", null,
				"No reliable local cost data for this repair; ask a contractor for a quote.");
		return new InspectionReport("p1", "2026-11-23T10:00:00Z", "claude-opus-5-5", List.of("claude-opus-5-5"),
				new InspectionReport.PhotoCounts(2, 2, List.of()), List.of(finding),
				List.of(new InspectionReport.AreaCondition("kitchen", "fair", "Dated.")), 0, "Photos only.");
	}

	MockMultipartHttpServletRequestBuilder twoPhotos() {
		return multipart("/api/v1/inspections")
				.file(photo(JPEG))
				.file(new MockMultipartFile("photos", "b.png", "image/png", PNG))
				.param("propertyId", "p1")
				.param("photoIds", "k1", "b1")
				.param("areas", "kitchen", "bathroom");
	}

	@Test
	void storesPhotosAndReturnsTheStructuredFindings() throws Exception {
		when(ai.inspect(any())).thenReturn(report());

		mvc.perform(twoPhotos()
						.param("listingType", "rent")
						.param("checklist.dishwasher", "missing")
						.param("checklist.sauna", "working"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.findings[0].photoIds[0]").value("k1"))
				.andExpect(jsonPath("$.findings[0].assessment").value("potential-concern"))
				.andExpect(jsonPath("$.findings[0].confidence").value(0.8))
				.andExpect(jsonPath("$.findings[0].recommendation").value("Ask for a moisture survey."))
				.andExpect(jsonPath("$.disclaimer").value("Photos only."));

		ArgumentCaptor<AiInspectionClient.Request> sent = ArgumentCaptor.forClass(AiInspectionClient.Request.class);
		verify(ai).inspect(sent.capture());
		var request = sent.getValue();
		assertThat(request.propertyId()).isEqualTo("p1");
		assertThat(request.photos()).extracting(AiInspectionClient.PhotoRef::photoId).containsExactly("k1", "b1");
		assertThat(request.photos()).extracting(AiInspectionClient.PhotoRef::area).containsExactly("kitchen", "bathroom");
		assertThat(request.photos().get(0).storageKey()).matches("properties/p1/[0-9a-f-]{36}\\.jpg");
		assertThat(request.photos().get(1).storageKey()).endsWith(".png");
		assertThat(request.context())
				.containsEntry("listingType", "rent")
				.containsEntry("declaredChecklist", Map.of("dishwasher", "missing"));

		for (var ref : request.photos()) {
			assertThat(Files.exists(photoDir.resolve(ref.storageKey()))).isTrue();
		}
		assertThat(Files.readAllBytes(photoDir.resolve(request.photos().get(0).storageKey()))).isEqualTo(JPEG);
	}

	@Test
	void rejectsFilesThatAreNotImagesBeforeStoringAnything() throws Exception {
		mvc.perform(multipart("/api/v1/inspections")
						.file(photo(JPEG))
						.file(new MockMultipartFile("photos", "x.jpg", "image/jpeg", "%PDF-1.7".getBytes()))
						.param("propertyId", "p2")
						.param("photoIds", "a", "b")
						.param("areas", "kitchen", "kitchen"))
				.andExpect(status().isBadRequest());
		verify(ai, never()).inspect(any());
		assertThat(Files.exists(photoDir.resolve("properties/p2"))).isFalse();
	}

	@Test
	void validatesAreasIdsAndCounts() throws Exception {
		mvc.perform(multipart("/api/v1/inspections").file(photo(JPEG))
						.param("propertyId", "p1").param("photoIds", "a").param("areas", "garage"))
				.andExpect(status().isBadRequest());
		mvc.perform(multipart("/api/v1/inspections").file(photo(JPEG))
						.param("propertyId", "p1").param("photoIds", "a", "b").param("areas", "kitchen"))
				.andExpect(status().isBadRequest());
		mvc.perform(multipart("/api/v1/inspections").file(photo(JPEG))
						.param("propertyId", "../etc").param("photoIds", "a").param("areas", "kitchen"))
				.andExpect(status().isBadRequest());

		var nine = multipart("/api/v1/inspections").param("propertyId", "p1");
		for (int i = 0; i < 9; i++) {
			nine.file(photo(JPEG)).param("photoIds", "p" + i).param("areas", "kitchen");
		}
		mvc.perform(nine).andExpect(status().isBadRequest());
		verify(ai, never()).inspect(any());
	}

	@Test
	void reportsAiFailuresWithoutInventingFindings() throws Exception {
		when(ai.inspect(any()))
				.thenThrow(new AiInspectionClient.AiServiceException(502, "The AI model could not analyse these photographs."));
		mvc.perform(twoPhotos())
				.andExpect(status().isBadGateway())
				.andExpect(jsonPath("$.detail").value("The AI model could not analyse these photographs."))
				.andExpect(jsonPath("$.findings").doesNotExist());

		doThrow(new AiInspectionClient.AiServiceException(503, "busy")).when(ai).inspect(any());
		mvc.perform(twoPhotos()).andExpect(status().isServiceUnavailable());
	}

	@Test
	void allowsTheWebsiteOrigin() throws Exception {
		when(ai.inspect(any())).thenReturn(report());
		mvc.perform(twoPhotos().header("Origin", "http://localhost:3000"))
				.andExpect(status().isOk())
				.andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.header()
						.string("Access-Control-Allow-Origin", "http://localhost:3000"));
		mvc.perform(twoPhotos().header("Origin", "https://evil.example")).andExpect(status().isForbidden());
	}
}
