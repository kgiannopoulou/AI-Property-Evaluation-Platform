package com.propertyapp.documents;

import static org.assertj.core.api.Assertions.assertThat;

import com.propertyapp.documents.DocumentChecker.Declared;
import com.propertyapp.documents.DocumentChecker.Document;
import com.propertyapp.documents.DocumentChecker.Finding;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

class DocumentCheckerTest {

	static final LocalDate TODAY = LocalDate.of(2026, 11, 24);
	static final Declared DECLARED = new Declared(78.0, "D", 1972, "Υμηττού 10, Παγκράτι");

	static Document doc(String type, LocalDate issued) {
		return new Document(type, issued, null, "N-1", null, null, null, null, null, null, null, null);
	}

	static List<Document> complete() {
		return List.of(
				new Document("title-deed", LocalDate.of(2009, 5, 1), null, "12345", 78.0, null, List.of("Γιώργος Παπαδόπουλος"), "Υμηττού 10", null, null, null, null),
				new Document("cadastral-extract", TODAY.minusDays(5), null, "KAEK-1", 77.5, null, List.of("ΠΑΠΑΔΟΠΟΥΛΟΣ ΓΙΩΡΓΟΣ"), null, null, false, null, null),
				new Document("building-permit", LocalDate.of(1971, 3, 1), null, "P-1", null, null, null, null, null, null, null, null),
				new Document("engineer-certificate", TODAY.minusDays(20), null, "E-1", null, null, null, null, null, null, null, false),
				new Document("energy-certificate", LocalDate.of(2022, 1, 10), null, "PEA-1", null, "D", null, null, null, null, null, null),
				doc("enfia-certificate", TODAY.minusDays(10)),
				doc("municipal-certificate", TODAY.minusDays(10)),
				doc("encumbrance-certificate", TODAY.minusDays(3)),
				doc("floor-plan", LocalDate.of(1971, 3, 1)),
				doc("common-charges-certificate", TODAY.minusDays(3)));
	}

	static DocumentChecker.Result check(List<Document> docs, List<String> sellers) {
		return DocumentChecker.check(new DocumentChecker.Request("sale", null, DECLARED, sellers, docs), TODAY);
	}

	@Test
	void aCompleteConsistentSetIsReady() {
		var r = check(complete(), List.of("Παπαδόπουλος Γιώργος"));
		assertThat(r.findings()).isEmpty();
		assertThat(r.readiness()).isEqualTo("ready");
		assertThat(r.checklist()).hasSize(10).allMatch(c -> c.status().equals("ok"));
	}

	@Test
	void flagsMissingExpiredInconsistentAndQuestionable() {
		List<Document> docs = List.of(
				new Document("title-deed", LocalDate.of(2009, 5, 1), null, "1", 78.0, null, List.of("Γιώργος Παπαδόπουλος", "Μαρία Παπαδοπούλου"), null, null, null, null, null),
				new Document("cadastral-extract", TODAY.minusDays(5), null, "K", 70.0, null, List.of("Γιώργος Παπαδόπουλος"), null, null, true, "Mortgage to a bank, €80,000", null),
				new Document("building-permit", LocalDate.of(1980, 1, 1), null, "P", null, null, null, null, null, null, null, null),
				new Document("engineer-certificate", TODAY.minusMonths(3), null, "E", null, null, null, null, null, null, null, true),
				new Document("energy-certificate", LocalDate.of(2015, 1, 1), null, "", null, "C", null, null, null, null, null, null));
		var r = check(docs, List.of("Γιώργος Παπαδόπουλος"));

		assertThat(r.readiness()).isEqualTo("blocked");
		assertThat(r.findings().getFirst().severity()).isEqualTo("blocker");
		assertThat(r.findings()).extracting(Finding::kind).contains("missing", "expired", "inconsistent", "questionable");
		assertThat(r.findings()).extracting(Finding::message)
				.anyMatch(m -> m.startsWith("The owners on the Title deed"))                // two owners, one seller
				.anyMatch(m -> m.contains("78.0 m² but the cadastre extract 70.0 m²"))
				.anyMatch(m -> m.startsWith("Energy performance certificate (ΠΕΑ) expired on 2025-01-01"))
				.anyMatch(m -> m.startsWith("Engineer's certificate of legality") && m.contains("expired"))
				.anyMatch(m -> m.contains("states class C but the listing says D"))
				.anyMatch(m -> m.startsWith("The permit was issued in 1980"))
				.anyMatch(m -> m.startsWith("Encumbrances are recorded: Mortgage"))
				.anyMatch(m -> m.startsWith("Unauthorised constructions are reported and no legalisation"))
				.anyMatch(m -> m.startsWith("ENFIA property-tax certificate has not been provided"));
	}

	@Test
	void namesAndAddressesCompareWithoutAccentsCaseOrOrder() {
		assertThat(DocumentChecker.names(List.of("ΠΑΠΑΔΟΠΟΥΛΟΣ Γιώργος"))).isEqualTo(DocumentChecker.names(List.of("Γιωργος Παπαδοπουλος")));
		assertThat(DocumentChecker.sameAddress("Υμηττού 10, Παγκράτι", "ΥΜΗΤΤΟΥ 10")).isTrue();
	}

	@Test
	void aLeaseOnlyNeedsTheEnergyCertificate() {
		var r = DocumentChecker.check(new DocumentChecker.Request("rent", null, DECLARED, null, List.of()), TODAY);
		assertThat(r.checklist()).singleElement().extracting(DocumentChecker.ChecklistItem::type).isEqualTo("energy-certificate");
		assertThat(r.readiness()).isEqualTo("blocked");
	}
}
