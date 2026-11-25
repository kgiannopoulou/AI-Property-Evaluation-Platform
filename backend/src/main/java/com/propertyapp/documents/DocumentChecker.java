package com.propertyapp.documents;

import com.propertyapp.common.Notices;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.text.Normalizer;
import java.time.LocalDate;
import java.time.Period;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;

/**
 * Document Checker: flags missing, expired, inconsistent or questionable documents for a Greek
 * property sale or lease.
 *
 * <p>Works on the fields of each document (entered by the user or extracted upstream): which
 * documents a transaction needs, how long each stays valid, and whether size, energy class,
 * owners, address and dates agree across documents and with the declared details. It does not
 * judge authenticity; a notary and a lawyer still review the originals.
 */
public final class DocumentChecker {

	private DocumentChecker() {}

	static final String TYPES = "title-deed|cadastral-extract|building-permit|engineer-certificate|energy-certificate"
			+ "|enfia-certificate|municipal-certificate|encumbrance-certificate|floor-plan|common-charges-certificate"
			+ "|legalisation-declaration|lease|other";

	/** @param validity how long the document is accepted for a transaction; null = no fixed expiry */
	record Requirement(String type, String label, boolean sale, boolean rent, Period validity, String why) {}

	static final List<Requirement> REQUIREMENTS = List.of(
			new Requirement("title-deed", "Title deed (συμβόλαιο)", true, false, null, "Proves the seller's ownership and how it was acquired."),
			new Requirement("cadastral-extract", "Cadastre extract (απόσπασμα κτηματολογίου)", true, false, Period.ofMonths(1), "Registered owners, size and rights in the Hellenic Cadastre."),
			new Requirement("building-permit", "Building permit (οικοδομική άδεια)", true, false, null, "The building was built legally."),
			new Requirement("engineer-certificate", "Engineer's certificate of legality (βεβαίωση μηχανικού)", true, false, Period.ofMonths(2), "Confirms there are no unauthorised constructions or that they were regularised."),
			new Requirement("energy-certificate", "Energy performance certificate (ΠΕΑ)", true, true, Period.ofYears(10), "Required to sell or let; states the energy class."),
			new Requirement("enfia-certificate", "ENFIA property-tax certificate", true, false, null, "Property tax for the last five years has been paid."),
			new Requirement("municipal-certificate", "Municipal certificate (ΤΑΠ)", true, false, null, "No unpaid municipal property levy."),
			new Requirement("encumbrance-certificate", "Encumbrance certificate (πιστοποιητικό βαρών)", true, false, Period.ofMonths(1), "Mortgages, liens or claims on the property."),
			new Requirement("floor-plan", "Floor plan / topographic diagram", true, false, null, "Size and layout to compare with the deed and cadastre."),
			new Requirement("common-charges-certificate", "Common charges clearance from the building manager", true, false, Period.ofMonths(1), "No unpaid common charges pass to the buyer."));

	public record Request(
			@NotNull @Pattern(regexp = "sale|rent") String listingType,
			LocalDate transactionDate,
			@Valid @NotNull Declared declared,
			@Size(max = 10) List<@Size(max = 120) String> sellerNames,
			@NotNull @Size(max = 40) List<@Valid Document> documents) {}

	public record Declared(
			@DecimalMin("10") @DecimalMax("5000") Double sizeSqm,
			@Pattern(regexp = "A\\+|A|B\\+|B|C|D|E|F|G") String energyClass,
			@Min(1850) @Max(2100) Integer yearBuilt,
			@Size(max = 200) String address) {}

	/** Fields read from one document; any may be null when the document does not show it. */
	public record Document(
			@NotNull @Pattern(regexp = TYPES) String type,
			LocalDate issuedOn,
			LocalDate expiresOn,
			@Size(max = 60) String number,
			@DecimalMin("1") @DecimalMax("5000") Double sizeSqm,
			@Pattern(regexp = "A\\+|A|B\\+|B|C|D|E|F|G") String energyClass,
			@Size(max = 10) List<@Size(max = 120) String> owners,
			@Size(max = 200) String address,
			@Min(1850) @Max(2100) Integer yearBuilt,
			Boolean encumbrances,
			@Size(max = 300) String encumbranceDetails,
			Boolean unauthorisedConstructions) {}

