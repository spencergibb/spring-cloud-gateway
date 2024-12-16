/*
 * Copyright 2013-2023 the original author or authors.
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

package org.springframework.cloud.gateway.server.mvc;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;

import com.github.benmanes.caffeine.cache.Caffeine;
import io.github.bucket4j.caffeine.CaffeineProxyManager;
import io.github.bucket4j.distributed.proxy.AsyncProxyManager;
import io.github.bucket4j.distributed.remote.RemoteBucketState;
import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.cloud.gateway.server.mvc.common.MvcUtils;
import org.springframework.cloud.gateway.server.mvc.filter.FormFilter;
import org.springframework.cloud.gateway.server.mvc.filter.ForwardedRequestHeadersFilter;
import org.springframework.cloud.gateway.server.mvc.filter.XForwardedRequestHeadersFilter;
import org.springframework.cloud.gateway.server.mvc.predicate.GatewayRequestPredicates;
import org.springframework.cloud.gateway.server.mvc.test.HttpbinTestcontainers;
import org.springframework.cloud.gateway.server.mvc.test.HttpbinUriResolver;
import org.springframework.cloud.gateway.server.mvc.test.LocalServerPortUriResolver;
import org.springframework.cloud.gateway.server.mvc.test.TestLoadBalancerConfig;
import org.springframework.cloud.gateway.server.mvc.test.client.TestRestClient;
import org.springframework.cloud.loadbalancer.annotation.LoadBalancerClient;
import org.springframework.context.annotation.Bean;
import org.springframework.core.Ordered;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.RequestEntity;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.MultipartBodyBuilder;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.util.StreamUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.function.HandlerFunction;
import org.springframework.web.servlet.function.RouterFunction;
import org.springframework.web.servlet.function.ServerRequest;
import org.springframework.web.servlet.function.ServerResponse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.cloud.gateway.server.mvc.filter.AfterFilterFunctions.DedupeStrategy.RETAIN_FIRST;
import static org.springframework.cloud.gateway.server.mvc.filter.AfterFilterFunctions.DedupeStrategy.RETAIN_LAST;
import static org.springframework.cloud.gateway.server.mvc.filter.AfterFilterFunctions.DedupeStrategy.RETAIN_UNIQUE;
import static org.springframework.cloud.gateway.server.mvc.filter.AfterFilterFunctions.addResponseHeader;
import static org.springframework.cloud.gateway.server.mvc.filter.AfterFilterFunctions.dedupeResponseHeader;
import static org.springframework.cloud.gateway.server.mvc.filter.AfterFilterFunctions.removeResponseHeader;
import static org.springframework.cloud.gateway.server.mvc.filter.AfterFilterFunctions.rewriteLocationResponseHeader;
import static org.springframework.cloud.gateway.server.mvc.filter.AfterFilterFunctions.rewriteResponseHeader;
import static org.springframework.cloud.gateway.server.mvc.filter.AfterFilterFunctions.setResponseHeader;
import static org.springframework.cloud.gateway.server.mvc.filter.AfterFilterFunctions.setStatus;
import static org.springframework.cloud.gateway.server.mvc.filter.BeforeFilterFunctions.CB_EXECUTION_EXCEPTION_MESSAGE;
import static org.springframework.cloud.gateway.server.mvc.filter.BeforeFilterFunctions.CB_EXECUTION_EXCEPTION_TYPE;
import static org.springframework.cloud.gateway.server.mvc.filter.BeforeFilterFunctions.adaptCachedBody;
import static org.springframework.cloud.gateway.server.mvc.filter.BeforeFilterFunctions.fallbackHeaders;
import static org.springframework.cloud.gateway.server.mvc.filter.BeforeFilterFunctions.mapRequestHeader;
import static org.springframework.cloud.gateway.server.mvc.filter.BeforeFilterFunctions.modifyRequestBody;
import static org.springframework.cloud.gateway.server.mvc.filter.BeforeFilterFunctions.preserveHostHeader;
import static org.springframework.cloud.gateway.server.mvc.filter.BeforeFilterFunctions.removeRequestParameter;
import static org.springframework.cloud.gateway.server.mvc.filter.BeforeFilterFunctions.requestHeaderSize;
import static org.springframework.cloud.gateway.server.mvc.filter.BeforeFilterFunctions.requestHeaderToRequestUri;
import static org.springframework.cloud.gateway.server.mvc.filter.BeforeFilterFunctions.requestSize;
import static org.springframework.cloud.gateway.server.mvc.filter.BeforeFilterFunctions.routeId;
import static org.springframework.cloud.gateway.server.mvc.filter.Bucket4jFilterFunctions.rateLimit;
import static org.springframework.cloud.gateway.server.mvc.filter.CircuitBreakerFilterFunctions.circuitBreaker;
import static org.springframework.cloud.gateway.server.mvc.filter.FilterFunctions.addRequestHeader;
import static org.springframework.cloud.gateway.server.mvc.filter.FilterFunctions.addRequestHeadersIfNotPresent;
import static org.springframework.cloud.gateway.server.mvc.filter.FilterFunctions.addRequestParameter;
import static org.springframework.cloud.gateway.server.mvc.filter.FilterFunctions.prefixPath;
import static org.springframework.cloud.gateway.server.mvc.filter.FilterFunctions.redirectTo;
import static org.springframework.cloud.gateway.server.mvc.filter.FilterFunctions.removeRequestHeader;
import static org.springframework.cloud.gateway.server.mvc.filter.FilterFunctions.rewritePath;
import static org.springframework.cloud.gateway.server.mvc.filter.FilterFunctions.setPath;
import static org.springframework.cloud.gateway.server.mvc.filter.FilterFunctions.setRequestHeader;
import static org.springframework.cloud.gateway.server.mvc.filter.FilterFunctions.setRequestHostHeader;
import static org.springframework.cloud.gateway.server.mvc.filter.FilterFunctions.stripPrefix;
import static org.springframework.cloud.gateway.server.mvc.filter.LoadBalancerFilterFunctions.lb;
import static org.springframework.cloud.gateway.server.mvc.filter.RetryFilterFunctions.retry;
import static org.springframework.cloud.gateway.server.mvc.handler.GatewayRouterFunctions.route;
import static org.springframework.cloud.gateway.server.mvc.handler.HandlerFunctions.forward;
import static org.springframework.cloud.gateway.server.mvc.handler.HandlerFunctions.http;
import static org.springframework.cloud.gateway.server.mvc.predicate.GatewayRequestPredicates.cloudFoundryRouteService;
import static org.springframework.cloud.gateway.server.mvc.predicate.GatewayRequestPredicates.cookie;
import static org.springframework.cloud.gateway.server.mvc.predicate.GatewayRequestPredicates.header;
import static org.springframework.cloud.gateway.server.mvc.predicate.GatewayRequestPredicates.host;
import static org.springframework.cloud.gateway.server.mvc.predicate.GatewayRequestPredicates.query;
import static org.springframework.cloud.gateway.server.mvc.predicate.GatewayRequestPredicates.readBody;
import static org.springframework.cloud.gateway.server.mvc.test.TestUtils.getMap;
import static org.springframework.http.MediaType.APPLICATION_FORM_URLENCODED;
import static org.springframework.http.MediaType.MULTIPART_FORM_DATA;
import static org.springframework.web.servlet.function.RequestPredicates.GET;
import static org.springframework.web.servlet.function.RequestPredicates.POST;
import static org.springframework.web.servlet.function.RequestPredicates.path;

@SuppressWarnings("unchecked")
@SpringBootTest(properties = { "spring.cloud.gateway.mvc.http-client.type=jdk" },
		webEnvironment = WebEnvironment.RANDOM_PORT)
@ContextConfiguration(initializers = HttpbinTestcontainers.class)
@ExtendWith(OutputCaptureExtension.class)
public class ServerMvcIntegrationTests {

	static {
		// if set type to autodetect above
		System.setProperty("sun.net.http.allowRestrictedHeaders", "true");
	}

	@LocalServerPort
	int port;

	@Autowired
	TestRestTemplate restTemplate;

	@Autowired
	TestRestClient restClient;

	@Test
	public void nonGatewayRouterFunctionWorks() {
		restClient.get().uri("/hello").exchange().expectStatus().isOk().expectBody(String.class).isEqualTo("Hello");
	}

	@SuppressWarnings("rawtypes")
	@Test
	public void addRequestHeaderWorks() {
		restClient.get().uri("/get").exchange().expectStatus().isOk().expectBody(Map.class).consumeWith(res -> {
			Map<String, Object> headers = getMap(res.getResponseBody(), "headers");
			assertThat(headers).containsEntry("X-Foo", "Bar");
		});
	}

	@Test
	public void addRequestParameterWorks() {
		restClient.get()
			.uri("/anything/addrequestparam")
			.exchange()
			.expectStatus()
			.isOk()
			.expectBody(Map.class)
			.consumeWith(res -> {
				Map<String, Object> map = res.getResponseBody();
				Map<String, Object> args = getMap(map, "args");
				assertThat(args).containsEntry("param1", "param1val");
			});
	}

	@Test
	public void removeHopByHopRequestHeadersFilterWorks() {
		restClient.get()
			.uri("/anything/removehopbyhoprequestheaders")
			.exchange()
			.expectStatus()
			.isOk()
			.expectBody(Map.class)
			.consumeWith(res -> {
				Map<String, Object> map = res.getResponseBody();
				Map<String, Object> headers = getMap(map, "headers");
				assertThat(headers).doesNotContainKeys("x-application-context");
			});
	}

	@Test
	public void setPathWorks() {
		restClient.get()
			.uri("/mycustompathextra1")
			.exchange()
			.expectStatus()
			.isOk()
			.expectBody(Map.class)
			.consumeWith(res -> {
				Map<String, Object> map = res.getResponseBody();
				Map<String, Object> args = getMap(map, "args");
				assertThat(args).containsEntry("param1", "param1valextra1");
			});
	}

	@Test
	public void setPathPostWorks() {
		restClient.post()
			.uri("/mycustompathpost")
			.bodyValue("hello")
			.header("Host", "www.setpathpost.org")
			.exchange()
			.expectStatus()
			.isOk()
			.expectBody(Map.class)
			.consumeWith(res -> {
				Map<String, Object> map = res.getResponseBody();
				assertThat(map).containsEntry("data", "hello");
			});
	}

	@Test
	public void stripPrefixWorks() {
		restClient.get()
			.uri("/long/path/to/get")
			.exchange()
			.expectStatus()
			.isOk()
			.expectBody(Map.class)
			.consumeWith(res -> {
				Map<String, Object> map = res.getResponseBody();
				Map<String, Object> headers = getMap(map, "headers");
				assertThat(headers).containsEntry("X-Test", "stripPrefix");
			});
	}

	@Test
	public void stripPrefixPostWorks() {
		restClient.post()
			.uri("/long/path/to/post")
			.bodyValue("hello")
			.header("Host", "www.stripprefixpost.org")
			.exchange()
			.expectStatus()
			.isOk()
			.expectBody(Map.class)
			.consumeWith(res -> {
				Map<String, Object> map = res.getResponseBody();
				assertThat(map).containsEntry("data", "hello");
				Map<String, Object> headers = getMap(map, "headers");
				assertThat(headers).containsEntry("X-Test", "stripPrefixPost");
			});
	}

	@Test
	public void setStatusGatewayRouterFunctionWorks() {
		restClient.get()
			.uri("/status/201")
			.exchange()
			.expectStatus()
			.isEqualTo(HttpStatus.TOO_MANY_REQUESTS)
			.expectHeader()
			.valueEquals("x-status", "201"); // .expectBody(String.class).isEqualTo("Failed
												// with 201");
	}

	@Test
	public void addResponseHeaderWorks() {
		restClient.get()
			.uri("/anything/addresheader")
			.exchange()
			.expectStatus()
			.isOk()
			.expectBody(Map.class)
			.consumeWith(res -> {
				Map<String, Object> map = res.getResponseBody();
				Map<String, Object> headers = getMap(map, "headers");
				assertThat(headers).doesNotContainKey("x-bar");
				assertThat(res.getResponseHeaders()).containsEntry("x-bar", Collections.singletonList("val1"));
			});
	}

	@Test
	public void postWorks() {
		restClient.post()
			.uri("/post")
			.bodyValue("Post Value")
			.header("test", "post")
			.exchange()
			.expectStatus()
			.isOk()
			.expectBody(Map.class)
			.consumeWith(res -> {
				Map<String, Object> map = res.getResponseBody();
				assertThat(map).isNotEmpty().containsEntry("data", "Post Value");
			});
	}

	@Test
	public void loadbalancerWorks() {
		restClient.get()
			.uri("/anything/loadbalancer")
			.exchange()
			.expectStatus()
			.isOk()
			.expectBody(Map.class)
			.consumeWith(res -> {
				Map<String, Object> map = res.getResponseBody();
				Map<String, Object> headers = getMap(map, "headers");
				assertThat(headers).containsEntry("X-Test", "loadbalancer");
			});
	}

	@Test
	public void hostPredicateWorks() {
		String host = "www1.myjavadslhost.com";
		restClient.get()
			.uri("/anything/hostpredicate")
			.header("Host", host)
			.exchange()
			.expectStatus()
			.isOk()
			.expectHeader()
			.valueEquals("X-SubDomain", "www1")
			.expectBody(Map.class)
			.consumeWith(res -> {
				Map<String, Object> map = res.getResponseBody();
				Map<String, Object> headers = getMap(map, "headers");
				assertThat(headers).containsEntry("Host", host);
			});
	}

	@Test
	public void circuitBreakerFallbackWorks() {
		restClient.get()
			.uri("/anything/circuitbreakerfallback")
			.exchange()
			.expectStatus()
			.isOk()
			.expectBody(String.class)
			.isEqualTo("Hello");
	}

	@Test
	public void circuitBreakerGatewayFallbackWorks() {
		restClient.get()
			.uri("/anything/circuitbreakergatewayfallback")
			.exchange()
			.expectStatus()
			.isOk()
			.expectBody(Map.class)
			.consumeWith(res -> {
				Map<String, Object> headers = getMap(res.getResponseBody(), "headers");
				assertThat(headers).containsKeys(CB_EXECUTION_EXCEPTION_TYPE, CB_EXECUTION_EXCEPTION_MESSAGE);
			});
	}

	@Test
	public void circuitBreakerNoFallbackWorks() {
		restClient.get()
			.uri("/anything/circuitbreakernofallback")
			.exchange()
			.expectStatus()
			.isEqualTo(HttpStatus.GATEWAY_TIMEOUT);
	}

	@Test
	public void circuitBreakerInvalidFallbackThrowsException() {
		// @formatter:off
		Assertions.assertThatThrownBy(() -> route("testcircuitbreakergatewayfallback")
				.route(path("/anything/circuitbreakergatewayfallback"), http(URI.create("https://nonexistantdomain.com1234")))
				.filter(circuitBreaker("mycb2", URI.create("http://example.com")))
				.build()).isInstanceOf(IllegalArgumentException.class);
		// @formatter:on
	}

	@Test
	public void retryWorks() {
		restClient.get().uri("/retry?key=get").exchange().expectStatus().isOk().expectBody(String.class).isEqualTo("3");
		// test for: java.lang.IllegalArgumentException: You have already selected another
		// retry policy
		restClient.get()
			.uri("/retry?key=get2")
			.exchange()
			.expectStatus()
			.isOk()
			.expectBody(String.class)
			.isEqualTo("3");
	}

	@Test
	public void rateLimitWorks() {
		restClient.get().uri("/anything/ratelimit").exchange().expectStatus().isOk();
		restClient.get().uri("/anything/ratelimit").exchange().expectStatus().isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
	}

	@Test
	public void headerRegexWorks() {
		restClient.get().uri("/headerregex").exchange().expectStatus().isNotFound();
		restClient.get()
			.uri("/headerregex")
			.header("X-MyHeader", "foo")
			.exchange()
			.expectStatus()
			.isOk()
			.expectBody(Map.class)
			.consumeWith(res -> {
				Map<String, Object> map = res.getResponseBody();
				Map<String, Object> headers = getMap(map, "headers");
				assertThat(headers).containsEntry("X-Myheader", "foo");
			});
	}

	@Test
	public void cookieRegexWorks() {
		restClient.get().uri("/cookieregex").exchange().expectStatus().isNotFound();
		restClient.get()
			.uri("/cookieregex")
			.cookie("mycookie", "foo")
			.exchange()
			.expectStatus()
			.isOk()
			.expectBody(Map.class)
			.consumeWith(res -> {
				Map<String, Object> map = res.getResponseBody();
				Map<String, Object> headers = getMap(map, "headers");
				assertThat(headers).containsEntry("Cookie", "mycookie=foo");
			});
	}

	@Test
	public void rewritePathWorks() {
		restClient.get()
			.uri("/foo/get")
			.header("Host", "www.rewritepath.org")
			.exchange()
			.expectStatus()
			.isOk()
			.expectBody(Map.class)
			.consumeWith(res -> {
				Map<String, Object> map = res.getResponseBody();
				Map<String, Object> headers = getMap(map, "headers");
				assertThat(headers).containsEntry("X-Test", "rewritepath");
			});
	}

	@Test
	public void rewritePathPostWorks() {
		restClient.post()
			.uri("/baz/post")
			.bodyValue("hello")
			.header("Host", "www.rewritepathpost.org")
			.exchange()
			.expectStatus()
			.isOk()
			.expectBody(Map.class)
			.consumeWith(res -> {
				Map<String, Object> map = res.getResponseBody();
				assertThat(map).containsEntry("data", "hello");
				Map<String, Object> headers = getMap(map, "headers");
				assertThat(headers).containsEntry("X-Test", "rewritepathpost");
			});
	}

	@Test
	public void rewritePathPostLocalWorks() {
		restClient.post()
			.uri("/baz/post")
			.bodyValue("hello")
			.header("Host", "www.rewritepathpostlocal.org")
			.exchange()
			.expectStatus()
			.isOk()
			.expectBody(Map.class)
			.consumeWith(res -> {
				Map<String, Object> map = res.getResponseBody();
				assertThat(map).containsEntry("data", "hello");
				Map<String, Object> headers = getMap(map, "headers");
				assertThat(headers).containsEntry("x-test", "rewritepathpostlocal");
			});
	}

	@Test
	public void forwardedHeadersWork() {
		restClient.get()
			.uri("/headers")
			.header("test", "forwarded")
			.exchange()
			.expectStatus()
			.isOk()
			.expectBody(Map.class)
			.consumeWith(res -> {
				Map<String, Object> map = res.getResponseBody();
				Map<String, Object> headers = getMap(map, "headers");
				assertThat(headers).containsKeys(ForwardedRequestHeadersFilter.FORWARDED_HEADER,
						XForwardedRequestHeadersFilter.X_FORWARDED_FOR_HEADER,
						XForwardedRequestHeadersFilter.X_FORWARDED_HOST_HEADER,
						XForwardedRequestHeadersFilter.X_FORWARDED_PORT_HEADER,
						XForwardedRequestHeadersFilter.X_FORWARDED_PROTO_HEADER);
				assertThat(headers.get(ForwardedRequestHeadersFilter.FORWARDED_HEADER)).asString()
					.contains("proto=http")
					.contains("host=\"localhost:")
					.contains("for=\"127.0.0.1:");
				assertThat(headers.get(XForwardedRequestHeadersFilter.X_FORWARDED_HOST_HEADER)).asString()
					.isEqualTo("localhost:" + this.port);
				assertThat(headers.get(XForwardedRequestHeadersFilter.X_FORWARDED_PORT_HEADER)).asString()
					.isEqualTo(String.valueOf(this.port));
				assertThat(headers.get(XForwardedRequestHeadersFilter.X_FORWARDED_PROTO_HEADER).toString()).asString()
					.isEqualTo("http");
			});
	}

	@Test
	public void requestSizeWorks() {
		restClient.post()
			.uri("/post")
			.bodyValue("123456")
			.header("test", "requestsize")
			.exchange()
			.expectStatus()
			.isEqualTo(HttpStatus.PAYLOAD_TOO_LARGE)
			.expectHeader()
			.valueMatches("errormessage",
					"Request size is larger than permissible limit. Request size is .* where permissible limit is .*");
	}

	@Test
	public void requestHeaderSizeWorks() {
		restClient.get()
			.uri("/headers")
			.header("test", "requestheadersize")
			.header("X-AnyHeader", "11111111112222222222333333333344444444445555555555666666666677777777778888888888")
			.exchange()
			.expectStatus()
			.isEqualTo(HttpStatus.REQUEST_HEADER_FIELDS_TOO_LARGE)
			.expectHeader()
			.valueMatches("errormessage",
					"Request Header/s size is larger than permissible limit (.*). Request Header/s size for 'x-anyheader' is .*");
		restClient.get()
			.uri("/headers")
			.header("test", "requestheadersize")
			.header("X-AnyHeader", "111111111122222222223333333333444444444455555555556666666666")
			.exchange()
			.expectStatus()
			.isOk();
	}

	public static final MediaType FORM_URL_ENCODED_CONTENT_TYPE = new MediaType(APPLICATION_FORM_URLENCODED,
			StandardCharsets.UTF_8);

	@Test
	void formUrlencodedWorks() {
		LinkedMultiValueMap<String, String> formData = new LinkedMultiValueMap<>();
		formData.add("foo", "bar");
		formData.add("baz", "bam");

		// @formatter:off
		restClient.post().uri("/post?foo=fooquery").header("test", "formurlencoded").contentType(FORM_URL_ENCODED_CONTENT_TYPE)
				.bodyValue(formData)
				.exchange()
				.expectStatus().isOk()
				.expectBody(Map.class).consumeWith(result -> {
					Map map = result.getResponseBody();
					Map<String, Object> form = getMap(map, "form");
					assertThat(form).containsEntry("foo", "bar");
					assertThat(form).containsEntry("baz", "bam");
				});
		// @formatter:on
	}

	@Test
	void multipartFormDataWorks() {
		MultiValueMap<String, HttpEntity<?>> formData = createMultipartData();
		// @formatter:off
		restClient.post().uri("/post").contentType(MULTIPART_FORM_DATA)
				.header("Host", "www.testform.org")
				.bodyValue(formData)
				.exchange()
				.expectStatus().isOk()
				.expectBody(Map.class)
				.consumeWith(result -> {
					assertMultipartData(result.getResponseBody());
				});
		// @formatter:on
	}

	@Test
	void multipartFormDataRestTemplateWorks() {
		MultiValueMap<String, HttpEntity<?>> formData = createMultipartData();
		RequestEntity<MultiValueMap<String, HttpEntity<?>>> request = RequestEntity.post("/post")
			.contentType(MULTIPART_FORM_DATA)
			.header("Host", "www.testform.org")
			.body(formData);
		ResponseEntity<Map> response = restTemplate.exchange(request, Map.class);
		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertMultipartData(response.getBody());
	}

	@Test
	public void redirectToWorks() {
		restClient.get()
			.uri("/anything/redirect")
			.exchange()
			.expectStatus()
			.isEqualTo(HttpStatus.MOVED_PERMANENTLY)
			.expectHeader()
			.valueEquals(HttpHeaders.LOCATION, "https://exampleredirect.com");
	}

	private MultiValueMap<String, HttpEntity<?>> createMultipartData() {
		ClassPathResource part = new ClassPathResource("test/1x1.png");
		MultipartBodyBuilder builder = new MultipartBodyBuilder();
		builder.part("imgpart", part, MediaType.IMAGE_PNG);
		return builder.build();
	}

	private void assertMultipartData(Map responseBody) {
		Map<String, Object> files = (Map<String, Object>) responseBody.get("files");
		assertThat(files).containsKey("imgpart");
		String file = (String) files.get("imgpart");
		assertThat(file).startsWith("data:").contains(";base64,");
	}

	@Test
	public void removeRequestHeaderWorks() {
		restClient.get()
			.uri("/anything/removerequestheader")
			.header("X-Request-Foo", "Bar")
			.exchange()
			.expectStatus()
			.isOk()
			.expectBody(Map.class)
			.consumeWith(res -> {
				Map<String, Object> map = res.getResponseBody();
				Map<String, Object> headers = getMap(map, "headers");
				assertThat(headers).doesNotContainKey("X-Request-Foo");
			});
	}

	@Test
	public void setRequestHeaderWorks() {
		restClient.get()
			.uri("/headers")
			.header("test", "setrequestheader")
			.exchange()
			.expectStatus()
			.isOk()
			.expectBody(Map.class)
			.consumeWith(res -> {
				Map<String, Object> headers = getMap(res.getResponseBody(), "headers");
				assertThat(headers).doesNotContainEntry("X-Test", "value1");
				assertThat(headers).containsEntry("X-Test", "value2");
			});
	}

	@Test
	public void setRequestHeaderHostWorks() {
		restClient.get()
			.uri("/headers")
			.header("Host", "www.setrequesthostheader.org")
			.exchange()
			.expectStatus()
			.isOk()
			.expectBody(Map.class)
			.consumeWith(res -> {
				Map<String, Object> headers = getMap(res.getResponseBody(), "headers");
				assertThat(headers).containsEntry("Host", "otherhost.io");
			});
	}

	@Test
	public void setResponseHeaderWorks() {
		restClient.get()
			.uri("/anything/setresponseheader")
			.header("test", "setresponseheader")
			.exchange()
			.expectStatus()
			.isOk()
			.expectBody(Map.class)
			.consumeWith(res -> {
				HttpHeaders headers = res.getResponseHeaders();
				assertThat(headers).doesNotContainEntry("X-Test", List.of("value1"));
				assertThat(headers).containsEntry("X-Test", List.of("value2"));
			});
	}

	@Test
	public void nestedRouteWorks() {
		testNestedRoute("nested1");
		testNestedRoute("nested2");
	}

	private void testNestedRoute(String nestedPath) {
		restClient.get()
			.uri("/anything/nested/" + nestedPath)
			.header("test", "nested")
			.exchange()
			.expectStatus()
			.isOk()
			.expectBody(Map.class)
			.consumeWith(res -> {
				Map<String, Object> headers = getMap(res.getResponseBody(), "headers");
				assertThat(headers).containsEntry("X-Test", nestedPath);
			});
	}

	@Test
	public void removeRequestParameterWorks() {
		restClient.get()
			.uri("/anything/removerequestparameter?foo=bar")
			.header("test", "removerequestparam")
			.exchange()
			.expectStatus()
			.isOk()
			.expectHeader()
			.doesNotExist("foo");
	}

	@Test
	public void removeRequestParameterPostWorks() {
		restClient.post()
			.uri("/post?foo=bar")
			.bodyValue("hello")
			.header("Host", "www.removerequestparampost.org")
			.exchange()
			.expectStatus()
			.isOk()
			.expectHeader()
			.doesNotExist("foo")
			.expectBody(Map.class)
			.consumeWith(res -> {
				assertThat(res.getResponseBody()).containsEntry("data", "hello");
			});
	}

	@Test
	public void removeResponseHeaderWorks() {
		restClient.get()
			.uri("/anything/removeresponseheader")
			.header("test", "removeresponseheader")
			.exchange()
			.expectStatus()
			.isOk()
			.expectHeader()
			.doesNotExist("X-Test");
	}

	@Test
	public void rewriteResponseHeaderWorks() {
		restClient.get()
			.uri("/headers")
			.header("test", "rewriteresponseheader")
			.exchange()
			.expectStatus()
			.isOk()
			.expectHeader()
			.valueEquals("X-Request-Foo", "/42?user=ford&password=***&flag=true");
	}

	@Test
	public void requestHeaderToRequestUriWorks() {
		// @formatter:off
		restClient.get().uri("/anything/requestheadertorequesturi")
				.header("Host", "www.requestheadertorequesturi.org")
				.header("X-CF-Forwarded-Url", "http://localhost:" + port)
				.header("X-CF-Proxy-Signature", "fakeproxysignature")
				.header("X-CF-Proxy-Metadata", "fakeproxymetadata")
				.exchange().expectStatus().isOk().expectBody(String.class).isEqualTo("Hello");
		// @formatter:on
	}

	@Test
	public void mapRequestHeaderWorks() {
		restClient.get()
			.uri("/anything/maprequestheader")
			.header("X-Foo", "fooval")
			.exchange()
			.expectStatus()
			.isOk()
			.expectBody(Map.class)
			.consumeWith(res -> {
				Map<String, Object> headers = getMap(res.getResponseBody(), "headers");
				assertThat(headers).containsEntry("X-Bar", "fooval");
			});
	}

	@Test
	public void dedupeResponseHeaderWorks() {
		restClient.get()
			.uri("/headers")
			.header("Host", "www.deduperesponseheader.org")
			.exchange()
			.expectStatus()
			.isOk()
			.expectHeader()
			.valueEquals("Access-Control-Allow-Credentials", "true")
			.expectHeader()
			.valueEquals("Access-Control-Allow-Origin", "https://example.org")
			.expectHeader()
			.valueEquals("Scout-Cookie", "S'mores")
			.expectHeader()
			.valueEquals("Next-Week-Lottery-Numbers", "4", "2", "42");
	}

	@Test
	public void addRequestHeadersIfNotPresentWorks() {
		restClient.get()
			.uri("/headers")
			.header("Host", "www.addrequestheadersifnotpresent.org")
			.header("X-Request-Beta", "Value1")
			.exchange()
			.expectStatus()
			.isOk()
			.expectBody(Map.class)
			.consumeWith(res -> {
				Map<String, Object> headers = getMap(res.getResponseBody(), "headers");
				// this asserts that Value2 was not added
				assertThat(headers).containsEntry("X-Request-Beta", "Value1");
				assertThat(headers).containsKey("X-Request-Acme");
				List<String> values = (List<String>) headers.get("X-Request-Acme");
				assertThat(values).hasSize(4).containsOnly("ValueX", "ValueY", "ValueZ", "www");
			});
	}

	@Test
	public void rewriteLocationResponseHeaderWorks() {
		restClient.get()
			.uri("/anything/rewritelocationresponseheader")
			.header("Host", "test1.rewritelocationresponseheader.org")
			.exchange()
			.expectStatus()
			.isOk()
			.expectHeader()
			.valueEquals("Location", "https://test1.rewritelocationresponseheader.org/some/object/id");
	}

	@Test
	public void readBodyWorks() {
		Event messageEvent = new Event("message", "bar");

		restClient.post()
			.uri("/events")
			.bodyValue(messageEvent)
			.exchange()
			.expectStatus()
			.isOk()
			.expectHeader()
			.valueEquals("X-Foo", "message")
			.expectBody(Event.class)
			.consumeWith(res -> assertThat(res.getResponseBody()).isEqualTo(messageEvent));

		Event messageChannelEvent = new Event("message.channel", "baz");

		restClient.post()
			.uri("/events")
			.bodyValue(messageChannelEvent)
			.exchange()
			.expectStatus()
			.isOk()
			.expectHeader()
			.valueEquals("X-Channel-Foo", "message.channel")
			.expectBody(Event.class)
			.consumeWith(res -> assertThat(res.getResponseBody()).isEqualTo(messageChannelEvent));

	}

	@Test
	@SuppressWarnings("unchecked")
	public void rewriteRequestBodyStringWorks() {
		restClient.post()
			.uri("/post")
			.header("Host", "www.modifyrequestbodystring.org")
			.bodyValue("hello")
			.exchange()
			.expectStatus()
			.isOk()
			.expectBody(Map.class)
			.consumeWith(result -> assertThat(result.getResponseBody()).containsEntry("data", "HELLOHELLO"));
	}

	@Test
	@SuppressWarnings("unchecked")
	public void rewriteRequestBodyObjectWorks() {
		restClient.post()
			.uri("/post")
			.header("Host", "www.modifyrequestbodyobject.org")
			.bodyValue("hello world")
			.exchange()
			.expectStatus()
			.isOk()
			.expectBody(Map.class)
			.consumeWith(result -> assertThat(result.getResponseBody()).containsEntry("data",
					"{\"message\":\"HELLO WORLD\"}"));
	}

	@Test
	public void forwardWorks() {
		restClient.get().uri("/doforward").exchange().expectStatus().isOk().expectBody(String.class).isEqualTo("Hello");
	}

	@Test
	public void forwardNon200StatusWorks() {
		restClient.get()
			.uri("/doforward2")
			.exchange()
			.expectStatus()
			.isCreated()
			.expectBody(String.class)
			.isEqualTo("hello2");
	}

	@Test
	@SuppressWarnings("rawtypes")
	public void queryParamWorks() {
		restClient.get()
			.uri("/get?foo=bar")
			.exchange()
			.expectStatus()
			.isOk()
			.expectBody(Map.class)
			.consumeWith(result -> {
				Map responseBody = result.getResponseBody();
				assertThat(responseBody).containsKey("args");
				Map args = getMap(responseBody, "args");
				assertThat(args).containsKey("foo");
				assertThat(args.get("foo")).isEqualTo("bar");
			});
	}

	@SuppressWarnings("rawtypes")
	@Test
	public void queryParamWithSpecialCharactersWorks() {
		restClient.get()
			.uri("/get?myparam= &intlparam=æøå")
			.exchange()
			.expectStatus()
			.isOk()
			.expectBody(Map.class)
			.consumeWith(result -> {
				Map responseBody = result.getResponseBody();
				assertThat(responseBody).containsKey("args");
				Map args = getMap(responseBody, "args");
				assertThat(args).containsKey("myparam");
				assertThat(args.get("myparam")).isEqualTo(" ");
				assertThat(args).containsKey("intlparam");
				assertThat(args.get("intlparam")).isEqualTo("æøå");
			});
	}

	@Test
	public void clientResponseBodyAttributeWorks() {
		restClient.get()
			.uri("/anything/readresponsebody")
			.header("X-Foo", "fooval")
			.exchange()
			.expectStatus()
			.isOk()
			.expectBody(Map.class)
			.consumeWith(res -> {
				Map<String, Object> headers = getMap(res.getResponseBody(), "headers");
				assertThat(headers).containsEntry("X-Foo", "FOOVAL");
			});
	}

	@Test
	void logsArtifactDeprecatedWarning(CapturedOutput output) {
		assertThat(output).contains("spring-cloud-gateway-server-mvc is deprecated");
	}

	@SpringBootConfiguration
	@EnableAutoConfiguration
	@LoadBalancerClient(name = "httpbin", configuration = TestLoadBalancerConfig.Httpbin.class)
	protected static class TestConfiguration {

		@Bean
		TestHandler testHandler() {
			return new TestHandler();
		}

		@Bean
		RetryController retryController() {
			return new RetryController();
		}

		@Bean
		EventController eventController() {
			return new EventController();
		}

		@Bean
		public AsyncProxyManager<String> caffeineProxyManager() {
			Caffeine<String, RemoteBucketState> builder = (Caffeine) Caffeine.newBuilder().maximumSize(100);
			return new CaffeineProxyManager<>(builder, Duration.ofMinutes(1)).asAsync();
		}

		@Bean
		public RouterFunction<ServerResponse> nonGatewayRouterFunctions(TestHandler testHandler) {
			return route(GET("/hello"), testHandler).withAttribute(MvcUtils.GATEWAY_ROUTE_ID_ATTR, "hello");
		}

		@Bean
		public RouterFunction<ServerResponse> nonGatewayRouterFunctions2() {
			return route(GET("/hello2"), request -> ServerResponse.status(HttpStatus.CREATED).body("hello2"))
				.withAttribute(MvcUtils.GATEWAY_ROUTE_ID_ATTR, "hello2");
		}

		@Bean
		public RouterFunction<ServerResponse> gatewayRouterFunctionsAddReqHeader() {
			// @formatter:off
			return route(GET("/get"), http())
					.filter(new HttpbinUriResolver())
					.filter(addRequestHeader("X-Foo", "Bar"))
					.withAttribute(MvcUtils.GATEWAY_ROUTE_ID_ATTR, "testaddreqheader");
			// @formatter:on
		}

		@Bean
		public RouterFunction<ServerResponse> gatewayRouterFunctionsSetStatusAndAddRespHeader() {
			// @formatter:off
			return route("testsetstatus")
					.GET("/status/{status}", http())
					.before(new HttpbinUriResolver())
					.after(setStatus(HttpStatus.TOO_MANY_REQUESTS))
					.after(addResponseHeader("X-Status", "{status}"))
				.build().and(route()
					.GET("/anything/addresheader", http())
					.before(routeId("testaddresponseheader"))
					.before(new HttpbinUriResolver())
					.after(addResponseHeader("X-Bar", "val1"))
				.build());
			// @formatter:on
		}

		@Bean
		public RouterFunction<ServerResponse> gatewayRouterFunctionsAddRequestParam() {
			// @formatter:off
			return route(GET("/anything/addrequestparam"), http())
					.filter(new HttpbinUriResolver())
					.filter(addRequestParameter("param1", "param1val"))
					.withAttribute(MvcUtils.GATEWAY_ROUTE_ID_ATTR, "testaddrequestparam");
			// @formatter:on
		}

		@Bean
		public RouterFunction<ServerResponse> gatewayRouterFunctionsSetPath() {
			// @formatter:off
			return route(GET("/mycustompath{extra}"), http())
					.filter(new HttpbinUriResolver())
					.filter(setPath("/anything/mycustompath{extra}"))
					.filter(addRequestParameter("param1", "param1val{extra}"))
					.withAttribute(MvcUtils.GATEWAY_ROUTE_ID_ATTR, "testsetpath");
			// @formatter:on
		}

		@Bean
		public RouterFunction<ServerResponse> gatewayRouterFunctionsSetPathPost() {
			// @formatter:off
			return route("testsetpath")
					.route(POST("/mycustompath{extra}").and(host("**.setpathpost.org")), http())
					.filter(new HttpbinUriResolver())
					.filter(setPath("/{extra}"))
					.build();
			// @formatter:on
		}

		@Bean
		public RouterFunction<ServerResponse> gatewayRouterFunctionsStripPrefix() {
			// @formatter:off
			return route(GET("/long/path/to/get"), http())
					.filter(new HttpbinUriResolver())
					.filter(stripPrefix(3))
					.filter(addRequestHeader("X-Test", "stripPrefix"))
					.withAttribute(MvcUtils.GATEWAY_ROUTE_ID_ATTR, "teststripprefix");
			// @formatter:on
		}

		@Bean
		public RouterFunction<ServerResponse> gatewayRouterFunctionsStripPrefixPost() {
			// @formatter:off
			return route("teststripprefixpost")
					.route(POST("/long/path/to/post").and(host("**.stripprefixpost.org")), http())
					.filter(new HttpbinUriResolver())
					.filter(stripPrefix(3))
					.filter(addRequestHeader("X-Test", "stripPrefixPost"))
					.build();
			// @formatter:on
		}

		@Bean
		public RouterFunction<ServerResponse> gatewayRouterFunctionsRemoveHopByHopRequestHeaders() {
			// @formatter:off
			return route(GET("/anything/removehopbyhoprequestheaders"), http())
					.filter(new HttpbinUriResolver())
					.filter(addRequestHeader("x-application-context", "context-id1"))
					.withAttribute(MvcUtils.GATEWAY_ROUTE_ID_ATTR, "testremovehopbyhopheaders");
			// @formatter:on
		}

		@Bean
		public RouterFunction<ServerResponse> gatewayRouterFunctionsPost() {
			// @formatter:off
			return route(POST("/post").and(header("test", "post")), http())
					.filter(new HttpbinUriResolver())
					.withAttribute(MvcUtils.GATEWAY_ROUTE_ID_ATTR, "testpost");
			// @formatter:on
		}

		@Bean
		public RouterFunction<ServerResponse> gatewayRouterFunctionsLoadBalancer() {
			// @formatter:off
			return route()
					.GET("/anything/loadbalancer", http())
					.filter(lb("httpbin"))
					.filter(addRequestHeader("X-Test", "loadbalancer"))
					.withAttribute(MvcUtils.GATEWAY_ROUTE_ID_ATTR, "testloadbalancer")
					.build();
			// @formatter:on
		}

		@Bean
		public RouterFunction<ServerResponse> gatewayRouterFunctionsHost() {
			// @formatter:off
			return route("testhostpredicate")
					.route(host("{sub}.somehotherhost.com", "{sub}.myjavadslhost.com").and(path("/anything/hostpredicate")), http())
					.before(new HttpbinUriResolver())
					.before(preserveHostHeader())
					.after(addResponseHeader("X-SubDomain", "{sub}"))
					.build();
			// @formatter:on
		}

		@Bean
		public RouterFunction<ServerResponse> gatewayRouterFunctionsCircuitBreakerFallback() {
			// @formatter:off
			return route("testcircuitbreakerfallback")
					.route(path("/anything/circuitbreakerfallback"), http(URI.create("https://nonexistantdomain.com1234")))
					.filter(circuitBreaker("mycb1", "/hello"))
					.build();
			// @formatter:on
		}

		@Bean
		public RouterFunction<ServerResponse> gatewayRouterFunctionsCircuitBreakerFallbackToGatewayRoute() {
			// @formatter:off
			return route("testcircuitbreakergatewayfallback")
					.route(path("/anything/circuitbreakergatewayfallback"), http(URI.create("https://nonexistantdomain.com1234")))
					.filter(circuitBreaker("mycb2", URI.create("forward:/anything/gatewayfallback")))
					.build()
				.and(route("testgatewayfallback")
					.route(path("/anything/gatewayfallback"), http())
					.before(new HttpbinUriResolver())
					.before(fallbackHeaders())
					.build());
			// @formatter:on
		}

		@Bean
		public RouterFunction<ServerResponse> gatewayRouterFunctionsCircuitBreakerNoFallback() {
			// @formatter:off
			return route(path("/anything/circuitbreakernofallback"), http())
					.filter(new HttpbinUriResolver())
					.filter(circuitBreaker("mycb3"))
					//.filter(circuitBreaker(config -> config.setId("myCircuitBreaker").setFallbackUri("forward:/inCaseOfFailureUseThis").setStatusCodes("500", "NOT_FOUND")))
					.filter(setPath("/delay/5"))
					.withAttribute(MvcUtils.GATEWAY_ROUTE_ID_ATTR, "testcircuitbreakernofallback");
			// @formatter:on
		}

		@Bean
		public RouterFunction<ServerResponse> gatewayRouterFunctionsRetry() {
			// @formatter:off
			return route("testretry")
					.route(path("/retry"), http())
					.before(new LocalServerPortUriResolver())
					.filter(retry(3))
					//.filter(retry(config -> config.setRetries(3).setSeries(Set.of(HttpStatus.Series.SERVER_ERROR)).setMethods(Set.of(HttpMethod.GET, HttpMethod.POST))))
					.filter(prefixPath("/do"))
					.build();
			// @formatter:on
		}

		@Bean
		public RouterFunction<ServerResponse> gatewayRouterFunctionsRateLimit() {
			// @formatter:off
			return route(GET("/anything/ratelimit"), http())
					.filter(new HttpbinUriResolver())
					//.filter(rateLimit(1, Duration.ofMinutes(1), request -> "ratelimittest1min"))
					.filter(rateLimit(c -> c.setCapacity(1)
							.setPeriod(Duration.ofMinutes(1))
							.setKeyResolver(request -> "ratelimitttest1min")))
					/*.filter(rateLimit(c -> c.setCapacity(100)
							.setPeriod(Duration.ofMinutes(1))
							.setKeyResolver(request -> request.servletRequest().getUserPrincipal().getName())))*/
					.withAttribute(MvcUtils.GATEWAY_ROUTE_ID_ATTR, "testratelimit");
			// @formatter:on
		}

		@Bean
		public RouterFunction<ServerResponse> gatewayRouterFunctionsHeaderPredicate() {
			// @formatter:off
			return route(path("/headerregex").and(header("X-MyHeader", "fo.")), http())
					.filter(new HttpbinUriResolver())
					.filter(setPath("/headers"))
					.withAttribute(MvcUtils.GATEWAY_ROUTE_ID_ATTR, "testheaderpredicate");
			// @formatter:on
		}

		@Bean
		public RouterFunction<ServerResponse> gatewayRouterFunctionsCookiePredicate() {
			// @formatter:off
			return route(GatewayRequestPredicates.path("/dummypath", "/cookieregex").and(cookie("mycookie", "fo.")), http())
					.filter(new HttpbinUriResolver())
					.filter(setPath("/headers"))
					.withAttribute(MvcUtils.GATEWAY_ROUTE_ID_ATTR, "testcookiepredicate");
			// @formatter:on
		}

		@Bean
		public RouterFunction<ServerResponse> gatewayRouterFunctionsRewritePath() {
			// @formatter:off
			return route(path("/foo/**").and(host("**.rewritepath.org")), http())
					.filter(new HttpbinUriResolver())
					.filter(rewritePath("/foo/(?<segment>.*)", "/${segment}"))
					.filter(addRequestHeader("X-Test", "rewritepath"))
					.withAttribute(MvcUtils.GATEWAY_ROUTE_ID_ATTR, "testrewritepath");
			// @formatter:on
		}

		@Bean
		public RouterFunction<ServerResponse> gatewayRouterFunctionsRewritePathPost() {
			// @formatter:off
			return route("testrewritepathpost")
					.route(POST("/baz/**").and(host("**.rewritepathpost.org")), http())
					.filter(new HttpbinUriResolver())
					.filter(rewritePath("/baz/(?<segment>.*)", "/${segment}"))
					.filter(addRequestHeader("X-Test", "rewritepathpost"))
					.build();
			// @formatter:on
		}

		@Bean
		public RouterFunction<ServerResponse> gatewayRouterFunctionsRewritePathPostLocal() {
			// @formatter:off
			return route("testrewritepathpostlocal")
					.route(POST("/baz/**").and(host("**.rewritepathpostlocal.org")), http())
					.before(new LocalServerPortUriResolver())
					.filter(rewritePath("/baz/(?<segment>.*)", "/test/${segment}"))
					.filter(addRequestHeader("X-Test", "rewritepathpostlocal"))
					.build();
			// @formatter:on
		}

		@Bean
		public RouterFunction<ServerResponse> gatewayRouterFunctionsForwardedHeaders() {
			// @formatter:off
			return route(path("/headers").and(header("test", "forwarded")), http())
					.filter(new HttpbinUriResolver())
					.filter(addRequestHeader("X-Test", "forwarded"))
					.withAttribute(MvcUtils.GATEWAY_ROUTE_ID_ATTR, "testforwardedheaders");
			// @formatter:on
		}

		@Bean
		public RouterFunction<ServerResponse> gatewayRouterFunctionsForm() {
			// @formatter:off
			return route("testform")
					.POST("/post", host("**.testform.org"), http())
					.before(new LocalServerPortUriResolver())
					.filter(prefixPath("/test"))
					.filter(addRequestHeader("X-Test", "form"))
					.build();
			// @formatter:on
		}

		@Bean
		public RouterFunction<ServerResponse> gatewayRouterFunctionsFormUrlEncoded() {
			// @formatter:off
			return route("testform")
					.POST("/post", header("test", "formurlencoded"), http())
					.before(new HttpbinUriResolver())
					.filter(addRequestHeader("X-Test", "formurlencoded"))
					.build();
			// @formatter:on
		}

		@Bean
		public RouterFunction<ServerResponse> gatewayRouterFunctionsRedirectTo() {
			// @formatter:off
			return route(path("/anything/redirect"), http())
					.filter(redirectTo(HttpStatus.MOVED_PERMANENTLY, URI.create("https://exampleredirect.com")))
					.withAttribute(MvcUtils.GATEWAY_ROUTE_ID_ATTR, "testredirectto");
			// @formatter:on
		}

		@Bean
		public RouterFunction<ServerResponse> gatewayRouterFunctionsRemoveRequestHeader() {
			// @formatter:off
			return route(path("/anything/removerequestheader"), http())
					.filter(new HttpbinUriResolver())
					.filter(removeRequestHeader("X-Request-Foo"))
					.withAttribute(MvcUtils.GATEWAY_ROUTE_ID_ATTR, "testremoverequestheader");
			// @formatter:on
		}

		@Bean
		public RouterFunction<ServerResponse> gatewayRouterFunctionsSetRequestHeader() {
			// @formatter:off
			return route("testsetrequestheader")
					.GET("/headers", header("test", "setrequestheader"), http())
					.filter(new HttpbinUriResolver())
					.filter(addRequestHeader("X-Test", "value1"))
					.filter(setRequestHeader("X-Test", "value2"))
					.build();
			// @formatter:on
		}

		@Bean
		public RouterFunction<ServerResponse> gatewayRouterFunctionsSetRequestHostHeader() {
			// @formatter:off
			return route("testsetrequesthostheader")
					.route(host("**.setrequesthostheader.org"), http())
					.filter(new HttpbinUriResolver())
					.filter(setRequestHostHeader("otherhost.io"))
					.build();
			// @formatter:on
		}

		@Bean
		public RouterFunction<ServerResponse> gatewayRouterFunctionsSetResponseHeader() {
			// @formatter:off
			return route("testsetresponseheader")
					.GET("/anything/setresponseheader", header("test", "setresponseheader"), http())
					.before(new HttpbinUriResolver())
					// reverse order for "post" filters
					.after(setResponseHeader("X-Test", "value2"))
					.after(addResponseHeader("X-Test", "value1"))
					.build();
			// @formatter:on
		}

		@Bean
		public RouterFunction<ServerResponse> gatewayRouterFunctionsNested() {
			// @formatter:off
			return route()
					.nest(path("/anything/nested").and(header("test", "nested")), () ->
						route("nested1").GET("/nested1", http())
							.before(new HttpbinUriResolver())
							.filter(addRequestHeader("X-Test", "nested1"))
						.build().and(
							route("nested2").GET("/nested2", http())
							.before(new HttpbinUriResolver())
							.filter(addRequestHeader("X-Test", "nested2")).build()))
					.build();
			// @formatter:on
		}

		@Bean
		public RouterFunction<ServerResponse> gatewayRouterFunctionsRemoveRequestParam() {
			// @formatter:off
			return route("removerequestparam")
					.route(header("test", "removerequestparam"), http())
					.filter(new HttpbinUriResolver())
					.before(removeRequestParameter("foo"))
					.build();
			// @formatter:on
		}

		@Bean
		public RouterFunction<ServerResponse> gatewayRouterFunctionsRemoveRequestParamPost() {
			// @formatter:off
			return route("removerequestparampost")
					.route(host("www.removerequestparampost.org").and(POST("/post")), http())
					.filter(new HttpbinUriResolver())
					.before(removeRequestParameter("foo"))
					.build();
			// @formatter:on
		}

		@Bean
		public RouterFunction<ServerResponse> gatewayRouterFunctionsRemoveResponseHeader() {
			// @formatter:off
			return route("testremoveresponseheader")
					.GET("/anything/removeresponseheader", header("test", "removeresponseheader"), http())
					.before(new HttpbinUriResolver())
					// reverse order for "post" filters
					.after(removeResponseHeader("X-Test"))
					.after(addResponseHeader("X-Test", "value1"))
					.build();
			// @formatter:on
		}

		@Bean
		public RouterFunction<ServerResponse> gatewayRouterFunctionsRewriteResponseHeader() {
			// @formatter:off
			return route("testrewriteresponseheader")
					.route(header("test", "rewriteresponseheader"), http())
					.before(new HttpbinUriResolver())
					// reverse order for "post" filters
					.after(rewriteResponseHeader("X-Request-Foo", "password=[^&]+", "password=***"))
					.after(addResponseHeader("X-Request-Foo", "/42?user=ford&password=omg!what&flag=true"))
					.build();
			// @formatter:on
		}

		@Bean
		public RouterFunction<ServerResponse> gatewayRouterFunctionsRequestSize() {
			// @formatter:off
			return route("requestsize")
					.POST(path("/post").and(header("test", "requestsize")), http())
					.before(new HttpbinUriResolver())
					.before(requestSize("5B"))
					.build();
			// @formatter:on
		}

		@Bean
		public RouterFunction<ServerResponse> gatewayRouterFunctionsRequestHeaderSize() {
			// @formatter:off
			return route("requestheadersize")
					.GET(path("/headers").and(header("test", "requestheadersize")), http())
					.before(new HttpbinUriResolver())
					.before(requestHeaderSize("79B"))
					.build();
			// @formatter:on
		}

		@Bean
		public RouterFunction<ServerResponse> gatewayRouterFunctionsRequestHeaderToRequestUri() {
			// @formatter:off
			return route("requestheadertorequesturi")
					.route(cloudFoundryRouteService().and(host("**.requestheadertorequesturi.org")), http())
					//.before(new HttpbinUriResolver()) NO URI RESOLVER!
					.before(requestHeaderToRequestUri("X-CF-Forwarded-Url"))
					.filter(setPath("/hello"))
					.build();
			// @formatter:on
		}

		@Bean
		public RouterFunction<ServerResponse> gatewayRouterFunctionsMapRequestHeader() {
			// @formatter:off
			return route("testmaprequestheader")
					.GET("/anything/maprequestheader", http())
					.before(new HttpbinUriResolver())
					.before(mapRequestHeader("X-Foo", "X-Bar"))
					.build();
			// @formatter:on
		}

		@Bean
		public RouterFunction<ServerResponse> gatewayRouterFunctionsAddRequestHeadersIfNotPresent() {
			// @formatter:off
			return route("testaddrequestheadersifnotpresent")
					.route(GET("/headers").and(host("{sub}.addrequestheadersifnotpresent.org")), http())
					.filter(new HttpbinUriResolver())
					// normally use BeforeFilterFunctions version, but wanted to test parsing for config
					.filter(addRequestHeadersIfNotPresent("X-Request-Acme:ValueX, X-Request-Acme:ValueY,X-Request-Acme:ValueZ, X-Request-Acme:{sub},X-Request-Beta:Value2"))
					.build();
			// @formatter:on
		}

		@Bean
		public RouterFunction<ServerResponse> gatewayRouterFunctionsDedupeResponseHeader() {
			// @formatter:off
			return route("testdeduperesponseheader")
					.route(GET("/headers").and(host("{sub}.deduperesponseheader.org")), http())
					.filter(new HttpbinUriResolver())
					.after(dedupeResponseHeader("Access-Control-Allow-Credentials Access-Control-Allow-Origin", RETAIN_FIRST))
					.after(dedupeResponseHeader("Scout-Cookie", RETAIN_LAST))
					.after(dedupeResponseHeader("Next-Week-Lottery-Numbers", RETAIN_UNIQUE))
					.after(addResponseHeader("Access-Control-Allow-Credentials", "false"))
					.after(setResponseHeader("Access-Control-Allow-Credentials", "true"))
					.after(addResponseHeader("Access-Control-Allow-Origin", "*"))
					.after(setResponseHeader("Access-Control-Allow-Origin", "https://example.org"))
					.after(addResponseHeader("Scout-Cookie", "S'mores"))
					.after(setResponseHeader("Scout-Cookie", "Thin Mints"))
					.after(addResponseHeader("Next-Week-Lottery-Numbers", "42"))
					.after(addResponseHeader("Next-Week-Lottery-Numbers", "2"))
					.after(addResponseHeader("Next-Week-Lottery-Numbers", "2"))
					.after(setResponseHeader("Next-Week-Lottery-Numbers", "4"))
					.build();
			// @formatter:on
		}

		@Bean
		public RouterFunction<ServerResponse> gatewayRouterFunctionsRewriteLocationResponseHeader() {
			// @formatter:off
			return route("testrewritelocationresponseheader")
					.GET("/anything/rewritelocationresponseheader", host("**.rewritelocationresponseheader.org"), http())
					.before(new HttpbinUriResolver())
					// reverse order for "post" filters
					.after(rewriteLocationResponseHeader())
					//.after(rewriteLocationResponseHeader(config -> config.setLocationHeaderName("Location").setStripVersion(RewriteLocationResponseHeaderFilterFunctions.StripVersion.AS_IN_REQUEST)))
					.after(addResponseHeader("Location", "https://backend.org:443/v1/some/object/id"))
					.build();
			// @formatter:on
		}

		@Bean
		public RouterFunction<ServerResponse> gatewayRouterFunctionsReadBodyPredicate() {
			// @formatter:off
			return route("testreadbodypredicate")
					.POST("/events", readBody(Event.class, eventPredicate("message")), http())
					.before(new LocalServerPortUriResolver())
					.filter(setPath("/do/events"))
					.before(adaptCachedBody())
					.build().and(
				route("testreadbodypredicate2")
					.POST("/events", readBody(Event.class, eventPredicate("message.channel")), http())
					.before(new LocalServerPortUriResolver())
					.filter(setPath("/do/events/channel"))
					.before(adaptCachedBody())
					.build());
			// @formatter:on
		}

		@Bean
		public RouterFunction<ServerResponse> gatewayRouterFunctionsModifyRequestBody() {
			// @formatter:off
			return route("testmodifyrequestbodystring")
					.POST("/post", host("**.modifyrequestbodystring.org"), http())
					.before(new HttpbinUriResolver())
					.before(modifyRequestBody(String.class, String.class, null, (request, s) -> s.toUpperCase(Locale.ROOT) + s.toUpperCase(Locale.ROOT)))
					.build().and(
				route("testmodifyrequestbodyobject")
					.POST("/post", host("**.modifyrequestbodyobject.org"), http())
					.before(new HttpbinUriResolver())
					.before(modifyRequestBody(String.class, Hello.class, MediaType.APPLICATION_JSON_VALUE, (request, s) -> new Hello(s.toUpperCase(Locale.ROOT))))
					.build());
			// @formatter:on
		}

		@Bean
		public RouterFunction<ServerResponse> gatewayRouterFunctionsForward() {
			// @formatter:off
			return route("testforward")
					.GET("/doforward", forward("/hello"))
					.before(new LocalServerPortUriResolver())
					.build();
			// @formatter:on
		}

		@Bean
		public RouterFunction<ServerResponse> gatewayRouterFunctionsForwardNon200Status() {
			// @formatter:off
			return route("testforwardnon200status")
					.GET("/doforward2", forward("/hello2"))
					.before(new LocalServerPortUriResolver())
					.build();
			// @formatter:on
		}

		@Bean
		public RouterFunction<ServerResponse> gatewayRouterFunctionsQuery() {
			// @formatter:off
			return route("testqueryparam")
					.route(query("foo", "bar"), http())
					.before(new HttpbinUriResolver())
					.build();
			// @formatter:on
		}

		@Bean
		public RouterFunction<ServerResponse> gatewayRouterFunctionsReadResponseBody() {
			// @formatter:off
			return route("testClientResponseBodyAttribute")
					.GET("/anything/readresponsebody", http())
					.before(new HttpbinUriResolver())
					.after((request, response) -> {
						Object o = request.attributes().get(MvcUtils.CLIENT_RESPONSE_INPUT_STREAM_ATTR);
						if (o instanceof InputStream) {
							try {
								byte[] bytes = StreamUtils.copyToByteArray((InputStream) o);
								String s = new String(bytes, StandardCharsets.UTF_8);
								String replace = s.replace("fooval", "FOOVAL");
								ByteArrayInputStream bais = new ByteArrayInputStream(replace.getBytes());
								request.attributes().put(MvcUtils.CLIENT_RESPONSE_INPUT_STREAM_ATTR, bais);
							}
							catch (IOException e) {
								throw new RuntimeException(e);
							}
						}
						return response;
					})
					.build();
			// @formatter:on
		}

		@Bean
		public FilterRegistrationBean myFilter() {
			FilterRegistrationBean<MyFilter> reg = new FilterRegistrationBean<>(new MyFilter());
			return reg;
		}

		private Predicate<Event> eventPredicate(String foo) {
			return new Predicate<>() {
				@Override
				public boolean test(Event event) {
					return event.foo().equals(foo);
				}

				@Override
				public String toString() {
					return "Event.foo == " + foo;
				}
			};
		}

	}

	private static class MyFilter implements Filter, Ordered {

		@Override
		public int getOrder() {
			return FormFilter.FORM_FILTER_ORDER - 1;
		}

		@Override
		public void doFilter(ServletRequest request, ServletResponse response, FilterChain filterChain)
				throws IOException, ServletException {
			if (isFormPost((HttpServletRequest) request)) {
				// test for formUrlencodedWorks and
				// https://github.com/spring-cloud/spring-cloud-gateway/issues/3244
				assertThat(request.getParameter("foo")).isEqualTo("fooquery");
				assertThat(request.getParameter("foo")).isEqualTo("fooquery");
			}
			filterChain.doFilter(request, response);

			if (isFormPost((HttpServletRequest) request)) {
				assertThat(request.getParameter("foo")).isEqualTo("fooquery");
				assertThat(request.getParameter("foo")).isEqualTo("fooquery");
			}
		}

		static boolean isFormPost(HttpServletRequest request) {
			String contentType = request.getContentType();
			return (contentType != null && contentType.contains(MediaType.APPLICATION_FORM_URLENCODED_VALUE)
					&& HttpMethod.POST.matches(request.getMethod()));
		}

	}

	protected record Hello(String message) {

	}

	protected record Event(String foo, String bar) {

	}

	@RestController
	protected static class EventController {

		@PostMapping(path = "/do/events", produces = MediaType.APPLICATION_JSON_VALUE)
		public ResponseEntity<Event> messageEvents(@RequestBody Event e) {
			return ResponseEntity.ok().header("X-Foo", e.foo()).body(e);
		}

		@PostMapping(path = "/do/events/channel", produces = MediaType.APPLICATION_JSON_VALUE)
		public ResponseEntity<Event> messageChannelEvents(@RequestBody Event e) {
			return ResponseEntity.ok().header("X-Channel-Foo", e.foo()).body(e);
		}

	}

	@RestController
	protected static class RetryController {

		Log log = LogFactory.getLog(getClass());

		ConcurrentHashMap<String, AtomicInteger> map = new ConcurrentHashMap<>();

		@GetMapping("/do/retry")
		public ResponseEntity<String> retry(@RequestParam("key") String key,
				@RequestParam(name = "count", defaultValue = "3") int count,
				@RequestParam(name = "failStatus", required = false) Integer failStatus) {
			AtomicInteger num = getCount(key);
			int i = num.incrementAndGet();
			log.warn("Retry count: " + i);
			String body = String.valueOf(i);
			if (i < count) {
				HttpStatus httpStatus = HttpStatus.INTERNAL_SERVER_ERROR;
				if (failStatus != null) {
					httpStatus = HttpStatus.resolve(failStatus);
				}
				return ResponseEntity.status(httpStatus).header("X-Retry-Count", body).body("temporarily broken");
			}
			return ResponseEntity.status(HttpStatus.OK).header("X-Retry-Count", body).body(body);
		}

		AtomicInteger getCount(String key) {
			return map.computeIfAbsent(key, s -> new AtomicInteger());
		}

	}

	protected static class TestHandler implements HandlerFunction<ServerResponse> {

		@Override
		public ServerResponse handle(ServerRequest request) {
			return ServerResponse.ok().body("Hello");
		}

		@Override
		public String toString() {
			return "TestHandler Hello";
		}

	}

}
