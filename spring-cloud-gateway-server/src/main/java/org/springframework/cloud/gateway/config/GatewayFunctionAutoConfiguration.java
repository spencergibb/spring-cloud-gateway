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

package org.springframework.cloud.gateway.config;

import java.util.Set;

import org.springframework.boot.autoconfigure.AutoConfigureAfter;
import org.springframework.boot.autoconfigure.AutoConfigureBefore;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.web.reactive.HttpHandlerAutoConfiguration;
import org.springframework.cloud.function.context.FunctionCatalog;
import org.springframework.cloud.function.context.config.ContextFunctionCatalogAutoConfiguration;
import org.springframework.cloud.gateway.config.conditional.ConditionalOnEnabledGlobalFilter;
import org.springframework.cloud.gateway.filter.FunctionRoutingFilter;
import org.springframework.cloud.gateway.filter.factory.rewrite.MessageBodyEncoder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.codec.ServerCodecConfigurer;
import org.springframework.web.reactive.DispatcherHandler;

@Configuration(proxyBeanMethods = false)
@AutoConfigureAfter(ContextFunctionCatalogAutoConfiguration.class)
@AutoConfigureBefore({ HttpHandlerAutoConfiguration.class, GatewayAutoConfiguration.class })
@ConditionalOnClass({ FunctionCatalog.class, DispatcherHandler.class })
@ConditionalOnProperty(name = "spring.cloud.gateway.function.enabled", matchIfMissing = true)
class GatewayFunctionAutoConfiguration {

	@Bean
	@ConditionalOnEnabledGlobalFilter
	@ConditionalOnBean(FunctionCatalog.class)
	public FunctionRoutingFilter functionRoutingFilter(FunctionCatalog functionCatalog,
			ServerCodecConfigurer codecConfigurer, Set<MessageBodyEncoder> messageBodyEncoders) {
		return new FunctionRoutingFilter(functionCatalog, codecConfigurer.getReaders(), messageBodyEncoders);
	}

}
