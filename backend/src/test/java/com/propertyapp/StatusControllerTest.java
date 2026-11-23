package com.propertyapp;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
class StatusControllerTest {

	@Autowired
	MockMvc mvc;

	@Test
	void statusListsModules() throws Exception {
		mvc.perform(get("/api/v1/status"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.service").value("property-backend"))
				.andExpect(jsonPath("$.modules.length()").value(9));
	}

	@Test
	void healthIsUp() throws Exception {
		mvc.perform(get("/actuator/health")).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("UP"));
	}
}
