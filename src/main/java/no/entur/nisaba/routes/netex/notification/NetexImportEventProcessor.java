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

package no.entur.nisaba.routes.netex.notification;

import no.entur.nisaba.Constants;
import no.entur.nisaba.domain.NetexImportEvent;
import org.apache.camel.Exchange;
import org.apache.camel.Processor;

import java.time.LocalDateTime;

public class NetexImportEventProcessor implements Processor {
    @Override
    public void process(Exchange exchange) {
        NetexImportEvent netexImportEvent = new NetexImportEvent(exchange.getIn().getHeader(Constants.DATASET_CODESPACE, String.class),
                exchange.getIn().getHeader(Constants.DATASET_CREATION_TIME, LocalDateTime.class));
        exchange.getIn().setBody(netexImportEvent);

    }
}
