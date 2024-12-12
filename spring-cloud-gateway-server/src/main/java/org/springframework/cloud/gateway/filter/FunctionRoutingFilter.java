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
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.reactivestreams.Publisher;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import org.springframework.cloud.function.context.FunctionCatalog;
import org.springframework.cloud.function.context.catalog.SimpleFunctionRegistry.FunctionInvocationWrapper;
import org.springframework.cloud.gateway.filter.factory.rewrite.CachedBodyOutputMessage;
import org.springframework.cloud.gateway.filter.factory.rewrite.MessageBodyEncoder;
import org.springframework.cloud.gateway.support.BodyInserterContext;
import org.springframework.cloud.gateway.support.MessageHeaderUtils;
import org.springframework.cloud.gateway.support.NotFoundException;
import org.springframework.core.Ordered;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferFactory;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.HttpHeaders;
import org.springframework.http.codec.HttpMessageReader;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.messaging.Message;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.util.CollectionUtils;
import org.springframework.util.MimeType;
import org.springframework.web.reactive.function.BodyInserter;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.server.ServerWebExchange;

import static java.util.function.Function.identity;
import static org.springframework.cloud.gateway.support.ServerWebExchangeUtils.GATEWAY_REQUEST_URL_ATTR;
import static org.springframework.cloud.gateway.support.ServerWebExchangeUtils.isAlreadyRouted;
import static org.springframework.cloud.gateway.support.ServerWebExchangeUtils.setAlreadyRouted;

/**
 * @author Spencer Gibb
 */
public class FunctionRoutingFilter implements GlobalFilter, Ordered {

	private static Log logger = LogFactory.getLog(FunctionRoutingFilter.class);

	private final FunctionCatalog functionCatalog;

	private final List<HttpMessageReader<?>> messageReaders;

	private final Map<String, MessageBodyEncoder> messageBodyEncoders;

	public FunctionRoutingFilter(FunctionCatalog functionCatalog, List<HttpMessageReader<?>> messageReaders,
			Set<MessageBodyEncoder> messageBodyEncoders) {
		this.functionCatalog = functionCatalog;
		this.messageReaders = messageReaders;
		this.messageBodyEncoders = messageBodyEncoders.stream()
			.collect(Collectors.toMap(MessageBodyEncoder::encodingType, identity()));
	}

	@Override
	public int getOrder() {
		return RouteToRequestUrlFilter.ROUTE_TO_URL_FILTER_ORDER + 10;
	}

	@Override
	public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
		URI requestUrl = exchange.getRequiredAttribute(GATEWAY_REQUEST_URL_ATTR);
		String scheme = requestUrl.getScheme();

		if (isAlreadyRouted(exchange) || !"fn".equals(scheme)) {
			return chain.filter(exchange);
		}
		setAlreadyRouted(exchange);

		FunctionInvocationWrapper function = functionCatalog.lookup(requestUrl.getHost(),
				exchange.getRequest().getHeaders().getAccept().stream().map(MimeType::toString).toArray(String[]::new));
		if (function != null) {
			return processRequest(exchange, function, messageReaders, messageBodyEncoders).then(chain.filter(exchange));
		}

		return Mono.error(new NotFoundException("No route for uri " + requestUrl));
	}

	protected Mono<Void> processRequest(ServerWebExchange exchange, FunctionInvocationWrapper function,
			List<HttpMessageReader<?>> messageReaders, Map<String, MessageBodyEncoder> messageBodyEncoders) {
		// 1- convert request body to function input type
		// 2- call function
		// 3- convert function return to raw data for response
		ServerRequest serverRequest = ServerRequest.create(exchange, messageReaders);
		return serverRequest.bodyToMono(function.getRawInputType()).flatMap(requestBody -> {
			ServerHttpRequest request = exchange.getRequest();
			HttpHeaders headers = request.getHeaders();

			Message<?> inputMessage = null;

			MessageBuilder builder = MessageBuilder.withPayload(requestBody);
			if (!CollectionUtils.isEmpty(request.getQueryParams())) {
				builder = builder.setHeader(MessageHeaderUtils.HTTP_REQUEST_PARAM,
						request.getQueryParams().toSingleValueMap());
			}
			inputMessage = builder.copyHeaders(headers.toSingleValueMap()).build();

			if (function.isRoutingFunction()) {
				function.setSkipOutputConversion(true);
			}

			List<String> ignoredHeaders = Collections.emptyList();
			HttpHeaders newResponseHeaders = new HttpHeaders();

			Object functionResult = function.apply(inputMessage);
			if (functionResult instanceof Message message) {
				newResponseHeaders.addAll(MessageHeaderUtils.fromMessage(message.getHeaders(), ignoredHeaders));
				functionResult = message.getPayload();
			}
			Publisher result;
			if (functionResult instanceof Publisher<?> publisher) {
				// TODO: deal with eventStream
				result = publisher;
			}
			else {
				result = Mono.just(functionResult);
			}

			Class<?> outClass = byte[].class;

			BodyInserter bodyInserter = BodyInserters.fromPublisher(result, outClass);
			CachedBodyOutputMessage outputMessage = new CachedBodyOutputMessage(exchange,
					exchange.getResponse().getHeaders());

			return bodyInserter.insert(outputMessage, new BodyInserterContext()).then(Mono.defer(() -> {
				ServerHttpResponse response = exchange.getResponse();
				Mono<DataBuffer> messageBody = writeBody(response, outputMessage, outClass);
				HttpHeaders responseHeaders = response.getHeaders();
				if (!responseHeaders.containsKey(HttpHeaders.TRANSFER_ENCODING)
						|| responseHeaders.containsKey(HttpHeaders.CONTENT_LENGTH)) {
					messageBody = messageBody.doOnNext(data -> headers.setContentLength(data.readableByteCount()));
				}
				responseHeaders.addAll(newResponseHeaders);

				// TODO: deal with content type
				/*
				 * if (StringUtils.hasText(config.newContentType)) {
				 * headers.set(HttpHeaders.CONTENT_TYPE, config.newContentType); }
				 */

				// TODO: fail if isStreamingMediaType?
				return response.writeWith(messageBody);
			}));
		});
	}

	private Mono<DataBuffer> writeBody(ServerHttpResponse httpResponse, CachedBodyOutputMessage message,
			Class<?> outClass) {
		Mono<DataBuffer> response = DataBufferUtils.join(message.getBody());
		if (byte[].class.isAssignableFrom(outClass)) {
			return response;
		}

		List<String> encodingHeaders = httpResponse.getHeaders().getOrEmpty(HttpHeaders.CONTENT_ENCODING);
		for (String encoding : encodingHeaders) {
			MessageBodyEncoder encoder = messageBodyEncoders.get(encoding);
			if (encoder != null) {
				DataBufferFactory dataBufferFactory = httpResponse.bufferFactory();
				response = response.publishOn(Schedulers.parallel()).map(buffer -> {
					byte[] encodedResponse = encoder.encode(buffer);
					DataBufferUtils.release(buffer);
					return encodedResponse;
				}).map(dataBufferFactory::wrap);
				break;
			}
		}

		return response;
	}

}
