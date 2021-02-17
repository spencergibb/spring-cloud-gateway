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

import java.util.Arrays;

import org.springframework.web.servlet.function.HandlerFilterFunction;
import org.springframework.web.servlet.function.ServerResponse;

public abstract class FilterFunctions {

	public static HandlerFilterFunction<ServerResponse, ServerResponse> addRequestHeader(String name,
			String... values) {
		return (request, next) -> {
			MutableHttpHeaders headers = new MutableHttpHeaders(request.headers().asHttpHeaders());
			headers.asHttpHeaders().put(name, Arrays.asList(values));
			ServerRequestWrapper wrapper = new ServerRequestWrapper(request) {
				@Override
				public Headers headers() {
					return headers;
				}
			};
			return next.handle(wrapper);
		};
	}

}
