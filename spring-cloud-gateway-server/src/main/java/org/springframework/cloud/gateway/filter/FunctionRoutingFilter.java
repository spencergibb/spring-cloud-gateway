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
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.reactivestreams.Publisher;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import org.springframework.cloud.function.context.FunctionCatalog;
import org.springframework.cloud.function.context.catalog.SimpleFunctionRegistry.FunctionInvocationWrapper;
import org.springframework.cloud.gateway.support.NotFoundException;
import org.springframework.core.Ordered;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageHeaders;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.util.CollectionUtils;
import org.springframework.util.MimeType;
import org.springframework.web.server.ServerWebExchange;

import static org.springframework.cloud.gateway.support.ServerWebExchangeUtils.GATEWAY_REQUEST_URL_ATTR;
import static org.springframework.cloud.gateway.support.ServerWebExchangeUtils.isAlreadyRouted;
import static org.springframework.cloud.gateway.support.ServerWebExchangeUtils.setAlreadyRouted;

/**
 * @author Spencer Gibb
 */
public class FunctionRoutingFilter implements GlobalFilter, Ordered {

	private static Log logger = LogFactory.getLog(FunctionRoutingFilter.class);

	private final FunctionCatalog functionCatalog;

	public FunctionRoutingFilter(FunctionCatalog functionCatalog) {
		this.functionCatalog = functionCatalog;
	}

	@Override
	public int getOrder() {
		return Ordered.LOWEST_PRECEDENCE;
	}

	@Override
	public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
		URI requestUrl = exchange.getRequiredAttribute(GATEWAY_REQUEST_URL_ATTR);
		String scheme = requestUrl.getScheme();

		if (isAlreadyRouted(exchange) || !"fn".equals(scheme)) {
			return chain.filter(exchange);
		}
		setAlreadyRouted(exchange);

		FunctionInvocationWrapper function = functionCatalog.lookup(requestUrl.getSchemeSpecificPart(), exchange.getRequest().getHeaders().getAccept().stream().map(MimeType::toString).toArray(String[]::new));
		if (function == null) {
			return Mono.error(new NotFoundException("No route for uri " + requestUrl.toString()));
		}

