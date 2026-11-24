package com.propertyapp.reports;

import com.lowagie.text.Chunk;
import com.lowagie.text.Document;
import com.lowagie.text.DocumentException;
import com.lowagie.text.Element;
import com.lowagie.text.Font;
import com.lowagie.text.PageSize;
import com.lowagie.text.Paragraph;
import com.lowagie.text.Phrase;
import com.lowagie.text.pdf.BaseFont;
import com.lowagie.text.pdf.PdfPCell;
import com.lowagie.text.pdf.PdfPTable;
import com.lowagie.text.pdf.PdfWriter;
import com.propertyapp.building.BuildingAnalysis;
import com.propertyapp.documents.DocumentChecker;
import com.propertyapp.financial.FinancialCalculator;
import com.propertyapp.investment.InvestmentCalculator;
import com.propertyapp.location.LocationIntelligence;
import com.propertyapp.negotiation.NegotiationAssistant;
import com.propertyapp.renovation.RenovationPlanner;
import com.propertyapp.valuation.ValuationReport;
import java.awt.Color;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.text.NumberFormat;
import java.util.List;
import java.util.Locale;

/** Renders the report contents as an A4 PDF (OpenPDF, embedded Liberation Sans for Greek text). */
final class PdfReportWriter {

	private PdfReportWriter() {}

	private static final Color INK = new Color(0x0f, 0x1c, 0x2b);
	private static final Color MUTED = new Color(0x5b, 0x67, 0x75);
	private static final Color LINE = new Color(0xdd, 0xe2, 0xe8);
	private static final Color SOFT = new Color(0xf3, 0xf5, 0xf8);
	private static final NumberFormat EUR = NumberFormat.getIntegerInstance(Locale.UK);

	private record Fonts(Font title, Font h2, Font body, Font bold, Font small) {}

	static byte[] write(ReportService.Contents c) {
		var out = new ByteArrayOutputStream();
		Document doc = new Document(PageSize.A4, 48, 48, 48, 56);
		try {
			PdfWriter.getInstance(doc, out);
			doc.addTitle("Property evaluation: " + c.title());
			doc.addCreator("AI Property Evaluation Platform");
			doc.open();
			Fonts f = fonts();
			boolean rent = c.valuation().mode().equals("rent");

			doc.add(new Paragraph(c.title(), f.title()));
			doc.add(new Paragraph((c.address() == null ? "" : c.address() + " · ") + "Report of " + c.date(), f.small()));
			if (c.client() != null) {
				var cl = c.client();
				String by = cl.preparedBy() == null ? "" : " by " + cl.preparedBy() + (cl.company() == null ? "" : ", " + cl.company());
				doc.add(new Paragraph("Prepared for " + cl.clientName() + by + (cl.reference() == null ? "" : " · Ref. " + cl.reference()), f.bold()));
			}
			doc.add(spacer());

			summary(doc, f, c.valuation(), rent);
			valuation(doc, f, c.valuation(), rent);
			negotiation(doc, f, c.negotiation(), rent);
			if (c.negotiation().building() != null) building(doc, f, c.negotiation().building());
			if (c.negotiation().documents() != null) documents(doc, f, c.negotiation().documents());
			if (c.financial() != null) financial(doc, f, c.financial());
			if (c.investment() != null) investment(doc, f, c.investment());
			if (c.renovation() != null) renovation(doc, f, c.renovation());
			if (c.location() != null) location(doc, f, c.location());

			heading(doc, f, "Method and limitations");
			bullets(doc, f, c.valuation().methodology());
			doc.add(new Paragraph(c.valuation().disclaimer(), f.small()));
			doc.close();
		} catch (DocumentException | IOException e) {
			throw new IllegalStateException("Could not render the PDF report", e);
		}
		return out.toByteArray();
	}

