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
import org.springframework.cloud.gateway.server.mvc.test.client.TestRestClient;
import org.springframework.context.annotation.Bean;
import org.springframework.http.MediaType;
import org.springframework.web.servlet.function.RouterFunction;
import org.springframework.web.servlet.function.ServerResponse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.cloud.gateway.server.mvc.handler.GatewayRouterFunctions.route;
import static org.springframework.cloud.gateway.server.mvc.handler.HandlerFunctions.stream;

@SpringBootTest(
		properties = { "spring.cloud.function.definition=consumeHello",
				"spring.cloud.stream.bindings.consumeHello-in-0.destination=hello-out-0" },
		webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
public class StreamHandlerTests {

	@Container
	@ServiceConnection
	public static RabbitMQContainer rabbitmq = new RabbitMQContainer("rabbitmq:3.7.25-management-alpine");

	@Autowired
	private TestRestClient restClient;

	@Autowired
	private AtomicBoolean helloConsumed;

	@Test
	public void testSimpleStreamWorks() {
		helloConsumed.set(false);
		restClient.post()
			.uri("/simplestream")
			.accept(MediaType.TEXT_PLAIN)
			.bodyValue("hello")
			.exchange()
			.expectStatus()
			.isAccepted();

		Awaitility.await().atMost(5, TimeUnit.SECONDS).until(() -> helloConsumed.get());
		assertThat(helloConsumed).isTrue();
	}

	@Test
	public void testTemplatedStreamWorks() {
		helloConsumed.set(false);
		restClient.post()
			.uri("/templatedstream/hello")
			.accept(MediaType.TEXT_PLAIN)
			.bodyValue("hello")
			.exchange()
			.expectStatus()
			.isAccepted();

		Awaitility.await().atMost(5, TimeUnit.SECONDS).until(() -> helloConsumed.get());
		assertThat(helloConsumed).isTrue();
	}

	@SpringBootConfiguration
	@EnableAutoConfiguration
	protected static class TestConfiguration {

		@Bean
		public RouterFunction<ServerResponse> gatewayRouterFunctionsSimpleStream() {
			// @formatter:off
			return route("testsimplestream")
					.POST("/simplestream", stream("hello-out-0"))
					.build()
				.and(route("testtemplatedstream")
					.POST("/templatedstream/{name}", stream("{name}-out-0"))
					.build());
			// @formatter:on
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
