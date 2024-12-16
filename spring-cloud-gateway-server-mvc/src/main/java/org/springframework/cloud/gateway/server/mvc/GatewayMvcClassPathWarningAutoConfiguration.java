/*
 * Copyright 2013-2020 the original author or authors.
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

package org.springframework.cloud.gateway.server.mvc;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import org.springframework.boot.autoconfigure.AutoConfigureBefore;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@AutoConfigureBefore(GatewayServerMvcAutoConfiguration.class)
@ConditionalOnProperty(name = "spring.cloud.gateway.mvc.enabled", matchIfMissing = true)
public class GatewayMvcClassPathWarningAutoConfiguration {

	private static final Log log = LogFactory.getLog(GatewayMvcClassPathWarningAutoConfiguration.class);

	private static final String BORDER = "\n\n**********************************************************\n\n";

	@Configuration(proxyBeanMethods = false)
	@ConditionalOnMissingClass("org.springframework.web.servlet.DispatcherServlet")
	protected static class WebMvcMissingFromClasspathConfiguration {

		public WebMvcMissingFromClasspathConfiguration() {
			log.warn(BORDER + "Spring Web MVC is missing from the classpath, "
					+ "which is required for Spring Cloud Gateway Server Web MVC. "
					+ "Please add spring-boot-starter-web dependency." + BORDER);
		}

	}

	@Configuration(proxyBeanMethods = false)
	@ConditionalOnMissingClass("org.springframework.cloud.gateway.server.webmvc.Marker")
	protected static class NewModuleConfiguration {

		public NewModuleConfiguration() {
			log.warn(BORDER + "The artifact spring-cloud-gateway-server-mvc is deprecated. "
					+ "It will be removed in the next major release. "
					+ "Please add spring-cloud-gateway-server-webmvc dependency." + BORDER);
		}

	}

}
