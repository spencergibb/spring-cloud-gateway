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
import java.net.InetSocketAddress;
import java.net.URI;
import java.security.Principal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import javax.servlet.ServletException;
import javax.servlet.http.Cookie;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpSession;
import javax.servlet.http.Part;

import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpMethod;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.server.PathContainer;
import org.springframework.http.server.RequestPath;
import org.springframework.lang.Nullable;
import org.springframework.util.Assert;
import org.springframework.util.MultiValueMap;
import org.springframework.web.servlet.function.ServerRequest;
import org.springframework.web.servlet.function.ServerResponse;
import org.springframework.web.util.UriBuilder;

public class ServerRequestWrapper implements ServerRequest {

	private final ServerRequest delegate;

	/**
	 * Create a new {@code ServerRequestWrapper} that wraps the given request.
	 * @param delegate the request to wrap
	 */
	public ServerRequestWrapper(ServerRequest delegate) {
		Assert.notNull(delegate, "Delegate must not be null");
		this.delegate = delegate;
	}

	@Override
	@Nullable
	public HttpMethod method() {
		return delegate.method();
	}

	@Override
	public String methodName() {
		return delegate.methodName();
	}

	@Override
	public URI uri() {
		return delegate.uri();
	}

	@Override
	public UriBuilder uriBuilder() {
		return delegate.uriBuilder();
	}

	@Override
	public String path() {
		return delegate.path();
	}

	@Override
	@Deprecated
	public PathContainer pathContainer() {
		return delegate.pathContainer();
	}

	@Override
	public RequestPath requestPath() {
		return delegate.requestPath();
	}

	@Override
	public Headers headers() {
		return delegate.headers();
	}

	@Override
	public MultiValueMap<String, Cookie> cookies() {
		return delegate.cookies();
	}

	@Override
	public Optional<InetSocketAddress> remoteAddress() {
		return delegate.remoteAddress();
	}

	@Override
	public List<HttpMessageConverter<?>> messageConverters() {
		return delegate.messageConverters();
	}

	@Override
	public <T> T body(Class<T> bodyType) throws ServletException, IOException {
		return delegate.body(bodyType);
	}

	@Override
	public <T> T body(ParameterizedTypeReference<T> bodyType) throws ServletException, IOException {
		return delegate.body(bodyType);
	}

	@Override
	public Optional<Object> attribute(String name) {
		return delegate.attribute(name);
	}

	@Override
	public Map<String, Object> attributes() {
		return delegate.attributes();
	}

	@Override
	public Optional<String> param(String name) {
		return delegate.param(name);
	}

	@Override
	public MultiValueMap<String, String> params() {
		return delegate.params();
	}

	@Override
	public MultiValueMap<String, Part> multipartData() throws IOException, ServletException {
		return delegate.multipartData();
	}

	@Override
	public String pathVariable(String name) {
		return delegate.pathVariable(name);
	}

	@Override
	public Map<String, String> pathVariables() {
		return delegate.pathVariables();
	}

	@Override
	public HttpSession session() {
		return delegate.session();
	}

	@Override
	public Optional<Principal> principal() {
		return delegate.principal();
	}

	@Override
	public HttpServletRequest servletRequest() {
		return delegate.servletRequest();
	}

	@Override
	public Optional<ServerResponse> checkNotModified(Instant lastModified) {
		return delegate.checkNotModified(lastModified);
	}

	@Override
	public Optional<ServerResponse> checkNotModified(String etag) {
		return delegate.checkNotModified(etag);
	}

	@Override
	public Optional<ServerResponse> checkNotModified(Instant lastModified, String etag) {
		return delegate.checkNotModified(lastModified, etag);
	}

	public static ServerRequest create(HttpServletRequest servletRequest,
			List<HttpMessageConverter<?>> messageReaders) {
		return ServerRequest.create(servletRequest, messageReaders);
	}

	public static Builder from(ServerRequest other) {
		return ServerRequest.from(other);
	}

}
