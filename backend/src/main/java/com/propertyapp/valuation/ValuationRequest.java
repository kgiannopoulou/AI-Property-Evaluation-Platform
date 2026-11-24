package com.propertyapp.valuation;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PastOrPresent;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.util.List;

/**
 * What the valuation engine is asked to value, plus the evidence the caller already has.
 *
 * @param concerns issues found by the AI Home Inspector, the rule-based checks or the user
 * @param repairEstimates costed repairs; only {@code verified} ones (a written quote, a survey)
 *     are used to move the suggested maximum offer
 * @param additionalComparables records the user knows about (e.g. a sale in the same building);
 *     they are weighted lower than records from the market database unless verified
 */
public record ValuationRequest(
		@NotNull @Valid Subject subject,
		@Size(max = 60) List<@Valid Concern> concerns,
		@Size(max = 30) List<@Valid RepairEstimate> repairEstimates,
		@Size(max = 30) List<@Valid ComparableInput> additionalComparables) {

	public ValuationRequest {
		concerns = concerns == null ? List.of() : List.copyOf(concerns);
		repairEstimates = repairEstimates == null ? List.of() : List.copyOf(repairEstimates);
		additionalComparables = additionalComparables == null ? List.of() : List.copyOf(additionalComparables);
	}

	static final String PROPERTY_TYPES = "apartment|maisonette|detached-house|studio|penthouse";
	static final String CONDITIONS = "needs-renovation|fair|good|excellent";
	static final String ENERGY_CLASSES = "A\\+|A|B\\+|B|C|D|E|F|G";

	public record Subject(
			@NotNull @Pattern(regexp = "sale|rent") String listingType,
			@NotNull @Pattern(regexp = PROPERTY_TYPES) String propertyType,
			@DecimalMin("15") @DecimalMax("2000") double sizeSqm,
			@Min(0) @Max(20) int bedrooms,
			@Min(0) @Max(10) int bathrooms,
			@Min(-2) @Max(60) int floor,
			boolean elevator,
			boolean parking,
			@Min(1850) @Max(2100) int yearBuilt,
			@Min(1850) @Max(2100) Integer renovatedYear,
			@NotNull @Pattern(regexp = CONDITIONS) String condition,
			@NotNull @Pattern(regexp = ENERGY_CLASSES) String energyClass,
			@Positive double askingPrice,
			@Valid GeoPoint location,
			@Size(max = 120) String district) {}

	public record GeoPoint(
			@DecimalMin("-90") @DecimalMax("90") double lat,
			@DecimalMin("-180") @DecimalMax("180") double lon) {}

	/**
	 * @param severity info, low/minor, medium/moderate, high/major or critical
	 * @param observed seen in a photograph or on site; {@code false} for a potential concern
	 */
	public record Concern(
			@Size(max = 60) String area,
			@NotBlank @Size(max = 300) String title,
			@NotNull @Pattern(regexp = "info|low|minor|medium|moderate|high|major|critical") String severity,
			boolean observed,
			@Size(max = 40) String source) {}

	public record RepairEstimate(
			@NotBlank @Size(max = 200) String item,
			@Positive double low,
			@Positive double high,
			@Size(max = 200) String source,
			boolean verified) {}

	public record ComparableInput(
			@Size(max = 200) String address,
			@NotNull @Pattern(regexp = "sold|sale-listing|rented|rent-listing") String listingType,
			@NotNull @Pattern(regexp = PROPERTY_TYPES) String propertyType,
			@DecimalMin("10") @DecimalMax("5000") double sizeSqm,
			@Positive double price,
			@NotNull @PastOrPresent LocalDate observedOn,
			@Positive Double distanceM,
			@Min(0) @Max(20) Integer bedrooms,
			@Min(0) @Max(10) Integer bathrooms,
			@Min(-2) @Max(60) Integer floor,
			Boolean elevator,
			Boolean parking,
			@Min(1850) @Max(2100) Integer yearBuilt,
			@Min(1850) @Max(2100) Integer renovatedYear,
			@Pattern(regexp = CONDITIONS) String condition,
			@Pattern(regexp = ENERGY_CLASSES) String energyClass,
			boolean verified) {}
}
