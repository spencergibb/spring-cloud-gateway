/*
 * Copyright 2013-2021 the original author or authors.
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

package org.springframework.cloud.gateway.fn.webflux;

import java.net.URI;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.netty.http.client.HttpClient;
import reactor.netty.http.client.HttpClientResponse;
import reactor.netty.resources.ConnectionProvider;

import org.springframework.cloud.gateway.fn.core.AbstractProxyHandlerFunction;
import org.springframework.cloud.gateway.fn.core.ServerRequestAdapter;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.lang.Nullable;
import org.springframework.web.reactive.function.server.HandlerFunction;
import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.reactive.function.server.ServerResponse;

public abstract class HandlerFunctions {

	private static final List<MediaType> streamingMediaTypes = Arrays.asList(MediaType.TEXT_EVENT_STREAM,
			MediaType.APPLICATION_STREAM_JSON);

	public static HandlerFunction<ServerResponse> http(String uri) {
		return http(URI.create(uri));
	}

	public static HandlerFunction<ServerResponse> http(URI uri) {
		// TODO: how to have this be shared ala server?
		ConnectionProvider connectionProvider = ConnectionProvider.builder("proxy").maxConnections(Integer.MAX_VALUE)
				.pendingAcquireTimeout(Duration.ofMillis(0)).pendingAcquireMaxCount(-1).build();
		HttpClient httpClient = HttpClient.create(connectionProvider);

		return new ProxyHandlerFunction(httpClient, uri);
	}

	protected static void addResponseHeaders(HttpClientResponse res, HttpHeaders responseHeaders) {
		res.responseHeaders().names().forEach(name -> responseHeaders.addAll(name, res.responseHeaders().getAll(name)));

		/*
		 * String contentTypeValue = headers.getFirst(HttpHeaders.CONTENT_TYPE); if
		 * (StringUtils.hasLength(contentTypeValue)) {
		 * exchange.getAttributes().put(ORIGINAL_RESPONSE_CONTENT_TYPE_ATTR,
		 * contentTypeValue); }
		 */

		// make sure headers filters run after setting status so it is
		// available in response
		// HttpHeaders filteredResponseHeaders =
		// HttpHeadersFilter.filter(getHeadersFilters(), headers, exchange,
		// Type.RESPONSE);

		// if (!filteredResponseHeaders.containsKey(HttpHeaders.TRANSFER_ENCODING)
		// && filteredResponseHeaders.containsKey(HttpHeaders.CONTENT_LENGTH)) {
		// It is not valid to have both the transfer-encoding header and
		// the content-length header.
		// Remove the transfer-encoding header in the response if the
		// content-length header is present.
		// response.getHeaders().remove(HttpHeaders.TRANSFER_ENCODING);
		// }

		// exchange.getAttributes().put(CLIENT_RESPONSE_HEADER_NAMES,
		// filteredResponseHeaders.keySet());

		// response.getHeaders().putAll(filteredResponseHeaders);
	}

	private static boolean isStreamingMediaType(@Nullable String contentType) {
		if (contentType == null) {
			return false;
		}
		MediaType mediaType = MediaType.valueOf(contentType);
		return (mediaType != null && streamingMediaTypes.stream().anyMatch(mediaType::isCompatibleWith));
	}

	static class ProxyHandlerFunction extends AbstractProxyHandlerFunction<ServerRequest, ServerResponse>
			implements HandlerFunction<ServerResponse> {

		ProxyHandlerFunction(HttpClient httpClient, URI uri) {
			super(httpClient, uri);
		}

		@Override
		protected ServerRequestAdapter<ServerRequest> adapt(ServerRequest request) {
			return new WebfluxServerRequestAdapter(request);
		}

		@Override
		protected Mono<ServerResponse> response(HttpClientResponse res, Flux<DataBuffer> body) {
			String contentType = res.responseHeaders().get(HttpHeaders.CONTENT_TYPE);

			return ServerResponse.status(res.status().code())
					.headers(responseHeaders -> addResponseHeaders(res, responseHeaders))
					.body((outputMessage, context) -> (isStreamingMediaType(contentType)
							? outputMessage.writeAndFlushWith(body.map(Flux::just)) : outputMessage.writeWith(body)));
		}

		@Override
		public Mono<ServerResponse> handle(ServerRequest request) {
			return doHandle(request).next();
		}

	}

}
