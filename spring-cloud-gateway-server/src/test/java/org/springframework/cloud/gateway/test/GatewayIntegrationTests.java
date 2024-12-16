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

package org.springframework.cloud.gateway.test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.hamcrest.BaseMatcher;
import org.hamcrest.Description;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import reactor.core.publisher.Mono;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.cloud.gateway.config.GatewayProperties;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.cloud.gateway.filter.WeightCalculatorWebFilter;
import org.springframework.cloud.gateway.filter.headers.ForwardedHeadersFilter;
import org.springframework.cloud.gateway.filter.headers.XForwardedHeadersFilter;
import org.springframework.cloud.gateway.handler.RoutePredicateHandlerMapping;
import org.springframework.cloud.gateway.route.CachingRouteLocator;
import org.springframework.cloud.gateway.route.RouteDefinition;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationListener;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.support.AbstractApplicationContext;
import org.springframework.core.annotation.AnnotationAwareOrderComparator;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.boot.test.context.SpringBootTest.WebEnvironment.RANDOM_PORT;
import static org.springframework.cloud.gateway.test.TestUtils.getMap;

@SpringBootTest(webEnvironment = RANDOM_PORT)
@DirtiesContext
@SuppressWarnings("unchecked")
@ExtendWith(OutputCaptureExtension.class)
class GatewayIntegrationTests extends BaseWebClientTests {

	@Autowired
	private GatewayProperties properties;

	@Autowired
	private ApplicationContext context;

	@Test
	void listenersInOrder() {
		assertThat(context).isInstanceOf(AbstractApplicationContext.class);
		AbstractApplicationContext ctxt = (AbstractApplicationContext) context;
		List<ApplicationListener<?>> applicationListeners = new ArrayList<>(ctxt.getApplicationListeners());
		AnnotationAwareOrderComparator.sort(applicationListeners);
		int weightFilterIndex = applicationListeners.indexOf(context.getBean(WeightCalculatorWebFilter.class));
		int routeLocatorIndex = applicationListeners.indexOf(context.getBean(CachingRouteLocator.class));
		assertThat(weightFilterIndex > routeLocatorIndex).as("CachingRouteLocator is after WeightCalculatorWebFilter")
			.isTrue();
	}

	@Test
	void checkFilterIsDisabled() {
		Optional<RouteDefinition> disabledRoute = properties.getRoutes()
			.stream()
			.filter(r -> "disabled_config_test".equals(r.getId()))
			.findFirst();
		assertThat(disabledRoute).as("Disabled route is present")
			.isPresent()
			.get()
			.hasFieldOrPropertyWithValue("enabled", false);
	}

	@Test
	void complexContentTypeWorks() {
		testClient.post()
			.uri("/headers")
			.contentType(MediaType.APPLICATION_JSON)
			.bodyValue("testdata")
			.header("Host", "www.complexcontenttype.org")
			.exchange()
			.expectStatus()
			.isOk()
			.expectBody(Map.class)
			.consumeWith(result -> {
				Map<String, Object> headers = getMap(result.getResponseBody(), "headers");
				assertThat(headers).containsEntry(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE);
			});
	}

	@Test
	void forwardedHeadersWork() {
		testClient.get().uri("/headers").exchange().expectStatus().isOk().expectBody(Map.class).consumeWith(result -> {
			Map<String, Object> headers = getMap(result.getResponseBody(), "headers");
			assertThat(headers).containsKeys(ForwardedHeadersFilter.FORWARDED_HEADER,
					XForwardedHeadersFilter.X_FORWARDED_FOR_HEADER, XForwardedHeadersFilter.X_FORWARDED_HOST_HEADER,
					XForwardedHeadersFilter.X_FORWARDED_PORT_HEADER, XForwardedHeadersFilter.X_FORWARDED_PROTO_HEADER);
			assertThat(headers.get(ForwardedHeadersFilter.FORWARDED_HEADER)).asString()
				.contains("proto=http")
				.contains("host=\"localhost:")
				.contains("for=\"127.0.0.1:");
			assertThat(headers.get(XForwardedHeadersFilter.X_FORWARDED_HOST_HEADER)).asString()
				.isEqualTo("localhost:" + this.port);
			assertThat(headers.get(XForwardedHeadersFilter.X_FORWARDED_PORT_HEADER)).asString()
				.isEqualTo("" + this.port);
			assertThat(headers.get(XForwardedHeadersFilter.X_FORWARDED_PROTO_HEADER)).asString().isEqualTo("http");
		});
	}

