package no.entur.nisaba.netex;

import org.apache.camel.Header;
import org.entur.netex.index.api.NetexEntitiesIndex;
import org.rutebanken.netex.model.CompositeFrame;
import org.rutebanken.netex.model.ObjectFactory;
import org.rutebanken.netex.model.PublicationDeliveryStructure;
import org.rutebanken.netex.model.ServiceFrame;
import org.rutebanken.netex.model.ServiceLink;
import org.rutebanken.netex.model.ServiceLinksInFrame_RelStructure;

import java.time.LocalDateTime;
import java.util.List;

import static no.entur.nisaba.Constants.COMMON_FILE_INDEX;
import static no.entur.nisaba.Constants.DATASET_CODESPACE;
import static no.entur.nisaba.Constants.PUBLICATION_DELIVERY_TIMESTAMP;
import static no.entur.nisaba.Constants.SERVICE_LINKS_SUB_LIST;
import static no.entur.nisaba.netex.NetexUtils.getFrames;
import static no.entur.nisaba.netex.NetexUtils.wrapAsJAXBElement;

public class ServiceLinkPublicationDeliveryBuilder {

    private static final String DEFAULT_FRAME_VERSION = "1";
    private static final String DEFAULT_FRAME_ID = "1";

    private final ObjectFactory objectFactory = new ObjectFactory();


    public PublicationDeliveryStructure build(
            @Header(DATASET_CODESPACE) String codespace,
            @Header(COMMON_FILE_INDEX) NetexEntitiesIndex commonEntities,
            @Header(SERVICE_LINKS_SUB_LIST) List<ServiceLink> serviceLinks,
            @Header(PUBLICATION_DELIVERY_TIMESTAMP) String publicationDeliveryTimestamp) {

        // publication delivery
        CompositeFrame sourceCompositeFrame = commonEntities.getCompositeFrames().stream().findFirst().orElseThrow();
        PublicationDeliveryStructure publicationDeliveryStructure = new PublicationDeliveryStructureBuilder()
                .withDefaultCodespace(codespace)
                .withDescription("Service Links")
                .withPublicationDeliveryTimestamp(LocalDateTime.parse(publicationDeliveryTimestamp))
                .withCompositeFrameCreationDate(sourceCompositeFrame.getCreated())
                .withCodespaces(sourceCompositeFrame.getCodespaces())
                .withFrameDefaults(sourceCompositeFrame.getFrameDefaults())
                .withValidityConditions(sourceCompositeFrame.getValidityConditions())
                .build();

        // service frame

        ServiceFrame serviceFrame = objectFactory.createServiceFrame();
        serviceFrame.setId(codespace.toUpperCase() + ":ServiceFrame:" + DEFAULT_FRAME_ID);
        serviceFrame.setVersion(DEFAULT_FRAME_VERSION);
        getFrames(publicationDeliveryStructure).add(wrapAsJAXBElement(serviceFrame));

        serviceLinks.forEach(serviceLink -> {
            serviceLink.getToPointRef().setVersion(null);
            serviceLink.getFromPointRef().setVersion(null);
        });

        ServiceLinksInFrame_RelStructure serviceLinksInFrameRelStructure = objectFactory.createServiceLinksInFrame_RelStructure();
        serviceLinksInFrameRelStructure.getServiceLink().addAll(serviceLinks);
        serviceFrame.setServiceLinks(serviceLinksInFrameRelStructure);

        return publicationDeliveryStructure;

    }


}

