/*
 * Copyright 2013-2020 the original author or authors.
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

package org.springframework.cloud.gateway.filter;

import java.net.URI;
import java.util.Locale;
import java.util.function.Function;

import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cloud.gateway.route.RouteLocator;
import org.springframework.cloud.gateway.route.builder.RouteLocatorBuilder;
import org.springframework.cloud.gateway.test.BaseWebClientTests;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.web.util.UriComponentsBuilder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.boot.test.context.SpringBootTest.WebEnvironment.RANDOM_PORT;

/**
 * @author Spencer Gibb
 */
@SpringBootTest(webEnvironment = RANDOM_PORT,
		properties = { "debug=false", "spring.cloud.function.definition=upper", "spring.codec.max-in-memory-size=40" })
public class FunctionRoutingFilterTests extends BaseWebClientTests {

	@Test
	public void functionRoutingFilterWorks() {
		URI uri = UriComponentsBuilder.fromUriString(this.baseUri + "/").build(true).toUri();

		testClient.post()
			.uri(uri)
			.bodyValue("hello")
			.header("Host", "www.functionroutingfilterjava.org")
			.accept(MediaType.TEXT_PLAIN)
			.exchange()
			.expectBody(String.class)
			.consumeWith(res -> assertThat(res.getResponseBody()).isEqualTo("HELLO"));
	}

	@EnableAutoConfiguration
	@SpringBootConfiguration
	@Import(DefaultTestConfig.class)
	public static class TestConfig {

		@Bean
		Function<String, String> upper() {
			return s -> s.toUpperCase(Locale.ROOT);
		}

		@Bean
		public RouteLocator testRouteLocator(RouteLocatorBuilder builder) {
			return builder.routes()
				.route("function_routing_filter_java_test",
						r -> r.path("/").and().host("www.functionroutingfilterjava.org").uri("fn://upper"))
				.build();
		}

	}

}
