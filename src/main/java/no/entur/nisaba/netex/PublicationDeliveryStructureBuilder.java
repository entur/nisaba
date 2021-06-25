package no.entur.nisaba.netex;

import org.rutebanken.netex.model.Codespaces_RelStructure;
import org.rutebanken.netex.model.CompositeFrame;
import org.rutebanken.netex.model.Frames_RelStructure;
import org.rutebanken.netex.model.ObjectFactory;
import org.rutebanken.netex.model.PublicationDeliveryStructure;
import org.rutebanken.netex.model.ValidityConditions_RelStructure;
import org.rutebanken.netex.model.VersionFrameDefaultsStructure;

import java.time.LocalDateTime;

import static no.entur.nisaba.netex.JaxbUtils.wrapAsJAXBElement;

public class PublicationDeliveryStructureBuilder {

    private static final String NETEX_VERSION = "1.12:NO-NeTEx-networktimetable:1.3";
    private static final String NETEX_PARTICIPANT_REF = "RB";
    private static final String DEFAULT_FRAME_ID = "1";
    private static final String DEFAULT_FRAME_VERSION = "1";

    private static final ObjectFactory objectFactory = new ObjectFactory();

    private String description;
    private LocalDateTime publicationDeliveryTimestamp;
    private LocalDateTime compositeFrameCreationDate;
    private String defaultCodespace;
    private VersionFrameDefaultsStructure frameDefaults;
    private ValidityConditions_RelStructure validityConditions;
    private Codespaces_RelStructure codespaces;

    PublicationDeliveryStructureBuilder() {

    }

    public PublicationDeliveryStructureBuilder withDefaultCodespace(String defaultCodespace) {
        this.defaultCodespace = defaultCodespace.toUpperCase();
        return this;
    }

    public PublicationDeliveryStructureBuilder withCompositeFrameCreationDate(LocalDateTime compositeFrameCreationDate) {
        this.compositeFrameCreationDate = compositeFrameCreationDate;
        return this;
    }

    public PublicationDeliveryStructureBuilder withPublicationDeliveryTimestamp(LocalDateTime publicationDeliveryTimestamp) {
        this.publicationDeliveryTimestamp = publicationDeliveryTimestamp;
        return this;
    }

    public PublicationDeliveryStructureBuilder withDescription(String description) {
        this.description = description;
        return this;
    }

    public PublicationDeliveryStructureBuilder withValidityConditions(ValidityConditions_RelStructure validityConditions) {
        this.validityConditions = validityConditions;
        return this;
    }

    public PublicationDeliveryStructureBuilder withFrameDefaults(VersionFrameDefaultsStructure frameDefaults) {
        this.frameDefaults = frameDefaults;
        return this;
    }

    public PublicationDeliveryStructureBuilder withCodespaces(Codespaces_RelStructure codespaces) {
        this.codespaces = codespaces;
        return this;
    }


    public PublicationDeliveryStructure build() {

        PublicationDeliveryStructure publicationDeliveryStructure = objectFactory.createPublicationDeliveryStructure();
        PublicationDeliveryStructure.DataObjects dataObjects = objectFactory.createPublicationDeliveryStructureDataObjects();
        publicationDeliveryStructure.setDataObjects(dataObjects);


        publicationDeliveryStructure.setDescription(objectFactory.createMultilingualString().withValue(description));
        publicationDeliveryStructure.setParticipantRef(NETEX_PARTICIPANT_REF);
        publicationDeliveryStructure.setPublicationTimestamp(publicationDeliveryTimestamp);
        publicationDeliveryStructure.setVersion(NETEX_VERSION);


        CompositeFrame compositeFrame = objectFactory.createCompositeFrame();

        compositeFrame.setId(defaultCodespace + ":CompositeFrame:" + DEFAULT_FRAME_ID);
        compositeFrame.setVersion(DEFAULT_FRAME_VERSION);
        compositeFrame.setCodespaces(codespaces);
        compositeFrame.setValidityConditions(validityConditions);
        compositeFrame.setFrameDefaults(frameDefaults);
        compositeFrame.setCreated(compositeFrameCreationDate);
        Frames_RelStructure framesRelStructure = objectFactory.createFrames_RelStructure();
        compositeFrame.setFrames(framesRelStructure);
        dataObjects.getCompositeFrameOrCommonFrame().add(wrapAsJAXBElement(compositeFrame));

        return publicationDeliveryStructure;


    }


}

