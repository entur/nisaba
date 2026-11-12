/*
 * Licensed under the EUPL, Version 1.2 or – as soon they will be approved by
 * the European Commission - subsequent versions of the EUPL (the "Licence");
 * You may not use this work except in compliance with the Licence.
 * You may obtain a copy of the Licence at:
 *
 *   https://joinup.ec.europa.eu/software/page/eupl
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the Licence is distributed on an "AS IS" basis,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the Licence for the specific language governing permissions and
 * limitations under the Licence.
 *
 */

package no.entur.nisaba;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.joran.JoranConfigurator;
import ch.qos.logback.core.joran.spi.JoranException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * The tests run with {@code logback-test.xml}, so nothing else reads the production {@code logback.xml}.
 * A conditional that Logback no longer evaluates leaves the root logger without an appender: the
 * application then starts and logs nothing.
 */
class LogbackConfigurationTest {

    @Test
    void theJsonAppenderIsAttachedOutsideDev() throws JoranException {
        assertNotNull(configure("prd").getAppender("console"));
    }

    @Test
    void theSpringBootConsoleAppenderIsAttachedInDev() throws JoranException {
        assertNotNull(configure("dev").getAppender("CONSOLE"));
    }

    private static Logger configure(String env) throws JoranException {
        LoggerContext context = new LoggerContext();
        // always set: the context scope comes before the system properties and the environment of the build
        context.putProperty("env", env);
        JoranConfigurator configurator = new JoranConfigurator();
        configurator.setContext(context);
        configurator.doConfigure(LogbackConfigurationTest.class.getResource("/logback.xml"));
        return context.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
    }
}
