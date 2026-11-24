package com.propertyapp.inspection;

import com.propertyapp.subscriptions.Access.Caller;
import com.propertyapp.subscriptions.Access.Metered;
import com.propertyapp.subscriptions.Access.RequiresFeature;
import com.propertyapp.subscriptions.Access.UpgradeRequired;
import com.propertyapp.subscriptions.PlanCatalog.Feature;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

/**
 * AI Home Inspector: the user uploads photographs, they are stored, the ai-service examines them
 * with a vision model and the structured findings are returned to the caller.
 *
 * <pre>
 * POST /api/v1/inspections   (multipart/form-data)
 *   propertyId               the property the photos belong to
 *   photos                   1-24 image files (JPEG, PNG or WebP, up to 5 MB each)
 *   photoIds, areas          one per photo, in the same order; area is kitchen, bathroom, ...
 *   listingType, propertyType, checklist.&lt;item&gt;   optional context for the model
 * </pre>
 */
@RestController
@RequestMapping("/api/v1/inspections")
public class InspectionController {

	static final Set<String> AREAS =
			Set.of("kitchen", "bathroom", "bedroom", "living-room", "exterior", "building-entrance", "other");
	static final Set<String> CHECKLIST_ITEMS = Set.of(
			"refrigerator", "washing-machine", "dishwasher", "oven-stove", "air-conditioning", "heating", "furniture");
	static final int MAX_PHOTOS = 24;
	static final int MAX_PER_AREA = 8;
	static final long MAX_BYTES = 5L * 1024 * 1024;
	private static final Pattern ID = Pattern.compile("[A-Za-z0-9_-]{1,64}");
	private static final Pattern WORD = Pattern.compile("[a-z-]{1,30}");

	private final PhotoStorage storage;
	private final AiInspectionClient ai;

	public InspectionController(PhotoStorage storage, AiInspectionClient ai) {
		this.storage = storage;
		this.ai = ai;
	}

	@RequiresFeature(Feature.BASIC_INSPECTION)
	@Metered
	@PostMapping(consumes = "multipart/form-data")
	public InspectionReport inspect(
			Caller caller,
			@RequestParam String propertyId,
			@RequestParam("photos") List<MultipartFile> photos,
			@RequestParam List<String> photoIds,
			@RequestParam List<String> areas,
			@RequestParam MultiValueMap<String, String> params) throws IOException {

		if (!ID.matcher(propertyId).matches()) throw badRequest("propertyId is invalid");
		if (photos.isEmpty() || photos.size() > MAX_PHOTOS) throw badRequest("Send between 1 and " + MAX_PHOTOS + " photos");
		int planMax = caller.plan().maxInspectionPhotos();
		if (photos.size() > planMax) {
			throw new UpgradeRequired("The " + caller.plan().name() + " plan inspects up to " + planMax + " photos per analysis. "
					+ "Upgrade to Premium for up to " + MAX_PHOTOS + ".", Feature.FULL_INSPECTION, caller.plan());
		}
		if (photoIds.size() != photos.size() || areas.size() != photos.size()) {
			throw badRequest("photoIds and areas must have one entry per photo");
		}
		if (photoIds.stream().distinct().count() != photoIds.size()) throw badRequest("photoIds must be unique");

		Map<String, Integer> perArea = new HashMap<>();
		for (int i = 0; i < photos.size(); i++) {
			if (!ID.matcher(photoIds.get(i)).matches()) throw badRequest("photoIds must be letters, digits, - or _");
			if (!AREAS.contains(areas.get(i))) throw badRequest("Unknown area: " + areas.get(i));
			if (perArea.merge(areas.get(i), 1, Integer::sum) > MAX_PER_AREA) {
				throw badRequest("At most " + MAX_PER_AREA + " photos per area");
			}
			if (photos.get(i).getSize() > MAX_BYTES) throw badRequest("Each photo must be 5 MB or smaller");
		}

		// Validate every file before storing any of them.
		List<byte[]> contents = new ArrayList<>();
		List<String> extensions = new ArrayList<>();
		for (MultipartFile photo : photos) {
			byte[] data = photo.getBytes();
			String ext = PhotoStorage.sniffExtension(data)
					.orElseThrow(() -> badRequest("Only JPEG, PNG or WebP images are accepted"));
			contents.add(data);
			extensions.add(ext);
		}

		List<AiInspectionClient.PhotoRef> refs = new ArrayList<>();
		for (int i = 0; i < photos.size(); i++) {
			String key = storage.save(propertyId, contents.get(i), extensions.get(i));
			refs.add(new AiInspectionClient.PhotoRef(photoIds.get(i), key, areas.get(i)));
		}

		return ai.inspect(new AiInspectionClient.Request(propertyId, refs, context(params)));
	}

	/** Optional context: listing type, property type and the declared appliance checklist. */
	static Map<String, Object> context(MultiValueMap<String, String> params) {
		Map<String, Object> context = new LinkedHashMap<>();
		String listingType = params.getFirst("listingType");
		if ("sale".equals(listingType) || "rent".equals(listingType)) context.put("listingType", listingType);
		String propertyType = params.getFirst("propertyType");
		if (propertyType != null && WORD.matcher(propertyType).matches()) context.put("propertyType", propertyType);
		Map<String, String> checklist = new LinkedHashMap<>();
		for (String item : CHECKLIST_ITEMS) {
			String status = params.getFirst("checklist." + item);
			if (status != null && WORD.matcher(status).matches()) checklist.put(item, status);
		}
		if (!checklist.isEmpty()) context.put("declaredChecklist", checklist);
		return context;
	}

	private static ResponseStatusException badRequest(String message) {
		return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
	}

	@ExceptionHandler(AiInspectionClient.AiServiceException.class)
	ResponseEntity<ProblemDetail> aiFailed(AiInspectionClient.AiServiceException e) {
		HttpStatus status = switch (e.upstreamStatus()) {
			case 422 -> HttpStatus.UNPROCESSABLE_CONTENT;
			case 503 -> HttpStatus.SERVICE_UNAVAILABLE;
			default -> HttpStatus.BAD_GATEWAY;
		};
		ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, e.getMessage());
		problem.setTitle("AI inspection failed");
		return ResponseEntity.status(status).body(problem);
	}
}
