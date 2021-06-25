package no.entur.nisaba.netex;

import org.apache.camel.Header;
import org.entur.netex.index.api.NetexEntitiesIndex;
import org.rutebanken.netex.model.CompositeFrame;
import org.rutebanken.netex.model.DestinationDisplaysInFrame_RelStructure;
import org.rutebanken.netex.model.NoticesInFrame_RelStructure;
import org.rutebanken.netex.model.ObjectFactory;
import org.rutebanken.netex.model.PublicationDeliveryStructure;
import org.rutebanken.netex.model.ServiceFrame;

import java.time.LocalDateTime;

import static no.entur.nisaba.Constants.COMMON_FILE_INDEX;
import static no.entur.nisaba.Constants.DATASET_CODESPACE;
import static no.entur.nisaba.Constants.PUBLICATION_DELIVERY_TIMESTAMP;
import static no.entur.nisaba.netex.NetexUtils.getFrames;
import static no.entur.nisaba.netex.NetexUtils.wrapAsJAXBElement;

public class NoticeAndDestinationDisplayPublicationDeliveryBuilder {

    private static final String DEFAULT_FRAME_VERSION = "1";
    private static final String DEFAULT_FRAME_ID = "1";

    private final ObjectFactory objectFactory = new ObjectFactory();


    public PublicationDeliveryStructure build(
            @Header(DATASET_CODESPACE) String codespace,
            @Header(COMMON_FILE_INDEX) NetexEntitiesIndex commonEntities,
            @Header(PUBLICATION_DELIVERY_TIMESTAMP) String publicationDeliveryTimestamp) {

        // publication delivery
        CompositeFrame sourceCompositeFrame = commonEntities.getCompositeFrames().stream().findFirst().orElseThrow();
        PublicationDeliveryStructure publicationDeliveryStructure = new PublicationDeliveryStructureBuilder()
                .withDefaultCodespace(codespace)
                .withDescription("Notices and Destination Displays")
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

        if (!commonEntities.getNoticeIndex().getAll().isEmpty()) {
            NoticesInFrame_RelStructure noticesInFrameRelStructure = objectFactory.createNoticesInFrame_RelStructure();
            noticesInFrameRelStructure.getNotice().addAll(commonEntities.getNoticeIndex().getAll());
            serviceFrame.setNotices(noticesInFrameRelStructure);
        }

        if (!commonEntities.getDestinationDisplayIndex().getAll().isEmpty()) {
            DestinationDisplaysInFrame_RelStructure destinationDisplayRefsRelStructure = objectFactory.createDestinationDisplaysInFrame_RelStructure();
            destinationDisplayRefsRelStructure.getDestinationDisplay().addAll(commonEntities.getDestinationDisplayIndex().getAll());
            serviceFrame.setDestinationDisplays(destinationDisplayRefsRelStructure);
        }

        return publicationDeliveryStructure;

    }


}