	@Test
	void compositeRouteWorks() {
		testClient.get()
			.uri("/headers?foo=bar&baz")
			.header("Host", "www.foo.org")
			.header("X-Request-Id", "123")
			.cookie("chocolate", "chip")
			.exchange()
			.expectStatus()
			.isOk()
			.expectHeader()
			.valueEquals(HANDLER_MAPPER_HEADER, RoutePredicateHandlerMapping.class.getSimpleName())
			.expectHeader()
			.valueEquals(ROUTE_ID_HEADER, "host_foo_path_headers_to_httpbin")
			.expectHeader()
			.valueEquals("X-Response-Foo", "Bar");
	}

	@Test
	void defaultFiltersWorks() {
		assertThat(this.properties.getDefaultFilters()).isNotEmpty();

		testClient.get()
			.uri("/headers")
			.header("Host", "www.addresponseheader.org")
			.exchange()
			.expectStatus()
			.isOk()
			.expectHeader()
			.valueEquals("X-Response-Default-Foo", "Default-Bar")
			.returnResult(Object.class)
			.consumeWith(result -> {
				HttpHeaders httpHeaders = result.getResponseHeaders();
				assertThat(httpHeaders.get("X-Response-Default-Foo")).hasSize(1);
			});
	}

	@Test
	void loadBalancerFilterWorks() {
		testClient.get()
			.uri("/get")
			.header("Host", "www.loadbalancerclient.org")
			.exchange()
			.expectStatus()
			.isOk()
			.expectHeader()
			.valueEquals(ROUTE_ID_HEADER, "load_balancer_client_test");
	}

	@Test
	void loadBalancerFilterNoClientWorks() {
		testClient.get()
			.uri("/get")
			.header("Host", "www.loadbalancerclientempty.org")
			.exchange()
			.expectStatus()
			.value(new BaseMatcher<Integer>() {
				@Override
				public boolean matches(Object item) {
					if (Integer.class.isInstance(item)) {
						Integer toMatch = (Integer) item;
						return toMatch.intValue() == 503;
					}
					return false;
				}

				@Override
				public void describeTo(Description description) {
					description.appendText("Expected 503");
				}
			});
	}

	@Test
	// gh-374 no content type/empty body causes NPR in NettyRoutingFilter
	void noContentType() {
		testClient.get().uri("/nocontenttype").exchange().expectStatus().is2xxSuccessful();
	}

	@Test
	void logsArtifactDeprecatedWarning(CapturedOutput output) {
		assertThat(output).contains("spring-cloud-gateway-server is deprecated");
	}

	@EnableAutoConfiguration
	@SpringBootConfiguration
	@Import(DefaultTestConfig.class)
	@RestController
	static class TestConfig {

		private static final Log log = LogFactory.getLog(TestConfig.class);

		private static Mono<Void> postFilterWork(ServerWebExchange exchange) {
			log.info("postFilterWork");
			exchange.getResponse().getHeaders().add("X-Post-Header", "AddedAfterRoute");
			return Mono.empty();
		}

		@GetMapping("/httpbin/nocontenttype")
		ResponseEntity<Void> nocontenttype() {
			return ResponseEntity.status(204).build();
		}

		@Bean
		@Order(-1)
		GlobalFilter postFilter() {
			return (exchange, chain) -> {
				log.info("postFilter start");
				return chain.filter(exchange).then(postFilterWork(exchange));
			};
		}

	}

}
