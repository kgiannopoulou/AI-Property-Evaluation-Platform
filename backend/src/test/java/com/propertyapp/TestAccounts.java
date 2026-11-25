package com.propertyapp;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import com.propertyapp.privacy.ConsentService;
import com.propertyapp.users.AccountService;
import com.propertyapp.users.AccountStore;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/** Registers a user on a plan and returns a MockMvc that sends their token with every request. */
public final class TestAccounts {

	private TestAccounts() {}

	public record Account(AccountService.Session session, String bearer) {}

	public static Account create(WebApplicationContext context, String plan) {
		AccountService accounts = context.getBean(AccountService.class);
		AccountStore store = context.getBean(AccountStore.class);
		var session = accounts.register("user-" + UUID.randomUUID() + "@example.test", "correct horse battery", "Test User", "buyer");
		// As the registration endpoint does; AI photo processing granted so upload tests can run.
		context.getBean(ConsentService.class).atRegistration(session.user().id(), false, true);
		if (!plan.equals("free")) {
			Instant now = Instant.now();
			store.saveSubscription(new AccountStore.Subscription(null, session.user().id(), plan, "active", "paid", "test", null,
					null, now, now.plus(30, ChronoUnit.DAYS), false, null, null));
		}
		return new Account(session, "Bearer " + session.token());
	}

	public static MockMvc mvc(WebApplicationContext context, String plan) {
		return mvc(context, create(context, plan));
	}

	public static MockMvc mvc(WebApplicationContext context, Account account) {
		return MockMvcBuilders.webAppContextSetup(context).defaultRequest(get("/").header("Authorization", account.bearer())).build();
	}
}