	/** @param severity blocker, high, medium or info; kind missing, expired, inconsistent or questionable */
	public record Finding(String severity, String kind, String documentType, String message, String recommendation) {}

	public record ChecklistItem(String type, String label, String status, String why) {}

	/** @param readiness ready, issues or blocked */
	/** {@code notice}: an assistance tool, not a legal certification; returned with every result. */
	public record Result(String readiness, List<ChecklistItem> checklist, List<Finding> findings, List<String> notes,
			Notices.Notice notice) {}

	private static final Map<String, Integer> RANK = Map.of("blocker", 0, "high", 1, "medium", 2, "info", 3);

	public static Result check(Request r, LocalDate today) {
		boolean sale = r.listingType().equals("sale");
		LocalDate on = r.transactionDate() != null ? r.transactionDate() : today;
		List<Finding> findings = new ArrayList<>();
		List<ChecklistItem> checklist = new ArrayList<>();

		for (Requirement req : REQUIREMENTS) {
			if (!(sale ? req.sale() : req.rent())) continue;
			List<Document> docs = r.documents().stream().filter(d -> d.type().equals(req.type())).toList();
			if (docs.isEmpty()) {
				boolean permitCovered = req.type().equals("building-permit")
						&& r.documents().stream().anyMatch(d -> d.type().equals("legalisation-declaration"));
				if (permitCovered) {
					checklist.add(new ChecklistItem(req.type(), req.label(), "covered-by-legalisation", req.why()));
					continue;
				}
				boolean blocker = Set.of("title-deed", "engineer-certificate", "energy-certificate").contains(req.type());
				findings.add(new Finding(blocker ? "blocker" : "high", "missing", req.type(), req.label() + " has not been provided.",
						"Ask the " + (sale ? "seller or their lawyer" : "landlord") + " for it. " + req.why()));
				checklist.add(new ChecklistItem(req.type(), req.label(), "missing", req.why()));
				continue;
			}
			Document d = docs.stream().max(Comparator.comparing(x -> x.issuedOn() == null ? LocalDate.MIN : x.issuedOn())).orElseThrow();
			String status = "ok";
			LocalDate expiry = d.expiresOn() != null ? d.expiresOn()
					: req.validity() != null && d.issuedOn() != null ? d.issuedOn().plus(req.validity()) : null;
			if (d.issuedOn() != null && d.issuedOn().isAfter(today)) {
				findings.add(new Finding("high", "questionable", req.type(), req.label() + " is dated in the future (" + d.issuedOn() + ").",
						"Check the date on the original."));
				status = "check";
			}
			if (expiry != null && expiry.isBefore(on)) {
				findings.add(new Finding(req.type().equals("energy-certificate") ? "high" : "medium", "expired", req.type(),
						req.label() + " expired on " + expiry + (on.equals(today) ? "." : " (before the transaction date " + on + ")."),
						"A new one must be issued before signing."));
				status = "expired";
			} else if (req.validity() != null && d.issuedOn() == null && d.expiresOn() == null) {
				findings.add(new Finding("medium", "questionable", req.type(), req.label() + " has no issue date, so its validity cannot be checked.",
						"Ask for a dated copy; it is valid for " + describe(req.validity()) + "."));
				status = "check";
			}
			if (req.type().equals("energy-certificate") && (d.number() == null || d.number().isBlank())) {
				findings.add(new Finding("medium", "questionable", req.type(), "The energy certificate has no number.",
						"The certificate number is needed for the listing and contract; check it in the official register."));
			}
			checklist.add(new ChecklistItem(req.type(), req.label(), status, req.why()));
		}

		consistency(r, findings);
		questionable(r, findings, sale);

		findings.sort(Comparator.comparingInt(f -> RANK.get(f.severity())));
		String readiness = findings.stream().anyMatch(f -> f.severity().equals("blocker")) ? "blocked"
				: findings.stream().anyMatch(f -> !f.severity().equals("info")) ? "issues" : "ready";
		List<String> notes = List.of(
				"Checks use the fields provided for each document; they do not verify that a document is genuine.",
				"Validity periods are those commonly applied by notaries (e.g. engineer's certificate two months, energy certificate ten years); confirm with your notary.");
		return new Result(readiness, List.copyOf(checklist), List.copyOf(findings), notes, Notices.DOCUMENT_ASSISTANCE);
	}

