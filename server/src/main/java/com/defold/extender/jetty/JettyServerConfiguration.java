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
            // Spring Boot sets its own error handler on the servlet context (it drives the /error
            // dispatch), the server level one is unused and is where pre-context errors end up.
            server.setErrorHandler(new ExtenderJettyErrorHandler());

            final JettyRequestEventsHandler eventsHandler = new JettyRequestEventsHandler();
            final Handler currentHandler = server.getHandler();
            eventsHandler.setHandler(currentHandler);
            server.setHandler(eventsHandler);
        });
    }
}
