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

package org.springframework.cloud.gateway.filter;

import java.net.URI;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

import org.awaitility.Awaitility;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
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
		properties = { "debug=false", "spring.cloud.function.definition=consumeHello",
				"spring.cloud.stream.bindings.consumeHello-in-0.destination=hello-out-0" })
@Testcontainers
public class StreamRoutingFilterTests extends BaseWebClientTests {

	@Container
	@ServiceConnection
	public static RabbitMQContainer rabbitmq = new RabbitMQContainer("rabbitmq:3.7.25-management-alpine");

	@Autowired
	private AtomicBoolean helloConsumed;

	@Test
	public void streamRoutingFilterWorks() {
		helloConsumed.set(false);

		URI uri = UriComponentsBuilder.fromUriString(this.baseUri + "/").build(true).toUri();

		testClient.post()
			.uri(uri)
			.bodyValue("hello")
			.header("Host", "www.streamroutingfilterjava.org")
			.accept(MediaType.TEXT_PLAIN)
			.exchange()
			.expectStatus()
			.isOk();

		Awaitility.await().atMost(5, TimeUnit.SECONDS).until(() -> helloConsumed.get());
		assertThat(helloConsumed).isTrue();
	}

	@EnableAutoConfiguration
	@SpringBootConfiguration
	@Import(DefaultTestConfig.class)
	public static class TestConfig {

		@Bean
		public RouteLocator testRouteLocator(RouteLocatorBuilder builder) {
			return builder.routes()
				.route("stream_routing_filter_java_test",
						r -> r.path("/").and().host("www.streamroutingfilterjava.org").uri("stream://hello-out-0"))
				.build();
		}

		@Bean
		public AtomicBoolean helloConsumed() {
			return new AtomicBoolean(false);
		}

		@Bean
		public Consumer<String> consumeHello(AtomicBoolean helloConsumed) {
			return message -> helloConsumed.compareAndSet(false, true);
		}

	}

}
