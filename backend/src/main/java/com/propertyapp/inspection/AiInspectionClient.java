package com.propertyapp.inspection;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.json.JsonMapper;

/** Calls the ai-service's visual inspection endpoint. */
public class AiInspectionClient {

	/** A photo the ai-service should read from storage. */
	public record PhotoRef(String photoId, String storageKey, String area) {}

	public record Request(String propertyId, List<PhotoRef> photos, Map<String, Object> context) {}

	private final RestClient http;

	public AiInspectionClient(RestClient http) {
		this.http = http;
	}

	public InspectionReport inspect(Request request) {
		try {
			return http.post()
					.uri("/v1/inspections")
					.contentType(MediaType.APPLICATION_JSON)
					.body(request)
					.retrieve()
					.onStatus(HttpStatusCode::isError, (req, res) -> {
						int status = res.getStatusCode().value();
						throw new AiServiceException(status, serviceMessage(res.getBody().readAllBytes()).orElse(errorMessage(status)));
					})
					.body(InspectionReport.class);
		} catch (ResourceAccessException e) {
			throw new AiServiceException(503, "The AI inspection service is not reachable.");
		}
	}

	/**
	 * The ai-service's own explanation ({@code {"error": "..."}}), when it sent one. It is written by our
	 * service, never contains secrets, and tells the user why (e.g. the model declined, a photo was unreadable).
	 */
	public static Optional<String> serviceMessage(byte[] body) {
		try {
			String message = JsonMapper.shared().readTree(body).path("error").asString("");
			return message.isBlank() || message.length() > 300 ? Optional.empty() : Optional.of(message);
		} catch (RuntimeException e) {
			return Optional.empty();
		}
	}

	private static String errorMessage(int status) {
		return switch (status) {
			case 400 -> "The AI inspection service rejected the request.";
			case 401, 403 -> "The backend is not authorised to call the AI inspection service.";
			case 422 -> "None of the photographs could be analysed.";
			case 502 -> "The AI model could not analyse these photographs.";
			case 503 -> "The AI model is busy; try again shortly.";
			default -> "The AI inspection service failed (" + status + ").";
		};
	}

	/** An error from the ai-service, carrying the status the API should report. */
	public static class AiServiceException extends RuntimeException {
		private final int upstreamStatus;

		public AiServiceException(int upstreamStatus, String message) {
			super(message);
			this.upstreamStatus = upstreamStatus;
		}

		public int upstreamStatus() {
			return upstreamStatus;
		}
	}
}
