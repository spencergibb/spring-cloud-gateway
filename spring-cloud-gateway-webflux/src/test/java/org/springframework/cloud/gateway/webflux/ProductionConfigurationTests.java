/*
 * Copyright 2016-2019 the original author or authors.
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

package org.springframework.cloud.gateway.webflux;

import java.net.URI;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.cloud.gateway.webflux.ProductionConfigurationTests.TestApplication;
import org.springframework.cloud.gateway.webflux.ProductionConfigurationTests.TestApplication.Bar;
import org.springframework.cloud.gateway.webflux.ProductionConfigurationTests.TestApplication.Foo;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.RequestEntity;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.UriComponentsBuilder;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = { "spring.cloud.gateway.proxy.skipped=host" }, webEnvironment = WebEnvironment.RANDOM_PORT)
@ContextConfiguration(classes = TestApplication.class)
@DirtiesContext
@ExtendWith(OutputCaptureExtension.class)
public class ProductionConfigurationTests {

	@Autowired
	private TestRestTemplate rest;

	@Autowired
	private TestApplication application;

	@LocalServerPort
	private int port;

	@BeforeEach
	public void init() throws Exception {
		application.setHome(new URI("http://localhost:" + port));
		rest.getRestTemplate().setRequestFactory(new SimpleClientHttpRequestFactory());
	}

	@Test
	public void get() throws Exception {
		assertThat(rest.getForObject("/proxy/0", Foo.class).getName()).isEqualTo("bye");
	}

	@Test
	public void forwardGet() throws Exception {
		assertThat(rest.getForObject("/proxy/forward/0", Foo.class).getName()).isEqualTo("bye");
	}

	@Test
	public void path() throws Exception {
		assertThat(rest.getForObject("/proxy/path/1", Foo.class).getName()).isEqualTo("foo");
	}

	@Test
	public void resource() throws Exception {
		assertThat(rest.getForObject("/proxy/html/test.html", String.class)).contains("<body>Test");
	}

	@Test
	public void resourceWithNoType() throws Exception {
		assertThat(rest.getForObject("/proxy/typeless/test.html", String.class)).contains("<body>Test");
	}

	@Test
	public void missing() throws Exception {
		assertThat(rest.getForEntity("/proxy/missing/0", Foo.class).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
	}

	@Test
	public void uri() throws Exception {
		assertThat(rest.getForObject("/proxy/0", Foo.class).getName()).isEqualTo("bye");
	}

	@Test
	public void post() throws Exception {
		assertThat(rest.postForObject("/proxy/0", Collections.singletonMap("name", "foo"), Bar.class).getName())
			.isEqualTo("host=localhost:" + port + ";foo");
	}

	@Test
	public void postJsonWithWhitespace() {
		var json = """
				{
					"foo": "bar"
				}""";

		var headers = new HttpHeaders();
		headers.setContentType(MediaType.APPLICATION_JSON);
		headers.setContentLength(json.length());
		var request = new HttpEntity<>(json, headers);
		assertThat(rest.postForEntity("/proxy/checkContentLength", request, Void.class).getStatusCode())
			.isEqualTo(HttpStatus.OK);
	}

	@Test
	public void forwardPost() throws Exception {
		assertThat(rest.postForObject("/proxy/forward/0", Collections.singletonMap("name", "foo"), Bar.class).getName())
			.isEqualTo("host=localhost:" + port + ";foo");
	}

	@Test
	public void list() throws Exception {
		ResponseEntity<List<Bar>> result = rest.exchange(
				RequestEntity.post(rest.getRestTemplate().getUriTemplateHandler().expand("/proxy"))
					.contentType(MediaType.APPLICATION_JSON)
					.body(Collections.singletonList(Collections.singletonMap("name", "foo"))),
				new ParameterizedTypeReference<List<Bar>>() {
				});
		assertThat(result.getBody().iterator().next().getName()).isEqualTo("host=localhost:" + port + ";foo");
	}

	@Test
	public void bodyless() throws Exception {
		assertThat(rest.postForObject("/proxy/0", Collections.singletonMap("name", "foo"), Bar.class).getName())
			.isEqualTo("host=localhost:" + port + ";foo");
	}

	@Test
	public void entity() throws Exception {
		assertThat(
				rest.exchange(
						RequestEntity.post(rest.getRestTemplate().getUriTemplateHandler().expand("/proxy/entity"))
							.body(Collections.singletonMap("name", "foo")),
						new ParameterizedTypeReference<List<Bar>>() {
						})
					.getBody()
					.iterator()
					.next()
					.getName())
			.isEqualTo("host=localhost:" + port + ";foo");
	}

	@Test
	public void entityWithType() throws Exception {
		assertThat(
				rest.exchange(
						RequestEntity.post(rest.getRestTemplate().getUriTemplateHandler().expand("/proxy/type"))
							.body(Collections.singletonMap("name", "foo")),
						new ParameterizedTypeReference<List<Bar>>() {
						})
					.getBody()
					.iterator()
					.next()
					.getName())
			.isEqualTo("host=localhost:" + port + ";foo");
	}

	@Test
	public void single() throws Exception {
		assertThat(rest.postForObject("/proxy/single", Collections.singletonMap("name", "foobar"), Bar.class).getName())
			.isEqualTo("host=localhost:" + port + ";foobar");
	}

	@Test
	public void converter() throws Exception {
		assertThat(
				rest.postForObject("/proxy/converter", Collections.singletonMap("name", "foobar"), Bar.class).getName())
			.isEqualTo("host=localhost:" + port + ";foobar");
	}

	@Test
	@SuppressWarnings({ "Duplicates", "unchecked" })
	public void testSensitiveHeadersOverride() throws Exception {
		Map<String, List<String>> headers = rest
			.exchange(RequestEntity.get(rest.getRestTemplate().getUriTemplateHandler().expand("/proxy/headers"))
				.header("foo", "bar")
				.header("abc", "xyz")
				.header("cookie", "monster")
				.build(), Map.class)
			.getBody();
		assertThat(headers).doesNotContainKey("foo").doesNotContainKey("hello").containsKeys("bar", "abc");

		assertThat(headers.get("cookie")).containsOnly("monster");
	}

	@Test
	public void testSensitiveHeadersDefault() throws Exception {
		Map<String, List<String>> headers = rest
			.exchange(RequestEntity
				.get(rest.getRestTemplate().getUriTemplateHandler().expand("/proxy/sensitive-headers-default"))
				.header("cookie", "monster")
				.build(), Map.class)
			.getBody();

		assertThat(headers).doesNotContainKey("cookie");
	}

	@Test
	@SuppressWarnings({ "Duplicates", "unchecked" })
	public void headers() throws Exception {
		Map<String, List<String>> headers = rest
			.exchange(RequestEntity.get(rest.getRestTemplate().getUriTemplateHandler().expand("/proxy/headers"))
				.header("foo", "bar")
				.header("abc", "xyz")
				.header("baz", "fob")
				.build(), Map.class)
			.getBody();
		assertThat(headers).doesNotContainKey("foo").doesNotContainKey("hello").containsKeys("bar", "abc");

		assertThat(headers.get("bar")).containsOnly("hello");
		assertThat(headers.get("abc")).containsOnly("123");
		assertThat(headers.get("baz")).containsOnly("fob");
	}

	@Test
	public void forwardedHeaderUsesHost() throws Exception {
		Map<String, List<String>> headers = rest
			.exchange(RequestEntity.get(rest.getRestTemplate().getUriTemplateHandler().expand("/proxy/headers"))
				.header("host", "foo:1234")
				.build(), Map.class)
			.getBody();

		assertThat(headers).containsKey("forwarded");
		assertThat(headers.get("forwarded").size()).isEqualTo(1);
		assertThat(headers.get("forwarded").get(0)).isEqualTo("host=localhost:" + port);
	}

	@Test
	public void deleteWithoutBody() throws Exception {
		ResponseEntity<Void> deleteResponse = rest.exchange("/proxy/{id}/no-body", HttpMethod.DELETE, null, Void.TYPE,
				Collections.singletonMap("id", "123"));
		assertThat(deleteResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
	}

	@Test
	public void deleteWithBody() throws Exception {
		Foo foo = new Foo("to-be-deleted");
		ParameterizedTypeReference<Map<String, Foo>> returnType = new ParameterizedTypeReference<Map<String, Foo>>() {
		};
		ResponseEntity<Map<String, Foo>> deleteResponse = rest.exchange("/proxy/{id}", HttpMethod.DELETE,
				new HttpEntity<Foo>(foo), returnType, Collections.singletonMap("id", "123"));
		assertThat(deleteResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(deleteResponse.getBody().get("deleted")).usingRecursiveComparison().isEqualTo(foo);
	}

	@Test
	void logsArtifactDeprecatedWarning(CapturedOutput output) {
		assertThat(output).contains("spring-cloud-gateway-webflux is deprecated");
	}

	@SpringBootApplication
	static class TestApplication {

		@Autowired
		private ProxyController controller;

		public void setHome(URI home) {
			controller.setHome(home);
		}

		@RestController
		static class ProxyController {

			private URI home;

			public void setHome(URI home) {
				this.home = home;
			}

			@GetMapping("/proxy/{id}")
			public Mono<ResponseEntity<Object>> proxyFoos(@PathVariable Integer id, ProxyExchange<Object> proxy)
					throws Exception {
				return proxy.uri(home.toString() + "/foos/" + id).get();
			}

			@GetMapping("/proxy/path/**")
			public Mono<ResponseEntity<Object>> proxyPath(ProxyExchange<Object> proxy, UriComponentsBuilder uri)
					throws Exception {
				String path = proxy.path("/proxy/path/");
				return proxy.uri(home.toString() + "/foos/" + path).get();
			}

			@GetMapping("/proxy/html/**")
			public Mono<ResponseEntity<String>> proxyHtml(ProxyExchange<String> proxy, UriComponentsBuilder uri)
					throws Exception {
				String path = proxy.path("/proxy/html");
				return proxy.uri(home.toString() + path).get();
			}

			@GetMapping("/proxy/typeless/**")
			public Mono<ResponseEntity<byte[]>> proxyTypeless(ProxyExchange<byte[]> proxy, UriComponentsBuilder uri)
					throws Exception {
				String path = proxy.path("/proxy/typeless");
				return proxy.uri(home.toString() + path).get();
			}

			@GetMapping("/proxy/missing/{id}")
			public Mono<ResponseEntity<Object>> proxyMissing(@PathVariable Integer id, ProxyExchange<Object> proxy)
					throws Exception {
				return proxy.uri(home.toString() + "/missing/" + id).get();
			}

			@GetMapping("/proxy")
			public Mono<ResponseEntity<Object>> proxyUri(ProxyExchange<Object> proxy) throws Exception {
				return proxy.uri(home.toString() + "/foos").get();
			}

			@PostMapping("/proxy/{id}")
			public Mono<ResponseEntity<Object>> proxyBars(@PathVariable Integer id,
					@RequestBody Map<String, Object> body, ProxyExchange<List<Object>> proxy) throws Exception {
				body.put("id", id);
				return proxy.uri(home.toString() + "/bars").body(Arrays.asList(body)).post(this::first);
			}

			@PostMapping("/proxy")
			public Mono<ResponseEntity<List<Object>>> barsWithNoBody(ProxyExchange<List<Object>> proxy)
					throws Exception {
				return proxy.uri(home.toString() + "/bars").post();
			}

			@PostMapping("/proxy/entity")
			public Mono<ResponseEntity<Object>> explicitEntity(@RequestBody Mono<Foo> foo, ProxyExchange<Object> proxy)
					throws Exception {
				return proxy.uri(home.toString() + "/bars").body(Flux.from(foo)).post();
			}

			@PostMapping("/proxy/type")
			public Mono<ResponseEntity<List<Bar>>> explicitEntityWithType(@RequestBody Mono<Foo> foo,
					ProxyExchange<List<Bar>> proxy) throws Exception {
				return proxy.uri(home.toString() + "/bars").body(Flux.from(foo)).post();
			}

			@PostMapping("/proxy/single")
			public Mono<ResponseEntity<Object>> implicitEntity(@RequestBody Mono<Foo> foo,
					ProxyExchange<List<Object>> proxy) throws Exception {
				return proxy.uri(home.toString() + "/bars").body(Flux.from(foo)).post(this::first);
			}

			@PostMapping("/proxy/converter")
			public Mono<ResponseEntity<Bar>> implicitEntityWithConverter(@RequestBody Foo foo,
					ProxyExchange<List<Bar>> proxy) throws Exception {
				return proxy.uri(home.toString() + "/bars")
					.body(Arrays.asList(foo))
					.post(response -> ResponseEntity.status(response.getStatusCode())
						.headers(response.getHeaders())
						.body(response.getBody().iterator().next()));
			}

			@GetMapping("/proxy/headers")
			public Mono<ResponseEntity<Map<String, List<String>>>> headers(
					ProxyExchange<Map<String, List<String>>> proxy) {
				proxy.excluded("foo", "hello");
				proxy.header("bar", "hello");
				proxy.header("abc", "123");
				proxy.header("hello", "world");
				return proxy.uri(home.toString() + "/headers").get();
			}

			@GetMapping("/proxy/sensitive-headers-default")
			public Mono<ResponseEntity<Map<String, List<String>>>> defaultSensitiveHeaders(
					ProxyExchange<Map<String, List<String>>> proxy) {
				proxy.header("bar", "hello");
				proxy.header("abc", "123");
				proxy.header("hello", "world");
				return proxy.uri(home.toString() + "/headers").get();
			}

			@PostMapping("/proxy/checkContentLength")
			public Mono<ResponseEntity<byte[]>> checkContentLength(ProxyExchange<byte[]> proxy) {
				return proxy.uri(home.toString() + "/checkContentLength").post();
			}

			private <T> ResponseEntity<T> first(ResponseEntity<List<T>> response) {
				return ResponseEntity.status(response.getStatusCode())
					.headers(response.getHeaders())
					.body(response.getBody().iterator().next());
			}

			@GetMapping("/proxy/forward/{id}")
			public Mono<ResponseEntity<Object>> proxyForwardFoos(@PathVariable Integer id, ProxyExchange<Object> proxy)
					throws Exception {
				return proxy.uri(home.toString() + "/foos/" + id).forward();
			}

			@PostMapping("/proxy/forward/{id}")
			public Mono<ResponseEntity<Object>> proxyForwardBars(@PathVariable Integer id,
					@RequestBody Map<String, Object> body, ProxyExchange<List<Object>> proxy) throws Exception {
				body.put("id", id);
				return proxy.uri(home.toString() + "/bars").body(Arrays.asList(body)).forward(this::first);
			}

			@DeleteMapping("/proxy/{id}/no-body")
			public Mono<ResponseEntity<Object>> deleteWithoutBody(@PathVariable Integer id, ProxyExchange<Object> proxy)
					throws Exception {
				return proxy.uri(home.toString() + "/foos/" + id + "/no-body").delete();
			}

			@DeleteMapping("/proxy/{id}")
			public Mono<ResponseEntity<Object>> deleteWithBody(@PathVariable Integer id, @RequestBody Foo foo,
					ProxyExchange<Object> proxy) throws Exception {
				return proxy.uri(home.toString() + "/foos/" + id)
					.body(foo)
					.delete(response -> ResponseEntity.status(response.getStatusCode())
						.headers(response.getHeaders())
						.body(response.getBody()));
			}

		}

		@RestController
		static class TestController {

			@GetMapping("/foos")
			public List<Foo> foos() {
				return Arrays.asList(new Foo("hello"));
			}

			@GetMapping("/foos/{id}")
			public Foo foo(@PathVariable Integer id, @RequestHeader HttpHeaders headers) {
				String custom = headers.getFirst("X-Custom");
				return new Foo(id == 1 ? "foo" : custom != null ? custom : "bye");
			}

			@PostMapping("/bars")
			public List<Bar> bars(@RequestBody List<Foo> foos, @RequestHeader HttpHeaders headers) {
				String custom = headers.getFirst("X-Custom");
				custom = custom == null ? "" : custom;
				custom = headers.getFirst("forwarded") == null ? custom : headers.getFirst("forwarded") + ";" + custom;
				return Arrays.asList(new Bar(custom + foos.iterator().next().getName()));
			}

			@PostMapping("/checkContentLength")
			public ResponseEntity<?> checkContentLength(
					@RequestHeader(name = "Content-Length", required = false) Integer contentLength,
					@RequestBody String json) {
				if (contentLength != null && contentLength != json.length()) {
					return ResponseEntity.badRequest().build();
				}
				return ResponseEntity.ok().build();
			}

			@GetMapping("/headers")
			public Map<String, List<String>> headers(@RequestHeader HttpHeaders headers) {
				return headers;
			}

			@DeleteMapping("/foos/{id}/no-body")
			public ResponseEntity<?> deleteFoo(@PathVariable Integer id) {
				return ResponseEntity.ok().build();
			}

			@DeleteMapping("/foos/{id}")
			public ResponseEntity<?> deleteFoo(@PathVariable Integer id, @RequestBody Foo foo) {
				return ResponseEntity.ok().body(Collections.singletonMap("deleted", foo));
			}

		}

		@JsonIgnoreProperties(ignoreUnknown = true)
		static class Foo {

			private String name;

			Foo() {
			}

			Foo(String name) {
				this.name = name;
			}

			public String getName() {
				return name;
			}

			public void setName(String name) {
				this.name = name;
			}

		}

		@JsonIgnoreProperties(ignoreUnknown = true)
		static class Bar {

			private String name;

			Bar() {
			}

			Bar(String name) {
				this.name = name;
			}

			public String getName() {
				return name;
			}

			public void setName(String name) {
				this.name = name;
			}

		}

	}

}
