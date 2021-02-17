/*
 * Copyright 2013-2021 the original author or authors.
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

package org.springframework.cloud.gateway.fn.webflux;

import java.util.Map;

import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.context.annotation.Bean;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.reactive.function.server.ServerResponse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.cloud.gateway.fn.webflux.FilterFunctions.addRequestHeader;
import static org.springframework.cloud.gateway.fn.webflux.HandlerFunctions.http;
import static org.springframework.web.reactive.function.server.RequestPredicates.GET;
import static org.springframework.web.reactive.function.server.RouterFunctions.route;

@SpringBootTest(properties = "spring.main.web-application-type=reactive", webEnvironment = WebEnvironment.RANDOM_PORT)
public class WebfluxIntegrationTests {

	@Autowired
	WebTestClient client;

	@Test
	@SuppressWarnings("unchecked")
	public void routerFunction() {
		client.get().uri("/hello").exchange().expectStatus().isOk().expectBody(String.class).isEqualTo("Hello");

		client.get().uri("/get").exchange().expectStatus().isOk().expectBody(Map.class).consumeWith(res -> {
			Map<String, Object> map = res.getResponseBody();
			assertThat(map).isNotEmpty().containsKey("headers");
			Map<String, Object> headers = (Map<String, Object>) map.get("headers");
			assertThat(headers).containsEntry("X-Foo", "Bar");
		});
	}

	protected static class TestHandler {

		public Mono<ServerResponse> hello(ServerRequest request) {
			return ServerResponse.ok().bodyValue("Hello");
		}

	}

	@SpringBootConfiguration
	@EnableAutoConfiguration
	protected static class TestConfiguration {

		// @LocalServerPort
		int port;

		@Bean
		TestHandler testHandler() {
			return new TestHandler();
		}

		@Bean
		public RouterFunction<ServerResponse> downstreamRouterFunctions(TestHandler testHandler) {
			return route(GET("/hello"), testHandler::hello);
		}

		@Bean
		public RouterFunction<ServerResponse> gatewayRouterFunctions() {
			// return route(GET("/hi"), http("http://localhost:" + port));
			return route(GET("/get"), http("https://httpbin.org")).filter(addRequestHeader("X-Foo", "Bar"));
		}

	}

}
