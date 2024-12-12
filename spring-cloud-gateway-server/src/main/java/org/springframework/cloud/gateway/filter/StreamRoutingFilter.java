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
import java.util.List;

import reactor.core.publisher.Mono;

import org.springframework.cloud.gateway.filter.factory.SetStatusGatewayFilterFactory;
import org.springframework.cloud.gateway.support.MessageHeaderUtils;
import org.springframework.cloud.stream.function.StreamBridge;
import org.springframework.core.Ordered;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.codec.HttpMessageReader;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.messaging.Message;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.util.CollectionUtils;
import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.server.ServerWebExchange;

import static org.springframework.cloud.gateway.support.ServerWebExchangeUtils.GATEWAY_REQUEST_URL_ATTR;
import static org.springframework.cloud.gateway.support.ServerWebExchangeUtils.isAlreadyRouted;
import static org.springframework.cloud.gateway.support.ServerWebExchangeUtils.setAlreadyRouted;

/**
 * @author Spencer Gibb
 */
public class StreamRoutingFilter implements GlobalFilter, Ordered {

	private final StreamBridge streamBridge;

	private final List<HttpMessageReader<?>> messageReaders;

	private final SetStatusGatewayFilterFactory setStatusFilter;

	public StreamRoutingFilter(StreamBridge streamBridge, List<HttpMessageReader<?>> messageReaders) {
		this.streamBridge = streamBridge;
		this.messageReaders = messageReaders;
		// TODO: is this the right place for this?
		this.streamBridge.setAsync(true);
		setStatusFilter = new SetStatusGatewayFilterFactory();
	}

	@Override
	public int getOrder() {
		return RouteToRequestUrlFilter.ROUTE_TO_URL_FILTER_ORDER + 10;
	}

	@Override
	public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
		URI requestUrl = exchange.getRequiredAttribute(GATEWAY_REQUEST_URL_ATTR);
		String scheme = requestUrl.getScheme();

		if (isAlreadyRouted(exchange) || !"stream".equals(scheme)) {
			return chain.filter(exchange);
		}
		setAlreadyRouted(exchange);

		ServerRequest serverRequest = ServerRequest.create(exchange, messageReaders);
		return serverRequest.bodyToMono(byte[].class).flatMap(requestBody -> {
			ServerHttpRequest request = exchange.getRequest();
			HttpHeaders headers = request.getHeaders();

			Message<?> inputMessage = null;

			MessageBuilder<?> builder = MessageBuilder.withPayload(requestBody);
			if (!CollectionUtils.isEmpty(request.getQueryParams())) {
				// TODO: move HeaderUtils
				builder = builder.setHeader(MessageHeaderUtils.HTTP_REQUEST_PARAM,
						request.getQueryParams().toSingleValueMap());
				// TODO: sanitize?
			}
			inputMessage = builder.copyHeaders(headers.toSingleValueMap()).build();

			// TODO: output content type
			boolean send = streamBridge.send(requestUrl.getHost(), inputMessage);

			HttpStatus responseStatus = (send) ? HttpStatus.OK : HttpStatus.BAD_REQUEST;

			SetStatusGatewayFilterFactory.Config config = new SetStatusGatewayFilterFactory.Config();
			config.setStatus(responseStatus.name());
			return setStatusFilter.apply(config).filter(exchange, chain);
		});
	}

}
