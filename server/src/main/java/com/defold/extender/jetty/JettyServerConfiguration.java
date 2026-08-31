package com.defold.extender.jetty;

import org.eclipse.jetty.server.Handler;
import org.springframework.boot.jetty.ConfigurableJettyWebServerFactory;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class JettyServerConfiguration {

    @Bean
    WebServerFactoryCustomizer<ConfigurableJettyWebServerFactory> extenderJettyCustomizer() {
        return factory -> factory.addServerCustomizers(server -> {
            // Must be the server level handler: the context one is Spring Boot's /error dispatch.
            server.setErrorHandler(new ExtenderJettyErrorHandler());

            final JettyRequestEventsHandler eventsHandler = new JettyRequestEventsHandler();
            final Handler currentHandler = server.getHandler();
            eventsHandler.setHandler(currentHandler);
            server.setHandler(eventsHandler);
        });
    }
}
