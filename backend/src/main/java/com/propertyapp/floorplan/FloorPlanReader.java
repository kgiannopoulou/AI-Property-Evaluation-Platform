package com.propertyapp.floorplan;

import com.propertyapp.inspection.AiInspectionClient;
import com.propertyapp.inspection.AiInspectionClient.AiServiceException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

/**
 * Floor Plan Reader. Reading a drawing is where an AI model adds real value: the ai-service's
 * vision model transcribes rooms and PRINTED measurements (it never measures pixels). This class
 * then checks the transcription deterministically against what the user declared: total room area
 * against the property size, and bedroom and bathroom counts.
 */
public class FloorPlanReader {

	private final RestClient http;

	public FloorPlanReader(RestClient http) {
		this.http = http;
	}

	/** The ai-service's answer (ai-service/src/floor-plan). */
	public record Reading(
			String planId, String model, String servedBy, boolean isFloorPlan, String legibility, String units,
			String scalePrinted, List<Room> rooms, Double totalAreaPrinted, String totalAreaText, List<String> notes,
			String disclaimer) {}

	public record Room(
			String label, String roomType, Double areaSqm, String areaSource, Double widthM, Double lengthM,
			String printedText, double confidence, List<String> warnings) {}

	public record Declared(Double sizeSqm, Integer bedrooms, Integer bathrooms) {}

	/** @param status ok, check or mismatch */
	public record Check(String check, String status, String detail) {}

	public record Result(Reading reading, double roomsAreaSqm, double outdoorAreaSqm, int roomsWithArea, int roomsWithoutArea,
			List<Check> checks) {}

	public Reading read(String planId, String storageKey) {
		try {
			return http.post()
					.uri("/v1/floor-plans")
					.contentType(MediaType.APPLICATION_JSON)
					.body(Map.of("planId", planId, "storageKey", storageKey))
					.retrieve()
					.onStatus(HttpStatusCode::isError, (req, res) -> {
						int status = res.getStatusCode().value();
						throw new AiServiceException(status, AiInspectionClient.serviceMessage(res.getBody().readAllBytes())
								.orElse("The floor plan reader failed (" + status + ")."));
					})
					.body(Reading.class);
		} catch (ResourceAccessException e) {
			throw new AiServiceException(503, "The AI service is not reachable.");
		}
	}

	static final Set<String> OUTDOOR = Set.of("balcony", "terrace");
	static final Set<String> BATHS = Set.of("bathroom", "wc");

	/** Deterministic checks of a reading against the declared details. */
	public static Result check(Reading r, Declared declared) {
		List<Check> checks = new ArrayList<>();
		double indoor = 0;
		double outdoor = 0;
		int withArea = 0;
		int without = 0;
		for (Room room : r.rooms()) {
			if (room.areaSqm() == null) {
				without++;
				continue;
			}
			withArea++;
			if (OUTDOOR.contains(room.roomType())) outdoor += room.areaSqm();
			else indoor += room.areaSqm();
		}
		indoor = Math.round(indoor * 100) / 100.0;
		outdoor = Math.round(outdoor * 100) / 100.0;

		if (!r.isFloorPlan()) {
			checks.add(new Check("Floor plan", "mismatch", "The image does not look like a floor plan."));
			return new Result(r, 0, 0, 0, 0, checks);
		}
		if (r.legibility().equals("illegible")) checks.add(new Check("Legibility", "check", "The plan is illegible; upload a sharper scan."));
		if (without > 0) {
			checks.add(new Check("Room areas", "check", without + " room(s) have no printed area or dimensions; their area is unknown, not zero."));
		}

		Double size = declared == null ? null : declared.sizeSqm();
		if (size != null && withArea > 0) {
			// Net room areas are usually 80-95 % of the gross size (walls, shafts, shared parts).
			double ratio = indoor / size;
			String detail = String.format(Locale.UK, "Rooms add up to %.1f m², %.0f%% of the declared %.0f m²", indoor, ratio * 100, size);
			if (ratio > 1.02) checks.add(new Check("Total area", "mismatch", detail + ": more than the property size. Check the plan is for this property."));
			else if (ratio < 0.7 && without == 0) checks.add(new Check("Total area", "mismatch", detail + ": the declared size may include balconies, shared areas or a part not on this plan."));
			else checks.add(new Check("Total area", "ok", detail + " (net rooms are typically 80-95% of the gross size)."));
		}
		if (size != null && r.totalAreaPrinted() != null && Math.abs(r.totalAreaPrinted() - size) / size > 0.05) {
			checks.add(new Check("Printed total", "mismatch", String.format(Locale.UK,
					"The plan states %.1f m² but %.0f m² was declared.", r.totalAreaPrinted(), size)));
		}
		if (declared != null && declared.bedrooms() != null) {
			long bedrooms = r.rooms().stream().filter(x -> x.roomType().equals("bedroom")).count();
			checks.add(new Check("Bedrooms", bedrooms == declared.bedrooms() ? "ok" : "mismatch",
					bedrooms + " on the plan, " + declared.bedrooms() + " declared."));
		}
		if (declared != null && declared.bathrooms() != null) {
			long baths = r.rooms().stream().filter(x -> BATHS.contains(x.roomType())).count();
			checks.add(new Check("Bathrooms and WCs", baths >= declared.bathrooms() ? "ok" : "mismatch",
					baths + " on the plan, " + declared.bathrooms() + " bathroom(s) declared."));
		}
		return new Result(r, indoor, outdoor, withArea, without, List.copyOf(checks));
	}
}