	private static Fonts fonts() throws IOException {
		BaseFont regular = BaseFont.createFont("liberation/LiberationSans-Regular.ttf", BaseFont.IDENTITY_H, BaseFont.EMBEDDED);
		BaseFont bold = BaseFont.createFont("liberation/LiberationSans-Bold.ttf", BaseFont.IDENTITY_H, BaseFont.EMBEDDED);
		return new Fonts(new Font(bold, 18, Font.NORMAL, INK), new Font(bold, 12, Font.NORMAL, INK),
				new Font(regular, 9.5f, Font.NORMAL, INK), new Font(bold, 9.5f, Font.NORMAL, INK),
				new Font(regular, 8, Font.NORMAL, MUTED));
	}

	// Sections -----------------------------------------------------------------------------------

	private static void summary(Document doc, Fonts f, ValuationReport v, boolean rent) throws DocumentException {
		String suffix = rent ? "/month" : "";
		String value = v.estimate() == null ? "Not enough reliable data" : eur(v.estimate().low()) + "–" + eur(v.estimate().high()) + suffix;
		table(doc, f, new float[] {1, 1.4f}, List.of(
				List.of(rent ? "Asking rent" : "Asking price", eur(v.askingPrice()) + suffix),
				List.of(rent ? "Fair market rent" : "Estimated fair market value", value),
				List.of("Condition score", score(v.conditionScore())),
				List.of("Location score", score(v.locationScore())),
				List.of("Data quality", v.dataQuality().grade() + (v.dataQuality().sufficient() ? " (" + v.dataQuality().score() + "/100)" : ""))),
				false);
		if (!v.concerns().isEmpty()) {
			doc.add(new Paragraph("Potential concerns", f.bold()));
			bullets(doc, f, v.concerns().stream().limit(10).map(x -> x.title() + " (" + x.severity() + (x.observed() ? ", observed" : ", to check") + ")").toList());
		}
	}

	private static void valuation(Document doc, Fonts f, ValuationReport v, boolean rent) throws DocumentException {
		heading(doc, f, "Valuation");
		if (!v.dataQuality().sufficient()) {
			doc.add(new Paragraph("No valuation is shown because the evidence is not good enough:", f.body()));
			bullets(doc, f, v.dataQuality().reasons());
			bullets(doc, f, v.dataQuality().nextSteps());
		} else {
			var m = v.dataQuality().metrics();
			doc.add(new Paragraph(String.format(Locale.UK, "%d comparables used of %d found (%d completed, %d asking), median %.0f months old, spread ±%.1f%%.",
					m.used(), m.candidates(), m.transactions(), m.askingPrices(), m.medianAgeMonths(), m.dispersionPct()), f.body()));
			bullets(doc, f, v.dataQuality().warnings());
		}
		List<ValuationReport.ComparableResult> used = v.comparables().stream().filter(x -> x.excludedReason() == null).limit(10).toList();
		if (!used.isEmpty()) {
			List<List<String>> rows = new java.util.ArrayList<>();
			rows.add(List.of("Record", "Size", "Price", "€/m²", "Adjusted €/m²", "Weight"));
			for (var x : used) {
				rows.add(List.of(x.listingType() + " " + x.observedOn() + (x.distanceM() == null ? "" : String.format(Locale.UK, " · %.0f m", x.distanceM())),
						String.format(Locale.UK, "%.0f m²", x.sizeSqm()), eur(x.price()), perSqm(x.pricePerSqm(), rent),
						x.adjustedPricePerSqm() == null ? "—" : perSqm(x.adjustedPricePerSqm(), rent),
						x.weight() == null ? "—" : Math.round(x.weight() * 100) + "%"));
			}
			table(doc, f, new float[] {2.2f, 0.8f, 1, 0.9f, 1.1f, 0.7f}, rows, true);
		}
	}