		return chain.filter(exchange);
	}


	@SuppressWarnings({ "rawtypes", "unchecked" })
	public static Publisher<?> processRequest(ServerHttpRequest request, FunctionInvocationWrapper function, Object argument, boolean eventStream, List<String> ignoredHeaders, List<String> requestOnlyHeaders) {
		if (argument == null) {
			argument = "";
		}

		if (function == null) {
			return Mono.just(ResponseEntity.notFound().build());
		}

		HttpHeaders headers = request.getHeaders();

		Message<?> inputMessage = null;


		MessageBuilder builder = MessageBuilder.withPayload(argument);
		if (!CollectionUtils.isEmpty(request.getQueryParams())) {
			builder = builder.setHeader(HeaderUtils.HTTP_REQUEST_PARAM, request.getQueryParams().toSingleValueMap());
		}
		inputMessage = builder.copyHeaders(headers.toSingleValueMap()).build();

		if (function.isRoutingFunction()) {
			function.setSkipOutputConversion(true);
		}

		Object result = function.apply(inputMessage);
		if (function.isConsumer()) {
			if (result instanceof Publisher) {
				Mono.from((Publisher) result).subscribe();
			}
			return "DELETE".equals(request.getMethod()) ?
					Mono.empty() : Mono.just(ResponseEntity.accepted().headers(HeaderUtils.sanitize(headers, ignoredHeaders, requestOnlyHeaders)).build());
		}

		ResponseEntity.BodyBuilder responseOkBuilder = ResponseEntity.ok().headers(HeaderUtils.sanitize(headers, ignoredHeaders, requestOnlyHeaders));

		Publisher pResult;
		if (result instanceof Publisher) {
			pResult = (Publisher) result;
			if (eventStream) {
				return Flux.from(pResult);
			}

			if (pResult instanceof Flux) {
				pResult = ((Flux) pResult).onErrorContinue((e, v) -> {
					logger.error("Failed to process value: " + v, (Throwable) e);
				}).collectList();
			}
			pResult = Mono.from(pResult);
		}
		else {
			pResult = Mono.just(result);
		}

		return Mono.from(pResult).map(v -> {
			if (v instanceof Iterable i) {
				List aggregatedResult = (List) StreamSupport.stream(i.spliterator(), false).map(m -> {
					return m instanceof Message ? processMessage(responseOkBuilder, (Message<?>) m, ignoredHeaders) : m;
				}).collect(Collectors.toList());
				return responseOkBuilder.header("content-type", "application/json").body(aggregatedResult);
			}
			else if (v instanceof Message) {
				return responseOkBuilder.body(processMessage(responseOkBuilder, (Message<?>) v, ignoredHeaders));
			}
			else {
				return responseOkBuilder.body(v);
			}
		});
	}

	private static Object processMessage(ResponseEntity.BodyBuilder responseOkBuilder, Message<?> message, List<String> ignoredHeaders) {
		responseOkBuilder.headers(HeaderUtils.fromMessage(message.getHeaders(), ignoredHeaders));
		return message.getPayload();
	}


	/**
	 * @author Dave Syer
	 * @author Oleg Zhurakousky
	 */
	private static final class HeaderUtils {

		/**
		 * Message Header name which contains HTTP request parameters.
		 */
		public static final String HTTP_REQUEST_PARAM = "http_request_param";

		private static HttpHeaders IGNORED = new HttpHeaders();

		private static HttpHeaders REQUEST_ONLY = new HttpHeaders();

		static {
			IGNORED.add(MessageHeaders.ID, "");
			IGNORED.add(HttpHeaders.CONTENT_LENGTH, "0");
			// Headers that would typically be added by a downstream client
			REQUEST_ONLY.add(HttpHeaders.ACCEPT, "");
			REQUEST_ONLY.add(HttpHeaders.CONTENT_LENGTH, "");
			REQUEST_ONLY.add(HttpHeaders.CONTENT_TYPE, "");
			REQUEST_ONLY.add(HttpHeaders.HOST, "");
		}

		private HeaderUtils() {
			throw new IllegalStateException("Can't instantiate a utility class");
		}

		public static HttpHeaders fromMessage(MessageHeaders headers, List<String> ignoredHeders) {
			HttpHeaders result = new HttpHeaders();
			for (String name : headers.keySet()) {
				Object value = headers.get(name);
				name = name.toLowerCase(Locale.ROOT);
				if (!IGNORED.containsKey(name) && !ignoredHeders.contains(name)) {
					Collection<?> values = multi(value);
					for (Object object : values) {
						result.set(name, object.toString());
					}
				}
			}
			return result;
		}

		@SuppressWarnings("unchecked")
		public static HttpHeaders fromMessage(MessageHeaders headers) {
			return fromMessage(headers, Collections.EMPTY_LIST);
		}


		public static HttpHeaders sanitize(HttpHeaders request, List<String> ignoredHeders, List<String> requestOnlyHeaders) {
			HttpHeaders result = new HttpHeaders();
			for (String name : request.keySet()) {
				List<String> value = request.get(name);
				name = name.toLowerCase(Locale.ROOT);
				if (!IGNORED.containsKey(name) && !REQUEST_ONLY.containsKey(name) && !ignoredHeders.contains(name) && !requestOnlyHeaders.contains(name)) {
					result.put(name, value);
				}
			}
			return result;
		}

		@SuppressWarnings("unchecked")
		public static HttpHeaders sanitize(HttpHeaders request) {
			return sanitize(request, Collections.EMPTY_LIST, Collections.EMPTY_LIST);
		}

		public static MessageHeaders fromHttp(HttpHeaders headers) {
			Map<String, Object> map = new LinkedHashMap<>();
			for (String name : headers.keySet()) {
				Collection<?> values = multi(headers.get(name));
				name = name.toLowerCase(Locale.ROOT);
				Object value = values == null ? null
						: (values.size() == 1 ? values.iterator().next() : values);
				if (name.toLowerCase(Locale.ROOT).equals(HttpHeaders.CONTENT_TYPE.toLowerCase(Locale.ROOT))) {
					name = MessageHeaders.CONTENT_TYPE;
				}
				map.put(name, value);
			}
			return new MessageHeaders(map);
		}

		private static Collection<?> multi(Object value) {
			return value instanceof Collection ? (Collection<?>) value : Arrays.asList(value);
		}

	}
