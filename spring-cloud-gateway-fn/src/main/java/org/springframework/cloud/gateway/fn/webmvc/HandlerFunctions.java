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

package org.springframework.cloud.gateway.fn.webmvc;

import java.io.IOException;
import java.net.URI;
import java.time.Duration;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.netty.http.client.HttpClient;
import reactor.netty.http.client.HttpClientResponse;
import reactor.netty.resources.ConnectionProvider;

import org.springframework.cloud.gateway.fn.core.AbstractProxyHandlerFunction;
import org.springframework.cloud.gateway.fn.core.ServerRequestAdapter;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.util.ReflectionUtils;
import org.springframework.web.servlet.function.HandlerFunction;
import org.springframework.web.servlet.function.ServerRequest;
import org.springframework.web.servlet.function.ServerResponse;

public abstract class HandlerFunctions {

	public static HandlerFunction<ServerResponse> http(String uri) {
		return http(URI.create(uri));
	}

	private static HandlerFunction<ServerResponse> http(URI uri) {
		ConnectionProvider connectionProvider = ConnectionProvider.builder("proxy").maxConnections(Integer.MAX_VALUE)
				.pendingAcquireTimeout(Duration.ofMillis(0)).pendingAcquireMaxCount(-1).build();
		HttpClient httpClient = HttpClient.create(connectionProvider).wiretap(true);

		return new ProxyHandlerFunction(httpClient, uri);
	}

	static class ProxyHandlerFunction extends AbstractProxyHandlerFunction<ServerRequest, ServerResponse>
			implements HandlerFunction<ServerResponse> {

		ProxyHandlerFunction(HttpClient httpClient, URI uri) {
			super(httpClient, uri);
		}

		@Override
		protected ServerRequestAdapter<ServerRequest> adapt(ServerRequest request) {
			return new WebmvcServerRequestAdapter(request);
		}

		@Override
		protected Mono<ServerResponse> response(HttpClientResponse res, Flux<DataBuffer> body) {
			return Mono.just(ServerResponse.status(res.status().code())
					.headers(responseHeaders -> addResponseHeaders(res, responseHeaders))
					// .body(body));
					.build((httpServletRequest, httpServletResponse) -> {
						try {
							DataBufferUtils.write(body, httpServletResponse.getOutputStream()).subscribe();
						}
						catch (IOException e) {
							ReflectionUtils.rethrowRuntimeException(e);
						}
						return null;
					}));
		}

		@Override
		public ServerResponse handle(ServerRequest request) throws Exception {
			return ServerResponse.async(doHandle(request).next());
		}

	}

}
