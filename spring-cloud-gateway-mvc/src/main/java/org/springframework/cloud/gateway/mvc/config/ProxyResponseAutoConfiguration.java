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

package org.springframework.cloud.gateway.mvc.config;

import java.io.IOException;
import java.net.URI;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.cloud.gateway.mvc.ProxyExchange;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.http.converter.ByteArrayHttpMessageConverter;
import org.springframework.web.client.DefaultResponseErrorHandler;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.HandlerMethodReturnValueHandler;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Autoconfiguration for the {@link ProxyExchange} argument handler in Spring MVC
 * <code>@RequestMapping</code> methods.
 *
 * @author Dave Syer
 * @author Tim Ysewyn
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnWebApplication
@ConditionalOnClass({ HandlerMethodReturnValueHandler.class })
@EnableConfigurationProperties(ProxyProperties.class)
public class ProxyResponseAutoConfiguration implements WebMvcConfigurer {

	@Autowired
	private ApplicationContext context;

	@Bean
	@ConditionalOnMissingBean
	public ProxyExchangeArgumentResolver proxyExchangeArgumentResolver(Optional<RestTemplateBuilder> optional,
			ProxyProperties proxy) {
		RestTemplateBuilder builder = optional.orElse(new RestTemplateBuilder());
		RestTemplate template = builder.build();
		template.setErrorHandler(new NoOpResponseErrorHandler());
		template.getMessageConverters().add(new ByteArrayHttpMessageConverter() {
			@Override
			public boolean supports(Class<?> clazz) {
				return true;
			}
		});
		ProxyExchangeArgumentResolver resolver = new ProxyExchangeArgumentResolver(template);
		resolver.setHeaders(proxy.convertHeaders());
		resolver.setAutoForwardedHeaders(proxy.getAutoForward());
		Set<String> excludedHeaderNames = new HashSet<>();
		if (proxy.getSensitive() != null) {
			excludedHeaderNames.addAll(proxy.getSensitive());
		}
		if (proxy.getSkipped() != null) {
			excludedHeaderNames.addAll(proxy.getSkipped());
		}
		resolver.setExcluded(excludedHeaderNames);
		return resolver;
	}

	@Override
	public void addArgumentResolvers(List<HandlerMethodArgumentResolver> argumentResolvers) {
		argumentResolvers.add(context.getBean(ProxyExchangeArgumentResolver.class));
	}

	private static class NoOpResponseErrorHandler extends DefaultResponseErrorHandler {

		@Override
		public void handleError(ClientHttpResponse response) throws IOException {
		}

		@Override
		public void handleError(URI url, HttpMethod method, ClientHttpResponse response) throws IOException {
		}

		@Override
		protected void handleError(ClientHttpResponse response, HttpStatusCode statusCode, URI url, HttpMethod method)
				throws IOException {
		}

	}

	@Configuration(proxyBeanMethods = false)
	@ConditionalOnMissingClass("org.springframework.cloud.gateway.proxyexchange.webmvc.Marker")
	protected static class NewModuleConfiguration {

		private static final Log log = LogFactory.getLog(ProxyResponseAutoConfiguration.class);

		private static final String BORDER = "\n\n**********************************************************\n\n";

		public NewModuleConfiguration() {
			log.warn(BORDER + "The artifact spring-cloud-gateway-mvc is deprecated. "
					+ "It will be removed in the next major release. "
					+ "Please add spring-cloud-gateway-proxyexchange-webmvc dependency." + BORDER);
		}

	}

}