	private static void consistency(Request r, List<Finding> findings) {
		List<Document> docs = r.documents();
		// Size: deed / cadastre / floor plan against each other and the declared size.
		Document deed = first(docs, "title-deed");
		Document cadastre = first(docs, "cadastral-extract");
		Document plan = first(docs, "floor-plan");
		compareSize(findings, deed, cadastre, "title deed", "cadastre extract", 0.02, "high");
		compareSize(findings, deed, plan, "title deed", "floor plan", 0.05, "medium");
		Double declared = r.declared().sizeSqm();
		Double official = cadastre != null && cadastre.sizeSqm() != null ? cadastre.sizeSqm() : deed != null ? deed.sizeSqm() : null;
		if (declared != null && official != null && Math.abs(declared - official) / official > 0.05) {
			findings.add(new Finding("medium", "inconsistent", cadastre != null ? "cadastral-extract" : "title-deed",
					String.format(Locale.UK, "The advertised size of %.0f m² differs from the registered %.1f m².", declared, official),
					"Price per m² should be based on the registered size; ask whether the advert includes balconies or common areas."));
		}

		// Energy class.
		Document energy = first(docs, "energy-certificate");
		if (energy != null && energy.energyClass() != null && r.declared().energyClass() != null
				&& !energy.energyClass().equals(r.declared().energyClass())) {
			findings.add(new Finding("medium", "inconsistent", "energy-certificate",
					"The certificate states class " + energy.energyClass() + " but the listing says " + r.declared().energyClass() + ".",
					"Use the certificate's class; the listing must show it."));
		}

		// Owners: deed, cadastre and the people selling must match.
		Set<String> sellers = names(r.sellerNames());
		for (Document d : List.of(Objects.requireNonNullElse(deed, EMPTY), Objects.requireNonNullElse(cadastre, EMPTY))) {
			if (d == EMPTY || d.owners() == null || d.owners().isEmpty()) continue;
			Set<String> owners = names(d.owners());
			if (!sellers.isEmpty() && !owners.equals(sellers)) {
				findings.add(new Finding("blocker", "inconsistent", d.type(),
						"The owners on the " + label(d.type()) + " (" + String.join(", ", d.owners()) + ") are not the people selling ("
								+ String.join(", ", r.sellerNames()) + ").",
						"All registered owners must sign, or a power of attorney or inheritance acceptance must be shown."));
			}
		}
		if (deed != null && cadastre != null && deed.owners() != null && cadastre.owners() != null
				&& !deed.owners().isEmpty() && !cadastre.owners().isEmpty() && !names(deed.owners()).equals(names(cadastre.owners()))) {
			findings.add(new Finding("high", "inconsistent", "cadastral-extract", "The owners in the cadastre differ from the title deed.",
					"The cadastre record may need correcting before the sale; ask the seller's lawyer."));
		}

		// Address.
		String declaredAddress = r.declared().address();
		for (Document d : docs) {
			if (d.address() == null || declaredAddress == null) continue;
			if (!sameAddress(d.address(), declaredAddress)) {
				findings.add(new Finding("medium", "inconsistent", d.type(), "The address on the " + label(d.type()) + " (" + d.address()
						+ ") does not match " + declaredAddress + ".", "Confirm that the document is for this property."));
			}
		}
	}

