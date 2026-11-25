package com.propertyapp.common;

import java.util.List;

/**
 * Standard notices returned with results that people could mistake for professional work. They
 * are part of the API response, so every client (website, PDF, API users) gets the same wording.
 */
public final class Notices {

	private Notices() {}

	/**
	 * @param kind machine-readable: automated-estimate, assistance-tool
	 * @param title short label to show next to the result
	 * @param text one paragraph
	 * @param notThis what the result is not, item by item
	 */
	public record Notice(String kind, String title, String text, List<String> notThis) {}

	public static final Notice AUTOMATED_ESTIMATE = new Notice("automated-estimate",
			"Automated estimate, not a professional appraisal",
			"This figure is calculated by software from comparable market records and the details you entered. "
					+ "No one has inspected the property. A professional appraisal is carried out by a certified valuer "
					+ "who visits the property, checks its legal and technical status and signs a report. Banks, courts and "
					+ "tax authorities ask for that kind of valuation, not for this estimate.",
			List.of("a valuation by a certified valuer", "a building survey", "a basis for a mortgage, court or tax filing",
					"financial or investment advice"));

	public static final Notice DOCUMENT_ASSISTANCE = new Notice("assistance-tool",
			"Assistance tool, not a legal certification",
			"The Document Checker compares the details you enter from each document against common requirements "
					+ "and against each other. It does not see or authenticate the documents and does not search the land "
					+ "registry or cadastre. It does not certify that a title is clear or that a property can be sold or let. "
					+ "A lawyer's title search and the notary's checks are still needed before you sign.",
			List.of("legal advice", "a title search or certificate of clear title", "verification that a document is genuine",
					"a replacement for the lawyer or notary"));
}
