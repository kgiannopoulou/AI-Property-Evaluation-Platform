package com.propertyapp.subscriptions;

import com.propertyapp.audit.Audit;
import com.propertyapp.common.Database;
import com.propertyapp.subscriptions.Access.Caller;
import com.propertyapp.subscriptions.Access.Entitlements;
import com.propertyapp.subscriptions.Access.QuotaExceeded;
import com.propertyapp.subscriptions.Access.Unauthenticated;
import com.propertyapp.subscriptions.Access.UpgradeRequired;
import com.propertyapp.users.AccountService;
import com.propertyapp.users.AccountStore;
import com.propertyapp.users.InMemoryAccountStore;
import com.propertyapp.users.JdbcAccountStore;
import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.MethodParameter;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.client.RestClient;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/** Wires accounts, plan enforcement and billing into the web layer. */
@Configuration
class AccessConfig implements WebMvcConfigurer {

	private static final Logger log = LoggerFactory.getLogger(AccessConfig.class);

	@Bean
	AccountStore accountStore(Database database) {
		if (database.jdbc().isPresent()) return new JdbcAccountStore(database.jdbc().get());
		log.warn("No DATABASE_URL: accounts, subscriptions and usage are kept in memory and lost on restart (development only).");
		return new InMemoryAccountStore();
	}

	@Bean
	AccountService accountService(AccountStore store, Clock clock) {
		return new AccountService(store, clock);
	}

	@Bean
	Entitlements entitlements(AccountStore store, Clock clock) {
		return new Entitlements(store, clock);
	}

	@Bean
	StripePayments stripePayments(
			@Value("${app.billing.stripe.secret-key:}") String secretKey,
			@Value("${app.billing.stripe.webhook-secret:}") String webhookSecret,
			@Value("${app.billing.stripe.price-premium:}") String premium,
			@Value("${app.billing.stripe.price-professional:}") String professional,
			Clock clock) {
		Map<String, String> prices = new LinkedHashMap<>();
		prices.put("premium", premium);
		prices.put("professional", professional);
		return new StripePayments(RestClient.builder(), new StripePayments.Settings(secretKey, webhookSecret, prices), clock);
	}

	@Bean
	BillingService billingService(AccountStore store, Entitlements entitlements, StripePayments stripe, Clock clock,
			@Value("${app.billing.demo-mode:false}") boolean demoMode,
			@Value("${app.cors.allowed-origins}") String[] origins) {
		return new BillingService(store, entitlements, stripe, new BillingService.Settings(demoMode, origins[0]), clock);
	}

	private final java.util.function.Supplier<AccessInterceptor> interceptor;

	AccessConfig(org.springframework.beans.factory.ObjectProvider<AccountService> accounts,
			org.springframework.beans.factory.ObjectProvider<Entitlements> entitlements) {
		this.interceptor = () -> new AccessInterceptor(accounts.getObject(), entitlements.getObject());
	}

	@Override
	public void addInterceptors(InterceptorRegistry registry) {
		registry.addInterceptor(interceptor.get()).addPathPatterns("/api/**");
	}

	@Override
	public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers) {
		resolvers.add(new HandlerMethodArgumentResolver() {
			@Override
			public boolean supportsParameter(MethodParameter parameter) {
				return parameter.getParameterType().equals(Caller.class);
			}

			@Override
			public Object resolveArgument(MethodParameter parameter, ModelAndViewContainer mav, NativeWebRequest request,
					WebDataBinderFactory binderFactory) {
				Object caller = request.getAttribute(AccessInterceptor.CALLER, RequestAttributes.SCOPE_REQUEST);
				if (caller == null) throw new Unauthenticated("Log in to use this feature.");
				return caller;
			}
		});
	}

	/** Problem responses for access, quota and billing errors (RFC 9457). */
	@RestControllerAdvice
	static class Errors {

		private final Audit audit;

		Errors(Audit audit) {
			this.audit = audit;
		}

		private void denied(HttpServletRequest request, String reason, Map<String, Object> details) {
			Map<String, Object> d = new LinkedHashMap<>(details);
			d.put("reason", reason);
			if (request.getAttribute(AccessInterceptor.CALLER) instanceof Caller caller) {
				audit.denied(caller, "access.denied", request.getRequestURI(), d);
			} else if (request.getHeader(AccessInterceptor.API_KEY_HEADER) != null) {
				// Unknown or revoked API keys: worth seeing when someone tries many.
				audit.anonymous("access.denied", "denied", request.getRequestURI(), d);
			}
		}

		@ExceptionHandler(Unauthenticated.class)
		ResponseEntity<ProblemDetail> unauthenticated(Unauthenticated e, HttpServletRequest request) {
			denied(request, "unauthenticated", Map.of());
			ProblemDetail p = problem(HttpStatus.UNAUTHORIZED, "Authentication required", e.getMessage(), "unauthenticated");
			return ResponseEntity.status(HttpStatus.UNAUTHORIZED).header("WWW-Authenticate", "Bearer").body(p);
		}

		@ExceptionHandler(UpgradeRequired.class)
		ResponseEntity<ProblemDetail> upgrade(UpgradeRequired e, HttpServletRequest request) {
			denied(request, "upgrade-required", Map.of("feature", e.feature().id()));
			ProblemDetail p = problem(HttpStatus.FORBIDDEN, "Upgrade required", e.getMessage(), "upgrade-required");
			p.setProperty("feature", e.feature().id());
			p.setProperty("currentPlan", e.current().id());
			p.setProperty("requiredPlan", PlanCatalog.cheapestWith(e.feature()).id());
			return ResponseEntity.status(HttpStatus.FORBIDDEN).body(p);
		}

		@ExceptionHandler(QuotaExceeded.class)
		ResponseEntity<ProblemDetail> quota(QuotaExceeded e, HttpServletRequest request) {
			denied(request, "quota-exceeded", Map.of("limit", e.limit()));
			ProblemDetail p = problem(HttpStatus.TOO_MANY_REQUESTS, "Monthly analyses used up", e.getMessage(), "quota-exceeded");
			p.setProperty("plan", e.plan().id());
			p.setProperty("limit", e.limit());
			p.setProperty("used", e.used());
			p.setProperty("resetsOn", e.resetsOn().toString());
			return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
					.header("X-Analyses-Limit", String.valueOf(e.limit()))
					.header("X-Analyses-Remaining", "0")
					.body(p);
		}

		@ExceptionHandler(AccountService.AccountException.class)
		ResponseEntity<ProblemDetail> account(AccountService.AccountException e) {
			HttpStatus status = HttpStatus.valueOf(e.status());
			return ResponseEntity.status(status).body(problem(status, "Account", e.getMessage(), "account"));
		}

		@ExceptionHandler(BillingService.BillingException.class)
		ResponseEntity<ProblemDetail> billing(BillingService.BillingException e) {
			HttpStatus status = HttpStatus.valueOf(e.status());
			return ResponseEntity.status(status).body(problem(status, "Billing", e.getMessage(), "billing"));
		}

		@ExceptionHandler(StripePayments.PaymentException.class)
		ResponseEntity<ProblemDetail> payment(StripePayments.PaymentException e) {
			return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(problem(HttpStatus.BAD_GATEWAY, "Payment provider", e.getMessage(), "payment-provider"));
		}

		private static ProblemDetail problem(HttpStatus status, String title, String detail, String code) {
			ProblemDetail p = ProblemDetail.forStatusAndDetail(status, detail);
			p.setTitle(title);
			p.setType(URI.create("https://property-evaluation.example/problems/" + code));
			p.setProperty("code", code);
			return p;
		}
	}
}