	private static void negotiation(Document doc, Fonts f, NegotiationAssistant.Result n, boolean rent) throws DocumentException {
		heading(doc, f, "Suggested negotiation strategy");
		doc.add(new Paragraph(n.strategy(), f.body()));
		if (n.price() != null) {
			String s = rent ? "/month" : "";
			table(doc, f, new float[] {1, 1, 1}, List.of(List.of("Opening offer", "Target", "Maximum offer"),
					List.of(eur(n.price().openingOffer()) + s, eur(n.price().targetPrice()) + s, eur(n.price().maxOffer()) + s)), true);
			doc.add(new Paragraph("Evidence behind the maximum offer", f.bold()));
			bullets(doc, f, n.valuation().negotiation().evidence());
		} else {
			doc.add(new Paragraph(n.priceNote(), f.small()));
		}
		if (!n.arguments().isEmpty()) {
			doc.add(new Paragraph("Arguments", f.bold()));
			bullets(doc, f, n.arguments().stream().map(a -> a.point() + " [" + a.strength() + "] " + a.evidence()).toList());
		}
		if (!n.conditions().isEmpty()) {
			doc.add(new Paragraph("Conditions to put in the offer", f.bold()));
			bullets(doc, f, n.conditions());
		}
		if (!n.walkAwaySignals().isEmpty()) {
			doc.add(new Paragraph("Walk-away signals", f.bold()));
			bullets(doc, f, n.walkAwaySignals());
		}
	}

	private static void building(Document doc, Fonts f, BuildingAnalysis.Result b) throws DocumentException {
		heading(doc, f, "Building analysis (" + b.score() + "/100, " + b.completenessPct() + "% of items known)");
		List<List<String>> rows = new java.util.ArrayList<>();
		rows.add(List.of("Component", "Rating", "Issues and questions"));
		for (var c : b.components()) {
			rows.add(List.of(c.component(), c.rating(), String.join(" ", c.issues()) + (c.ask().isEmpty() ? "" : " Ask: " + String.join(" ", c.ask()))));
		}
		table(doc, f, new float[] {1.2f, 0.7f, 3.5f}, rows, true);
		doc.add(new Paragraph("Shared costs: " + b.sharedCosts().assessment(), f.body()));
	}

	private static void documents(Document doc, Fonts f, DocumentChecker.Result d) throws DocumentException {
		heading(doc, f, "Documents (" + d.readiness() + ")");
		if (d.findings().isEmpty()) doc.add(new Paragraph("No problems found in the documents provided.", f.body()));
		else bullets(doc, f, d.findings().stream().map(x -> x.severity().toUpperCase(Locale.ROOT) + ": " + x.message() + " " + x.recommendation()).toList());
	}

	private static void financial(Document doc, Fonts f, FinancialCalculator.Result r) throws DocumentException {
		heading(doc, f, "Purchase and ownership costs");
		List<List<String>> rows = new java.util.ArrayList<>();
		rows.add(List.of("Purchase cost", "Amount", "Basis"));
		r.purchaseCosts().forEach(l -> rows.add(List.of(l.label(), eur(l.amount()), l.basis())));
		rows.add(List.of("Total", eur(r.totalPurchaseCosts()), r.purchaseCostsPct() + "% of the price"));
		table(doc, f, new float[] {1.6f, 0.9f, 2.2f}, rows, true);
		if (r.loan() != null) {
			doc.add(new Paragraph(String.format(Locale.UK, "Mortgage %s over %d years at %.2f%%: %s a month (%s if rates rise 2 points).",
					eur(r.loan().amount()), r.loan().years(), r.loan().annualRatePct(), eur(r.loan().monthlyPayment()), eur(r.loan().stressMonthlyPayment())), f.body()));
		}
		doc.add(new Paragraph("Cash needed at purchase: " + eur(r.cashNeeded()) + ". Monthly cost of ownership: " + eur(r.monthlyTotal()) + ".", f.bold()));
		bullets(doc, f, r.notProvided());
	}

	private static void investment(Document doc, Fonts f, InvestmentCalculator.Result r) throws DocumentException {
		heading(doc, f, "Investment");
		table(doc, f, new float[] {1, 1, 1, 1}, List.of(
				List.of("Gross yield", "Net yield", "Cash flow (after tax)", "IRR"),
				List.of(r.grossYieldPct() + "%", r.netYieldPct() + "%", eur(r.monthlyCashFlowAfterTax()) + "/month", r.irrPct() == null ? "—" : r.irrPct() + "%")), true);
		bullets(doc, f, r.warnings());
		bullets(doc, f, r.assumptions());
	}

