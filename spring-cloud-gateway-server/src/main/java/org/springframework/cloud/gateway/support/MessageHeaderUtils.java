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

package org.springframework.cloud.gateway.support;

import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.springframework.http.HttpHeaders;
import org.springframework.messaging.MessageHeaders;

/**
 * @author Dave Syer
 * @author Oleg Zhurakousky
 */
public final class MessageHeaderUtils {

	/**
	 * Message Header name which contains HTTP request parameters.
	 */
	public static final String HTTP_REQUEST_PARAM = "http_request_param";

	private static final HttpHeaders IGNORED = new HttpHeaders();

	private static final HttpHeaders REQUEST_ONLY = new HttpHeaders();

	static {
		IGNORED.add(MessageHeaders.ID, "");
		IGNORED.add(HttpHeaders.CONTENT_LENGTH, "0");
		// Headers that would typically be added by a downstream client
		REQUEST_ONLY.add(HttpHeaders.ACCEPT, "");
		REQUEST_ONLY.add(HttpHeaders.CONTENT_LENGTH, "");
		REQUEST_ONLY.add(HttpHeaders.CONTENT_TYPE, "");
		REQUEST_ONLY.add(HttpHeaders.HOST, "");
	}

	private MessageHeaderUtils() {
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

	public static HttpHeaders sanitize(HttpHeaders request, List<String> ignoredHeders,
			List<String> requestOnlyHeaders) {
		HttpHeaders result = new HttpHeaders();
		for (String name : request.keySet()) {
			List<String> value = request.get(name);
			name = name.toLowerCase(Locale.ROOT);
			if (!IGNORED.containsKey(name) && !REQUEST_ONLY.containsKey(name) && !ignoredHeders.contains(name)
					&& !requestOnlyHeaders.contains(name)) {
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
			Object value = values == null ? null : (values.size() == 1 ? values.iterator().next() : values);
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
