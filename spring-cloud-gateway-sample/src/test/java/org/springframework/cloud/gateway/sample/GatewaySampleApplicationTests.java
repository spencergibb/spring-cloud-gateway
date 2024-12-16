/*
 * Copyright 2013-2019 the original author or authors.
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

package org.springframework.cloud.gateway.sample;

import java.io.IOException;
import java.time.Duration;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.cloud.client.DefaultServiceInstance;
import org.springframework.cloud.gateway.config.GatewayMetricsProperties;
import org.springframework.cloud.gateway.test.HttpBinCompatibleController;
import org.springframework.cloud.loadbalancer.annotation.LoadBalancerClient;
import org.springframework.cloud.loadbalancer.core.ServiceInstanceListSupplier;
import org.springframework.cloud.loadbalancer.support.ServiceInstanceListSuppliers;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.Environment;
import org.springframework.test.util.TestSocketUtils;
import org.springframework.test.web.reactive.server.WebTestClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.boot.test.context.SpringBootTest.WebEnvironment.RANDOM_PORT;

/**
 * @author Spencer Gibb
 */
@SpringBootTest(classes = { GatewaySampleApplicationTests.TestConfig.class }, webEnvironment = RANDOM_PORT,
		properties = { "management.endpoint.gateway.enabled=true", "management.server.port=${test.port}" })
@ExtendWith(OutputCaptureExtension.class)
public class GatewaySampleApplicationTests {

	protected static int managementPort;

	@Autowired
	GatewayMetricsProperties metricsProperties;

	@LocalServerPort
	protected int port = 0;

	protected WebTestClient webClient;

	protected String baseUri;

	@BeforeAll
	public static void beforeClass() {
		managementPort = TestSocketUtils.findAvailableTcpPort();

		System.setProperty("test.port", String.valueOf(managementPort));
	}

	@AfterAll
	public static void afterClass() {
		System.clearProperty("test.port");
	}

	@BeforeEach
	public void setup() {
		baseUri = "http://localhost:" + port;
		this.webClient = WebTestClient.bindToServer().responseTimeout(Duration.ofSeconds(10)).baseUrl(baseUri).build();
	}

	@Test
	public void contextLoads() {
		webClient.get().uri("/get").exchange().expectStatus().isOk();
	}

	@Test
	@SuppressWarnings("unchecked")
	public void readBodyPredicateStringWorks() {
		webClient.post()
			.uri("/post")
			.header("Host", "www.readbody.org")
			.bodyValue("hi")
			.exchange()
			.expectStatus()
			.isOk()
			.expectHeader()
			.valueEquals("X-TestHeader", "read_body_pred")
			.expectBody(Map.class)
			.consumeWith(result -> assertThat(result.getResponseBody()).containsEntry("data", "hi"));
	}

	@Test
	@SuppressWarnings("unchecked")
	public void rewriteRequestBodyStringWorks() {
		webClient.post()
			.uri("/post")
			.header("Host", "www.rewriterequestupper.org")
			.bodyValue("hello")
			.exchange()
			.expectStatus()
			.isOk()
			.expectHeader()
			.valueEquals("X-TestHeader", "rewrite_request_upper")
			.expectBody(Map.class)
			.consumeWith(result -> assertThat(result.getResponseBody()).containsEntry("data", "HELLOHELLO"));
	}

	@Test
	@SuppressWarnings("unchecked")
	public void rewriteRequestBodyObjectWorks() {
		webClient.post()
			.uri("/post")
			.header("Host", "www.rewriterequestobj.org")
			.bodyValue("hello")
			.exchange()
			.expectStatus()
			.isOk()
			.expectHeader()
			.valueEquals("X-TestHeader", "rewrite_request")
			.expectBody(Map.class)
			.consumeWith(
					result -> assertThat(result.getResponseBody()).containsEntry("data", "{\"message\":\"HELLO\"}"));
	}

	@Test
	@SuppressWarnings("unchecked")
	public void rewriteResponseBodyStringWorks() {
		webClient.post()
			.uri("/post")
			.header("Host", "www.rewriteresponseupper.org")
			.bodyValue("hello")
			.exchange()
			.expectStatus()
			.isOk()
			.expectHeader()
			.valueEquals("X-TestHeader", "rewrite_response_upper")
			.expectBody(Map.class)
			.consumeWith(result -> assertThat(result.getResponseBody()).containsEntry("DATA", "HELLO"));
	}