	private static void renovation(Document doc, Fonts f, RenovationPlanner.Result r) throws DocumentException {
		heading(doc, f, "Renovation scenarios");
		List<List<String>> rows = new java.util.ArrayList<>();
		rows.add(List.of("Scenario", "Cost", "Weeks", "Value after"));
		for (var s : r.scenarios()) {
			rows.add(List.of(s.label(), eur(s.costLow()) + "–" + eur(s.costHigh()), String.valueOf((int) s.weeks()),
					s.valueAfter() == null ? "—" : eur(s.valueAfter())));
		}
		table(doc, f, new float[] {1.5f, 1.4f, 0.6f, 1}, rows, true);
		bullets(doc, f, r.notes());
	}

	private static void location(Document doc, Fonts f, LocationIntelligence.Result l) throws DocumentException {
		heading(doc, f, "Location");
		for (var g : l.groups()) {
			if (g.nearest().isEmpty()) continue;
			doc.add(new Paragraph(g.group() + ": " + String.join(", ", g.nearest().stream()
					.map(p -> String.format(Locale.UK, "%s %.0f m (%d min)", p.name(), p.distanceM(), p.walkMinutes())).toList()), f.body()));
		}
		doc.add(new Paragraph("Environmental risks", f.bold()));
		bullets(doc, f, l.risks().stream().map(x -> x.riskType() + ": " + switch (x.status()) {
			case "zone" -> x.level() + " (" + x.layer() + ")";
			case "outside-zones" -> "outside mapped zones (" + x.layer() + ")";
			default -> "not assessed";
		}).toList());
		bullets(doc, f, l.notes());
	}

	// Helpers ------------------------------------------------------------------------------------

	private static void heading(Document doc, Fonts f, String text) throws DocumentException {
		Paragraph p = new Paragraph(text, f.h2());
		p.setSpacingBefore(14);
		p.setSpacingAfter(4);
		doc.add(p);
	}

	private static void bullets(Document doc, Fonts f, List<String> items) throws DocumentException {
		for (String item : items) {
			Paragraph p = new Paragraph();
			p.add(new Chunk("•  ", f.body()));
			p.add(new Phrase(item, f.body()));
			p.setIndentationLeft(10);
			p.setFirstLineIndent(-10);
			p.setSpacingAfter(2);
			doc.add(p);
		}
	}

	private static void table(Document doc, Fonts f, float[] widths, List<List<String>> rows, boolean header) throws DocumentException {
		PdfPTable t = new PdfPTable(widths);
		t.setWidthPercentage(100);
		t.setSpacingBefore(4);
		t.setSpacingAfter(6);
		for (int i = 0; i < rows.size(); i++) {
			boolean head = header && i == 0;
			for (int j = 0; j < rows.get(i).size(); j++) {
				PdfPCell cell = new PdfPCell(new Phrase(rows.get(i).get(j), head || (!header && j == 0) ? f.bold() : f.body()));
				cell.setBorderColor(LINE);
				cell.setPadding(4);
				if (head) cell.setBackgroundColor(SOFT);
				cell.setHorizontalAlignment(Element.ALIGN_LEFT);
				t.addCell(cell);
			}
		}
		doc.add(t);
	}

	private static Paragraph spacer() {
		Paragraph p = new Paragraph(" ");
		p.setSpacingAfter(2);
		return p;
	}

	private static String eur(double v) {
		return (v < 0 ? "−€" : "€") + EUR.format(Math.abs(Math.round(v)));
	}

	private static String perSqm(double v, boolean rent) {
		return rent ? String.format(Locale.UK, "€%.1f", v) : eur(v);
	}

	private static String score(ValuationReport.Score s) {
		return s.score() == null ? "Not available: " + s.basis() : s.score() + "/100";
	}
}