	private static void questionable(Request r, List<Finding> findings, boolean sale) {
		Integer year = r.declared().yearBuilt();
		Document permit = first(r.documents(), "building-permit");
		if (permit != null && permit.issuedOn() != null && year != null && permit.issuedOn().getYear() > year + 1) {
			findings.add(new Finding("high", "questionable", "building-permit", "The permit was issued in " + permit.issuedOn().getYear()
					+ ", after the stated construction year " + year + ".",
					"The building may have been built before the permit, or the permit covers later additions; ask the engineer."));
		}
		for (Document d : r.documents()) {
			if (Boolean.TRUE.equals(d.encumbrances())) {
				findings.add(new Finding(sale ? "high" : "medium", "questionable", d.type(), "Encumbrances are recorded"
						+ (d.encumbranceDetails() != null ? ": " + d.encumbranceDetails() : "") + ".",
						"Mortgages and liens must be lifted at or before signing; the contract should say so."));
			}
			if (Boolean.TRUE.equals(d.unauthorisedConstructions())) {
				boolean legalised = r.documents().stream().anyMatch(x -> x.type().equals("legalisation-declaration"));
				findings.add(new Finding(legalised ? "medium" : "blocker", "questionable", d.type(),
						"Unauthorised constructions are reported" + (legalised ? "; a legalisation declaration is provided." : " and no legalisation declaration is provided."),
						legalised ? "Check that the legalisation covers everything the engineer listed and that the fines are paid."
								: "The sale cannot be signed until they are legalised or removed."));
			}
		}
		Document engineer = first(r.documents(), "engineer-certificate");
		if (engineer != null && engineer.issuedOn() != null && permit != null && permit.issuedOn() != null
				&& engineer.issuedOn().isBefore(permit.issuedOn())) {
			findings.add(new Finding("medium", "questionable", "engineer-certificate", "The engineer's certificate predates the building permit.",
					"Ask for a current certificate."));
		}
	}

	private static final Document EMPTY = new Document("other", null, null, null, null, null, null, null, null, null, null, null);

	private static void compareSize(List<Finding> findings, Document a, Document b, String la, String lb, double tolerance, String severity) {
		if (a == null || b == null || a.sizeSqm() == null || b.sizeSqm() == null) return;
		double diff = Math.abs(a.sizeSqm() - b.sizeSqm()) / Math.max(a.sizeSqm(), b.sizeSqm());
		if (diff > tolerance) {
			findings.add(new Finding(severity, "inconsistent", b.type(), String.format(Locale.UK,
					"The %s gives %.1f m² but the %s %.1f m² (%.0f%% apart).", la, a.sizeSqm(), lb, b.sizeSqm(), diff * 100),
					"Ask an engineer to reconcile the sizes before signing; the cadastre record may need correcting."));
		}
	}

	private static Document first(List<Document> docs, String type) {
		return docs.stream().filter(d -> d.type().equals(type)).findFirst().orElse(null);
	}

	/** Names compared without accents, case or word order ("ΠΑΠΑΔΟΠΟΥΛΟΣ Γιώργος" = "Γιωργος Παπαδοπουλος"). */
	static Set<String> names(List<String> names) {
		Set<String> out = new LinkedHashSet<>();
		if (names == null) return out;
		Function<String, String> norm = s -> String.join(" ", java.util.Arrays.stream(fold(s).split("\\s+")).filter(w -> !w.isBlank()).sorted().toList());
		for (String n : names) if (n != null && !n.isBlank()) out.add(norm.apply(n));
		return out;
	}

	static boolean sameAddress(String a, String b) {
		String x = fold(a).replaceAll("[^\\p{L}\\p{N} ]", " ").replaceAll("\\s+", " ").trim();
		String y = fold(b).replaceAll("[^\\p{L}\\p{N} ]", " ").replaceAll("\\s+", " ").trim();
		return x.contains(y) || y.contains(x);
	}

	private static String fold(String s) {
		return Normalizer.normalize(s, Normalizer.Form.NFD).replaceAll("\\p{M}", "").toLowerCase(Locale.ROOT).replace('ς', 'σ');
	}

	private static String label(String type) {
		return REQUIREMENTS.stream().filter(r -> r.type().equals(type)).map(Requirement::label).findFirst().orElse(type.replace('-', ' '));
	}

	private static String describe(Period p) {
		if (p.getYears() > 0) return p.getYears() + " years";
		return p.getMonths() == 1 ? "one month" : p.getMonths() + " months";
	}
}