	@Test
	@SuppressWarnings("unchecked")
	public void rewriteResponseEmptyBodyToStringWorks() {
		webClient.post()
			.uri("/post/empty")
			.header("Host", "www.rewriteemptyresponse.org")
			.exchange()
			.expectStatus()
			.isOk()
			.expectHeader()
			.valueEquals("X-TestHeader", "rewrite_empty_response")
			.expectBody(String.class)
			.consumeWith(result -> assertThat(result.getResponseBody()).isEqualTo("emptybody"));
	}

	@Test
	@SuppressWarnings("unchecked")
	public void emptyBodySupplierNotCalledWhenBodyPresent() {
		webClient.post()
			.uri("/post")
			.header("Host", "www.rewriteresponsewithfailsupplier.org")
			.bodyValue("hello")
			.exchange()
			.expectStatus()
			.isOk()
			.expectHeader()
			.valueEquals("X-TestHeader", "rewrite_response_fail_supplier")
			.expectBody(Map.class)
			.consumeWith(result -> assertThat(result.getResponseBody()).containsEntry("DATA", "HELLO"));
	}

	@Test
	@SuppressWarnings("unchecked")
	public void rewriteResponeBodyObjectWorks() {
		webClient.post()
			.uri("/post")
			.header("Host", "www.rewriteresponseobj.org")
			.bodyValue("hello")
			.exchange()
			.expectStatus()
			.isOk()
			.expectHeader()
			.valueEquals("X-TestHeader", "rewrite_response_obj")
			.expectBody(String.class)
			.consumeWith(result -> assertThat(result.getResponseBody()).isEqualTo("hello"));
	}

	@Test
	public void complexPredicate() {
		webClient.get()
			.uri("/anything/png")
			.header("Host", "www.abc.org")
			.exchange()
			.expectHeader()
			.valueEquals("X-TestHeader", "foobar")
			.expectStatus()
			.isOk();
	}

	@Test
	public void routeFromKotlin() {
		webClient.get()
			.uri("/anything/kotlinroute")
			.header("Host", "kotlin.abc.org")
			.exchange()
			.expectHeader()
			.valueEquals("X-TestHeader", "foobar")
			.expectStatus()
			.isOk();
	}

	@Test
	public void actuatorManagementPort() {
		webClient.get()
			.uri("http://localhost:" + managementPort + "/actuator/gateway/routes")
			.exchange()
			.expectStatus()
			.isOk();
	}

	@Test
	public void actuatorMetrics() {
		contextLoads();
		String metricName = metricsProperties.getPrefix() + ".requests";
		webClient.get()
			.uri("http://localhost:" + managementPort + "/actuator/metrics/" + metricName)
			.exchange()
			.expectStatus()
			.isOk()
			.expectBody()
			.consumeWith(i -> {
				String body = new String(i.getResponseBodyContent());
				ObjectMapper mapper = new ObjectMapper();
				try {
					JsonNode actualObj = mapper.readTree(body);
					JsonNode findValue = actualObj.findValue("name");
					assertThat(findValue.asText()).as("Expected to find metric with name gateway.requests")
						.isEqualTo(metricName);
				}
				catch (IOException e) {
					throw new IllegalStateException(e);
				}
			});
	}

	@Test
	void usesNewStarter(CapturedOutput output) {
		assertThat(output).doesNotContain("spring-cloud-starter-gateway is deprecated")
			.doesNotContain("spring-cloud-gateway-server is deprecated");
	}

	@Configuration(proxyBeanMethods = false)
	@EnableAutoConfiguration
	@LoadBalancerClient(name = "httpbin", configuration = LoadBalancerConfig.class)
	@Import(GatewaySampleApplication.class)
	protected static class TestConfig {

		@Bean
		public HttpBinCompatibleController httpBinCompatibleController() {
			return new HttpBinCompatibleController();
		}

	}

	protected static class LoadBalancerConfig {

		@LocalServerPort
		int port;

		@Bean
		public ServiceInstanceListSupplier fixedServiceInstanceListSupplier(Environment env) {
			return ServiceInstanceListSuppliers.from("httpbin",
					new DefaultServiceInstance("httpbin-1", "httpbin", "localhost", port, false));
		}

	}

}
