/*
 * Copyright 2013-2024 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.springframework.cloud.gateway.server.mvc.handler;

import java.util.Locale;
import java.util.function.Function;

import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cloud.gateway.server.mvc.test.client.TestRestClient;
import org.springframework.context.annotation.Bean;
import org.springframework.http.MediaType;
import org.springframework.web.servlet.function.RouterFunction;
import org.springframework.web.servlet.function.ServerResponse;

import static org.springframework.cloud.gateway.server.mvc.handler.GatewayRouterFunctions.route;
import static org.springframework.cloud.gateway.server.mvc.handler.HandlerFunctions.fn;

@SpringBootTest(properties = {}, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
public class FunctionHandlerTests {

	@Autowired
	private TestRestClient restClient;

	@Test
	public void testSimpleFunctionWorks() {
		restClient.post()
			.uri("/simplefunction")
			.accept(MediaType.TEXT_PLAIN)
			.bodyValue("hello")
			.exchange()
			.expectStatus()
			.isOk()
			.expectBody(String.class)
			.isEqualTo("HELLO");
	}

	@Test
	public void testTemplatedFunctionWorks() {
		restClient.post()
			.uri("/templatedfunction/upper")
			.accept(MediaType.TEXT_PLAIN)
			.bodyValue("hello")
			.exchange()
			.expectStatus()
			.isOk()
			.expectBody(String.class)
			.isEqualTo("HELLO");
	}

	@SpringBootConfiguration
	@EnableAutoConfiguration
	protected static class TestConfiguration {

		@Bean
		Function<String, String> upper() {
			return s -> s.toUpperCase(Locale.ROOT);
		}

		@Bean
		public RouterFunction<ServerResponse> gatewayRouterFunctionsSimpleFunction() {
			// @formatter:off
			return route("testsimplefunction")
					.POST("/simplefunction", fn("upper"))
					.build()
				.and(route("testtemplatedfunction")
					.POST("/templatedfunction/{fnName}", fn("{fnName}"))
					.build());
			// @formatter:on
		}

	}

}
