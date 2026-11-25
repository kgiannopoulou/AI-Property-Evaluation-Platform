package com.propertyapp.subscriptions;

import com.propertyapp.subscriptions.Access.Caller;
import com.propertyapp.subscriptions.Access.Entitlements;
import com.propertyapp.subscriptions.Access.Metered;
import com.propertyapp.subscriptions.Access.RequiresFeature;
import com.propertyapp.subscriptions.Access.Unauthenticated;
import com.propertyapp.subscriptions.PlanCatalog.Feature;
import com.propertyapp.users.AccountService;
import com.propertyapp.users.AccountStore;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.web.cors.CorsUtils;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * Enforces accounts, plans and usage limits on every API call, in the backend: the website only
 * mirrors these rules. Order: authenticate (session token or API key), check the endpoint's feature,
 * then count the analysis. A count is released again when the request fails, so errors cost nothing.
 */
public class AccessInterceptor implements HandlerInterceptor {

	public static final String CALLER = AccessInterceptor.class.getName() + ".caller";
	static final String EVENTS = AccessInterceptor.class.getName() + ".events";
	public static final String PROPERTY_REF_HEADER = "X-Property-Ref";
	public static final String API_KEY_HEADER = "X-API-Key";
	static final Pattern REF = Pattern.compile("[A-Za-z0-9_.:-]{1,100}");

	static final Set<String> PUBLIC = Set.of("/api/v1/status", "/api/v1/plans", "/api/v1/auth/register", "/api/v1/auth/login",
			"/api/v1/billing/webhook", "/api/v1/privacy/retention");

	private final AccountService accounts;
	private final Entitlements entitlements;

	public AccessInterceptor(AccountService accounts, Entitlements entitlements) {
		this.accounts = accounts;
		this.entitlements = entitlements;
	}

	@Override
	public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
		if (CorsUtils.isPreFlightRequest(request) || PUBLIC.contains(request.getRequestURI())) return true;

		String apiKey = request.getHeader(API_KEY_HEADER);
		Optional<AccountStore.User> user;
		boolean viaKey = apiKey != null;
		if (viaKey) {
			user = accounts.userForApiKey(apiKey);
			if (user.isEmpty()) throw new Unauthenticated("The API key is invalid or revoked.");
		} else {
			String header = request.getHeader("Authorization");
			String token = header != null && header.startsWith("Bearer ") ? header.substring(7).trim() : null;
			if (token == null) throw new Unauthenticated("Log in to use this feature.");
			user = accounts.userForToken(token);
			if (user.isEmpty()) throw new Unauthenticated("Your session has expired. Log in again.");
		}
		Caller caller = entitlements.resolve(user.get(), viaKey);
		if (viaKey) caller.require(Feature.API_ACCESS);
		request.setAttribute(CALLER, caller);

		if (!(handler instanceof HandlerMethod method)) return true;
		RequiresFeature feature = method.getMethodAnnotation(RequiresFeature.class);
		if (feature != null) caller.require(feature.value());

		if (method.hasMethodAnnotation(Metered.class)) {
			String ref = request.getHeader(PROPERTY_REF_HEADER);
			// Without a property reference every call is a new analysis.
			String propertyRef = ref != null && REF.matcher(ref).matches() ? "p:" + ref : "r:" + UUID.randomUUID();
			var usage = entitlements.consume(caller, List.of(propertyRef), request.getRequestURI());
			request.setAttribute(EVENTS, usage.eventIds());
			usageHeaders(response, caller.analysesLimit(), usage.used());
		}
		return true;
	}

	@Override
	public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) {
		@SuppressWarnings("unchecked")
		List<UUID> events = (List<UUID>) request.getAttribute(EVENTS);
		if (events != null && (ex != null || response.getStatus() >= 400)) entitlements.release(events);
	}

	public static void usageHeaders(HttpServletResponse response, int limit, int used) {
		response.setHeader("X-Analyses-Limit", String.valueOf(limit));
		response.setHeader("X-Analyses-Used", String.valueOf(used));
		response.setHeader("X-Analyses-Remaining", String.valueOf(Math.max(0, limit - used)));
	}
}
