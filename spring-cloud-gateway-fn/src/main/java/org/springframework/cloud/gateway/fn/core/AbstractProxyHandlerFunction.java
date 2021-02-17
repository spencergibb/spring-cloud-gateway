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

package org.springframework.cloud.gateway.fn.core;

import java.net.URI;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.handler.codec.http.DefaultHttpHeaders;
import io.netty.handler.codec.http.HttpMethod;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.netty.http.client.HttpClient;
import reactor.netty.http.client.HttpClientResponse;

import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferFactory;
import org.springframework.core.io.buffer.DefaultDataBuffer;
import org.springframework.core.io.buffer.DefaultDataBufferFactory;
import org.springframework.core.io.buffer.NettyDataBuffer;
import org.springframework.core.io.buffer.NettyDataBufferFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.util.ReflectionUtils;
import org.springframework.web.util.UriComponentsBuilder;

public abstract class AbstractProxyHandlerFunction<REQUEST, RESPONSE> {

	private static final Log log = LogFactory.getLog(AbstractProxyHandlerFunction.class);

	protected final HttpClient httpClient;

	protected final URI uri;

	protected AbstractProxyHandlerFunction(HttpClient httpClient, URI uri) {
		this.httpClient = httpClient;
		this.uri = uri;
	}

	protected abstract ServerRequestAdapter<REQUEST> adapt(REQUEST request);

	protected abstract Mono<RESPONSE> response(HttpClientResponse res, Flux<DataBuffer> body);

	protected Flux<RESPONSE> doHandle(REQUEST originialRequest) {
		ServerRequestAdapter<REQUEST> request = adapt(originialRequest);
		// See RouteToRequestUrlFilter
		boolean encoded = containsEncodedQuery(request.uri());
		URI url = UriComponentsBuilder.fromUri(request.uri())
				// .uri(routeUri)
				.scheme(uri.getScheme()).host(uri.getHost()).port(uri.getPort()).build(encoded).toUri();

		// See NettyRoutingFilter
		final DefaultHttpHeaders httpHeaders = new DefaultHttpHeaders();
		request.httpHeaders().forEach(httpHeaders::set);
		HttpMethod method = HttpMethod.valueOf(request.methodName());

		Flux<RESPONSE> responseFlux = httpClient.headers(headers -> {
			headers.add(httpHeaders);
			// Will either be set below, or later by Netty
			headers.remove(HttpHeaders.HOST);
			/*
			 * if (preserveHost) { String host =
			 * request.getHeaders().getFirst(HttpHeaders.HOST);
			 * headers.add(HttpHeaders.HOST, host); }
			 */
		}).request(method).uri(url).send((req, nettyOutbound) -> {
			if (log.isTraceEnabled()) {
				/*
				 * nettyOutbound.withConnection(connection -> log.trace("outbound route: "
				 * + connection.channel().id().asShortText() + ", inbound: " +
				 * request.exchange() .getLogPrefix()));
				 */
			}
			try {
				return nettyOutbound.send(request.body().map(AbstractProxyHandlerFunction::getByteBuf));
			}
			catch (Exception e) {
				ReflectionUtils.rethrowRuntimeException(e);
			}
			return nettyOutbound;
		}).responseConnection((res, connection) -> {

			// Defer committing the response until all route filters have run
			// Put client response as ServerWebExchange attribute and write
			// response later NettyWriteResponseFilter
			// request.exchange().getAttributes().put(CLIENT_RESPONSE_ATTR, res);
			// request.exchange().getAttributes().put(CLIENT_RESPONSE_CONN_ATTR,
			// connection);

			// See NettyWriteResponseFilter
			final Flux<DataBuffer> body = connection.inbound().receive().retain()
					.map(byteBuf -> wrap(byteBuf, DefaultDataBufferFactory.sharedInstance));

			String contentType = res.responseHeaders().get(HttpHeaders.CONTENT_TYPE);

			return response(res, body);
			// .body((outputMessage, context) -> (isStreamingMediaType(contentType)
			// ? outputMessage.writeAndFlushWith(body.map(Flux::just))
			// : outputMessage.writeWith(body)));
		});

		// TODO: timeouts
		return responseFlux;
	}

	// See ServerWebExchange
	public static boolean containsEncodedQuery(URI uri) {
		boolean encoded = (uri.getRawQuery() != null && uri.getRawQuery().contains("%"))
				|| (uri.getRawPath() != null && uri.getRawPath().contains("%"));

		// Verify if it is really fully encoded. Treat partial encoded as unencoded.
		if (encoded) {
			try {
				UriComponentsBuilder.fromUri(uri).build(true);
				return true;
			}
			catch (IllegalArgumentException ignored) {
				if (log.isTraceEnabled()) {
					log.trace("Error in containsEncodedParts", ignored);
				}
			}

			return false;
		}

		return encoded;
	}

	protected static ByteBuf getByteBuf(DataBuffer dataBuffer) {
		if (dataBuffer instanceof NettyDataBuffer) {
			NettyDataBuffer buffer = (NettyDataBuffer) dataBuffer;
			return buffer.getNativeBuffer();
		}
		// MockServerHttpResponse creates these
		else if (dataBuffer instanceof DefaultDataBuffer) {
			DefaultDataBuffer buffer = (DefaultDataBuffer) dataBuffer;
			return Unpooled.wrappedBuffer(buffer.getNativeBuffer());
		}
		throw new IllegalArgumentException("Unable to handle DataBuffer of type " + dataBuffer.getClass());
	}

	protected static DataBuffer wrap(ByteBuf byteBuf, DataBufferFactory bufferFactory) {
		if (bufferFactory instanceof NettyDataBufferFactory) {
			NettyDataBufferFactory factory = (NettyDataBufferFactory) bufferFactory;
			return factory.wrap(byteBuf);
		}
		// MockServerHttpResponse creates these
		else if (bufferFactory instanceof DefaultDataBufferFactory) {
			DataBuffer buffer = ((DefaultDataBufferFactory) bufferFactory).allocateBuffer(byteBuf.readableBytes());
			buffer.write(byteBuf.nioBuffer());
			byteBuf.release();
			return buffer;
		}
		throw new IllegalArgumentException("Unkown DataBufferFactory type " + bufferFactory.getClass());
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

}
