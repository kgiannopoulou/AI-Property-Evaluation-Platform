package com.propertyapp.floorplan;

import com.propertyapp.privacy.ConsentService;
import com.propertyapp.subscriptions.Access.Caller;
import com.propertyapp.subscriptions.Access.Metered;
import com.propertyapp.subscriptions.Access.RequiresFeature;
import com.propertyapp.subscriptions.PlanCatalog.Feature;
import com.propertyapp.inspection.AiInspectionClient.AiServiceException;
import com.propertyapp.inspection.InspectionProperties;
import com.propertyapp.inspection.PhotoStorage;
import java.io.IOException;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.regex.Pattern;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestClient;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

/**
 * Floor Plan Reader.
 *
 * <pre>
 * POST /api/v1/floor-plans   (multipart/form-data)
 *   propertyId               the property the plan belongs to
 *   plan                     one image (JPEG, PNG or WebP, up to 5 MB)
 *   sizeSqm, bedrooms, bathrooms   optional declared values to check the reading against
 * </pre>
 */
@RestController
class FloorPlanController {

	private static final Pattern ID = Pattern.compile("[A-Za-z0-9_-]{1,64}");
	static final long MAX_BYTES = 5L * 1024 * 1024;

	private final PhotoStorage storage;
	private final FloorPlanReader reader;

	private final ConsentService consents;

	FloorPlanController(PhotoStorage storage, FloorPlanReader reader, ConsentService consents) {
		this.storage = storage;
		this.reader = reader;
		this.consents = consents;
	}

	@RequiresFeature(Feature.FLOOR_PLANS)
	@Metered
	@PostMapping(value = "/api/v1/floor-plans", consumes = "multipart/form-data")
	FloorPlanReader.Result read(
			Caller caller,
			@RequestParam String propertyId,
			@RequestParam("plan") MultipartFile plan,
			@RequestParam(required = false) Double sizeSqm,
			@RequestParam(required = false) Integer bedrooms,
			@RequestParam(required = false) Integer bathrooms) throws IOException {
		consents.require(caller.user().id(), ConsentService.Purpose.AI_PHOTO_PROCESSING);
		if (!ID.matcher(propertyId).matches()) throw badRequest("propertyId is invalid");
		if (plan.isEmpty() || plan.getSize() > MAX_BYTES) throw badRequest("The plan must be an image of 5 MB or less");
		if (sizeSqm != null && (sizeSqm < 15 || sizeSqm > 2000)) throw badRequest("sizeSqm must be between 15 and 2000");
		if (bedrooms != null && (bedrooms < 0 || bedrooms > 20)) throw badRequest("bedrooms must be between 0 and 20");
		if (bathrooms != null && (bathrooms < 0 || bathrooms > 10)) throw badRequest("bathrooms must be between 0 and 10");
		byte[] data = plan.getBytes();
		String ext = PhotoStorage.sniffExtension(data).orElseThrow(() -> badRequest("Only JPEG, PNG or WebP images are accepted"));
		String key = storage.save(caller, propertyId, data, ext, "floor-plan");
		FloorPlanReader.Reading reading = reader.read(propertyId, key);
		return FloorPlanReader.check(reading, new FloorPlanReader.Declared(sizeSqm, bedrooms, bathrooms));
	}

	private static ResponseStatusException badRequest(String message) {
		return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
	}

	@ExceptionHandler(AiServiceException.class)
	ResponseEntity<ProblemDetail> aiFailed(AiServiceException e) {
		HttpStatus status = switch (e.upstreamStatus()) {
			case 400, 422 -> HttpStatus.UNPROCESSABLE_CONTENT;
			case 503 -> HttpStatus.SERVICE_UNAVAILABLE;
			default -> HttpStatus.BAD_GATEWAY;
		};
		ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, e.getMessage());
		problem.setTitle("Floor plan reading failed");
		return ResponseEntity.status(status).body(problem);
	}

	@Configuration
	static class Config {
		@Bean
		FloorPlanReader floorPlanReader(InspectionProperties properties) {
			HttpClient httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
			JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(httpClient);
			factory.setReadTimeout(properties.aiTimeout());
			RestClient.Builder builder = RestClient.builder().baseUrl(properties.aiServiceUrl()).requestFactory(factory);
			if (properties.aiServiceToken() != null && !properties.aiServiceToken().isBlank()) {
				builder.defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + properties.aiServiceToken());
			}
			return new FloorPlanReader(builder.build());
		}
	}
}
