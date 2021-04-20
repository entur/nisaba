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
