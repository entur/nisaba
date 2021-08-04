package no.entur.nisaba.netex;

import com.google.common.collect.Streams;
import org.apache.camel.Header;
import org.entur.netex.index.api.NetexEntitiesIndex;
import org.rutebanken.netex.model.Common_VersionFrameStructure;
import org.rutebanken.netex.model.CompositeFrame;
import org.rutebanken.netex.model.DayTypeAssignmentsInFrame_RelStructure;
import org.rutebanken.netex.model.DayTypesInFrame_RelStructure;
import org.rutebanken.netex.model.FlexibleLine;
import org.rutebanken.netex.model.Frames_RelStructure;
import org.rutebanken.netex.model.JourneyInterchangesInFrame_RelStructure;
import org.rutebanken.netex.model.JourneyPattern;
import org.rutebanken.netex.model.JourneyPatternsInFrame_RelStructure;
import org.rutebanken.netex.model.JourneysInFrame_RelStructure;
import org.rutebanken.netex.model.Line;
import org.rutebanken.netex.model.LinesInFrame_RelStructure;
import org.rutebanken.netex.model.NoticeAssignment;
import org.rutebanken.netex.model.NoticeAssignmentsInFrame_RelStructure;
import org.rutebanken.netex.model.ObjectFactory;
import org.rutebanken.netex.model.OperatingDaysInFrame_RelStructure;
import org.rutebanken.netex.model.OperatingPeriodsInFrame_RelStructure;
import org.rutebanken.netex.model.Operator;
import org.rutebanken.netex.model.OrganisationsInFrame_RelStructure;
import org.rutebanken.netex.model.PassengerStopAssignment;
import org.rutebanken.netex.model.PublicationDeliveryStructure;
import org.rutebanken.netex.model.ResourceFrame;
import org.rutebanken.netex.model.Route;
import org.rutebanken.netex.model.RoutePointsInFrame_RelStructure;
import org.rutebanken.netex.model.RoutesInFrame_RelStructure;
import org.rutebanken.netex.model.ScheduledStopPointsInFrame_RelStructure;
import org.rutebanken.netex.model.ServiceCalendarFrame;
import org.rutebanken.netex.model.ServiceFrame;
import org.rutebanken.netex.model.ServiceJourney;
import org.rutebanken.netex.model.ServiceJourneyInterchange;
import org.rutebanken.netex.model.StopAssignment_VersionStructure;
import org.rutebanken.netex.model.StopAssignmentsInFrame_RelStructure;
import org.rutebanken.netex.model.TimetableFrame;
import org.rutebanken.netex.model.TypesOfValueInFrame_RelStructure;
import org.rutebanken.netex.model.VersionOfObjectRefStructure;

import javax.xml.bind.JAXBElement;
import javax.xml.namespace.QName;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static no.entur.nisaba.Constants.COMMON_FILE_INDEX;
import static no.entur.nisaba.Constants.DATASET_CODESPACE;
import static no.entur.nisaba.Constants.JOURNEY_PATTERN_REFERENCES;
import static no.entur.nisaba.Constants.LINE_FILE_INDEX;
import static no.entur.nisaba.Constants.LINE_REFERENCES;
import static no.entur.nisaba.Constants.PUBLICATION_DELIVERY_TIMESTAMP;
import static no.entur.nisaba.Constants.ROUTE_REFERENCES;
import static no.entur.nisaba.Constants.SERVICE_JOURNEY_ID;

public class PublicationDeliveryBuilder {

    private static final String NETEX_VERSION = "1.12:NO-NeTEx-networktimetable:1.3";
    private static final String NETEX_PARTICIPANT_REF = "RB";
    private static final String DEFAULT_FRAME_VERSION = "1";

    private final ObjectFactory objectFactory = new ObjectFactory();


    public PublicationDeliveryStructure build(
            @Header(DATASET_CODESPACE) String codespace,
            @Header(COMMON_FILE_INDEX) NetexEntitiesIndex commonEntities,
            @Header(LINE_FILE_INDEX) NetexEntitiesIndex lineEntities,
            @Header(SERVICE_JOURNEY_ID) String serviceJourneyId,
            @Header(ROUTE_REFERENCES) RouteReferencedEntities routeReferencedEntities,
            @Header(JOURNEY_PATTERN_REFERENCES) JourneyPatternReferencedEntities journeyPatternReferencedEntities,
            @Header(PUBLICATION_DELIVERY_TIMESTAMP) LocalDateTime publicationDeliveryTimestamp,
            @Header(LINE_REFERENCES) LineReferencedEntities lineReferencedEntities) {


        ServiceJourney serviceJourney = lineEntities.getServiceJourneyIndex().get(serviceJourneyId);

        ServiceJourneyReferencedEntities serviceJourneyReferencedEntities = new ServiceJourneyReferencedEntities.ServiceJourneyReferencedEntitiesBuilder()
                .withServiceJourney(serviceJourney)
                .withNetexCommonEntitiesIndex(commonEntities)
                .withNetexLineEntitiesIndex(lineEntities)
                .build();

        JourneyPattern journeyPattern = journeyPatternReferencedEntities.getJourneyPattern();
        Route route = routeReferencedEntities.getRoute();


        // publication delivery
        PublicationDeliveryStructure publicationDeliveryStructure = createPublicationDeliveryStructure(lineReferencedEntities, lineEntities, publicationDeliveryTimestamp);


        // resource frame
        ResourceFrame resourceFrame = objectFactory.createResourceFrame();
        resourceFrame.withId(codespace + ":ResourceFrame:1").withVersion(DEFAULT_FRAME_VERSION);
        OrganisationsInFrame_RelStructure organisationsInFrameRelStructure = objectFactory.createOrganisationsInFrame_RelStructure();

        Set<Operator> operators = new HashSet<>();
        Operator lineOperator = lineReferencedEntities.getOperator();
        if (lineOperator != null) {
            operators.add(lineOperator);
        }
        Operator serviceJourneyOperator = serviceJourneyReferencedEntities.getOperator();
        if (serviceJourneyOperator != null) {
            operators.add(serviceJourneyOperator);
        }
        organisationsInFrameRelStructure.getOrganisation_().addAll(operators.stream().map(this::wrapAsJAXBElement).collect(Collectors.toList()));
        organisationsInFrameRelStructure.getOrganisation_().add(wrapAsJAXBElement(lineReferencedEntities.getAuthority()));
        resourceFrame.setOrganisations(organisationsInFrameRelStructure);

        if (lineReferencedEntities.getBranding() != null) {
            TypesOfValueInFrame_RelStructure typesOfValueInFrameRelStructure = objectFactory.createTypesOfValueInFrame_RelStructure();
            typesOfValueInFrameRelStructure.getValueSetOrTypeOfValue().add(wrapAsJAXBElement(lineReferencedEntities.getBranding()));
            resourceFrame.setTypesOfValue(typesOfValueInFrameRelStructure);
        }

        getFrames(publicationDeliveryStructure).add(wrapAsJAXBElement(resourceFrame));


        // service frame

        ServiceFrame serviceFrame = objectFactory.createServiceFrame();
        ServiceFrame sourceServiceFrame = lineEntities.getServiceFrames().stream().findFirst().orElseThrow();
        serviceFrame.setId(sourceServiceFrame.getId());
        serviceFrame.setVersion(sourceServiceFrame.getVersion());
        getFrames(publicationDeliveryStructure).add(wrapAsJAXBElement(serviceFrame));


        LinesInFrame_RelStructure linesInFrameRelStructure = objectFactory.createLinesInFrame_RelStructure();
        List<JAXBElement<Line>> lines = lineEntities.getLineIndex().getAll().stream().map(this::wrapAsJAXBElement).collect(Collectors.toList());
        linesInFrameRelStructure.getLine_().addAll(lines);
        List<JAXBElement<FlexibleLine>> flexibleLines = lineEntities.getFlexibleLineIndex().getAll().stream().map(this::wrapAsJAXBElement).collect(Collectors.toList());
        linesInFrameRelStructure.getLine_().addAll(flexibleLines);

        serviceFrame.setLines(linesInFrameRelStructure);
        serviceFrame.setNetwork(lineReferencedEntities.getNetwork());


        RoutesInFrame_RelStructure routesInFrameRelStructure = objectFactory.createRoutesInFrame_RelStructure();
        routesInFrameRelStructure.getRoute_().add(wrapAsJAXBElement(route));
        serviceFrame.setRoutes(routesInFrameRelStructure);

        JourneyPatternsInFrame_RelStructure journeyPatternsInFrameRelStructure = objectFactory.createJourneyPatternsInFrame_RelStructure();
        journeyPatternsInFrameRelStructure.getJourneyPattern_OrJourneyPatternView().add(wrapAsJAXBElement(journeyPattern));
        serviceFrame.setJourneyPatterns(journeyPatternsInFrameRelStructure);

        ScheduledStopPointsInFrame_RelStructure scheduledStopPointsInFrameRelStructure = objectFactory.createScheduledStopPointsInFrame_RelStructure();
        scheduledStopPointsInFrameRelStructure.getScheduledStopPoint().addAll(journeyPatternReferencedEntities.getScheduledStopPoints());
        serviceFrame.setScheduledStopPoints(scheduledStopPointsInFrameRelStructure);

        StopAssignmentsInFrame_RelStructure stopAssignmentsInFrameRelStructure = objectFactory.createStopAssignmentsInFrame_RelStructure();
        Collection<PassengerStopAssignment> passengerStopAssignments = journeyPatternReferencedEntities.getPassengerStopAssignments();

        Collection<? extends JAXBElement<? extends StopAssignment_VersionStructure>> wrappedPassengerStopAssignments = passengerStopAssignments.stream().map(this::wrapAsJAXBElement).collect(Collectors.toList());
        stopAssignmentsInFrameRelStructure.getStopAssignment().addAll(wrappedPassengerStopAssignments);
        serviceFrame.setStopAssignments(stopAssignmentsInFrameRelStructure);

        RoutePointsInFrame_RelStructure routePointsInFrameRelStructure = objectFactory.createRoutePointsInFrame_RelStructure();
        routePointsInFrameRelStructure.getRoutePoint().addAll(routeReferencedEntities.getRoutePoints());
        serviceFrame.setRoutePoints(routePointsInFrameRelStructure);

        // timetable frame

        TimetableFrame timetableFrame = objectFactory.createTimetableFrame().withId(codespace + ":TimetableFrame:1").withVersion(DEFAULT_FRAME_VERSION);
        getFrames(publicationDeliveryStructure).add(wrapAsJAXBElement(timetableFrame));

        JourneysInFrame_RelStructure journeysInFrameRelStructure = objectFactory.createJourneysInFrame_RelStructure();
        journeysInFrameRelStructure.getVehicleJourneyOrDatedVehicleJourneyOrNormalDatedVehicleJourney().add(serviceJourney);
        journeysInFrameRelStructure.getVehicleJourneyOrDatedVehicleJourneyOrNormalDatedVehicleJourney().addAll(lineEntities.getDatedServiceJourneyByServiceJourneyRefIndex().get(serviceJourneyId));
        timetableFrame.setVehicleJourneys(journeysInFrameRelStructure);

        Collection<ServiceJourneyInterchange> serviceJourneyInterchanges = lineEntities.getServiceJourneyInterchangeByServiceJourneyRefIndex().get(serviceJourneyId);
        serviceJourneyInterchanges.forEach(serviceJourneyInterchange -> {
            serviceJourneyInterchange.getFromJourneyRef().setVersion(null);
            serviceJourneyInterchange.getToJourneyRef().setVersion(null);
        });
        if (!serviceJourneyInterchanges.isEmpty()) {
            JourneyInterchangesInFrame_RelStructure journeyInterchangesInFrameRelStructure = objectFactory.createJourneyInterchangesInFrame_RelStructure();
            journeyInterchangesInFrameRelStructure.getServiceJourneyPatternInterchangeOrServiceJourneyInterchange().addAll(serviceJourneyInterchanges);
            timetableFrame.setJourneyInterchanges(journeyInterchangesInFrameRelStructure);
        }

        Collection<NoticeAssignment> noticeAssignments = Streams.concat(serviceJourneyReferencedEntities.getNoticeAssignments().stream(),
                journeyPatternReferencedEntities.getNoticeAssignments().stream()).collect(Collectors.toList());
        if (!noticeAssignments.isEmpty()) {
            NoticeAssignmentsInFrame_RelStructure noticeAssignmentsInFrameRelStructure = objectFactory.createNoticeAssignmentsInFrame_RelStructure();
            noticeAssignmentsInFrameRelStructure.getNoticeAssignment_().addAll(noticeAssignments.stream().map(this::wrapAsJAXBElement).collect(Collectors.toList()));
            timetableFrame.setNoticeAssignments(noticeAssignmentsInFrameRelStructure);
        }

        // service calendar frame

        ServiceCalendarFrame serviceCalendarFrame = objectFactory.createServiceCalendarFrame().withId(codespace + ":ServiceCalendarFrame:1").withVersion(DEFAULT_FRAME_VERSION);
        getFrames(publicationDeliveryStructure).add(wrapAsJAXBElement(serviceCalendarFrame));

        // if the service journey is used together with DatedServiceJourneys then no day types are defined
        if (!serviceJourneyReferencedEntities.getDayTypes().isEmpty()) {
            DayTypesInFrame_RelStructure dayTypesInFrameRelStructure = objectFactory.createDayTypesInFrame_RelStructure();
            dayTypesInFrameRelStructure.getDayType_().addAll(serviceJourneyReferencedEntities.getDayTypes().stream().map(this::wrapAsJAXBElement).collect(Collectors.toList()));
            serviceCalendarFrame.setDayTypes(dayTypesInFrameRelStructure);

            DayTypeAssignmentsInFrame_RelStructure dayTypeAssignmentsInFrameRelStructure = objectFactory.createDayTypeAssignmentsInFrame_RelStructure();
            dayTypeAssignmentsInFrameRelStructure.getDayTypeAssignment().addAll(serviceJourneyReferencedEntities.getDayTypeAssignments());
            serviceCalendarFrame.setDayTypeAssignments(dayTypeAssignmentsInFrameRelStructure);

        }

        if (!serviceJourneyReferencedEntities.getOperatingPeriods().isEmpty()) {
            OperatingPeriodsInFrame_RelStructure operatingPeriodsInFrameRelStructure = objectFactory.createOperatingPeriodsInFrame_RelStructure();
            operatingPeriodsInFrameRelStructure.getOperatingPeriodOrUicOperatingPeriod().addAll(serviceJourneyReferencedEntities.getOperatingPeriods());
            serviceCalendarFrame.setOperatingPeriods(operatingPeriodsInFrameRelStructure);
        }

        if (!serviceJourneyReferencedEntities.getOperatingDays().isEmpty()) {
            OperatingDaysInFrame_RelStructure operatingDaysInFrameRelStructure = objectFactory.createOperatingDaysInFrame_RelStructure();
            operatingDaysInFrameRelStructure.getOperatingDay().addAll(serviceJourneyReferencedEntities.getOperatingDays());
            serviceCalendarFrame.setOperatingDays(operatingDaysInFrameRelStructure);

        }

        return publicationDeliveryStructure;

    }

    private PublicationDeliveryStructure createPublicationDeliveryStructure(LineReferencedEntities lineReferencedEntities, NetexEntitiesIndex netexLineEntitiesIndex, LocalDateTime publicationDeliveryTimestamp) {

        PublicationDeliveryStructure publicationDeliveryStructure = objectFactory.createPublicationDeliveryStructure();
        PublicationDeliveryStructure.DataObjects dataObjects = objectFactory.createPublicationDeliveryStructureDataObjects();
        publicationDeliveryStructure.setDataObjects(dataObjects);

        String lineName;
        if (lineReferencedEntities.getLine() != null) {
            lineName = lineReferencedEntities.getLine().getName().getValue();
        } else {
            lineName = lineReferencedEntities.getFlexibleLine().getName().getValue();
        }

        publicationDeliveryStructure.setDescription(objectFactory.createMultilingualString().withValue(lineName));
        publicationDeliveryStructure.setParticipantRef(NETEX_PARTICIPANT_REF);
        publicationDeliveryStructure.setPublicationTimestamp(publicationDeliveryTimestamp);
        publicationDeliveryStructure.setVersion(NETEX_VERSION);


        CompositeFrame compositeFrame = objectFactory.createCompositeFrame();
        CompositeFrame sourceCompositeFrame = netexLineEntitiesIndex.getCompositeFrames().stream().findFirst().orElseThrow();
        compositeFrame.setId(sourceCompositeFrame.getId());
        compositeFrame.setVersion(sourceCompositeFrame.getVersion());
        compositeFrame.setCodespaces(sourceCompositeFrame.getCodespaces());
        compositeFrame.setValidityConditions(sourceCompositeFrame.getValidityConditions());
        compositeFrame.setFrameDefaults(sourceCompositeFrame.getFrameDefaults());
        Frames_RelStructure framesRelStructure = objectFactory.createFrames_RelStructure();
        compositeFrame.setFrames(framesRelStructure);
        dataObjects.getCompositeFrameOrCommonFrame().add(wrapAsJAXBElement(compositeFrame));

        return publicationDeliveryStructure;


    }

    private CompositeFrame getCompositeFrame(PublicationDeliveryStructure publicationDeliveryStructure) {
        Common_VersionFrameStructure commonVersionFrameStructure = publicationDeliveryStructure.getDataObjects().getCompositeFrameOrCommonFrame().get(0).getValue();
        return ((CompositeFrame) commonVersionFrameStructure);
    }

    private List<JAXBElement<? extends Common_VersionFrameStructure>> getFrames(PublicationDeliveryStructure publicationDeliveryStructure) {
        return getCompositeFrame(publicationDeliveryStructure).getFrames().getCommonFrame();
    }

    private <E> JAXBElement<E> wrapAsJAXBElement(E entity) {
        if (entity == null) {
            return null;
        }
        return new JAXBElement(new QName("http://www.netex.org.uk/netex", getEntityName(entity)), entity.getClass(), null, entity);
    }

    private static <E> String getEntityName(E entity) {
        String localPart = entity.getClass().getSimpleName();

        if (entity instanceof VersionOfObjectRefStructure) {
            // Assuming all VersionOfObjectRefStructure subclasses is named as correct element + suffix ("RefStructure""))
            if (localPart.endsWith("Structure")) {
                localPart = localPart.substring(0, localPart.lastIndexOf("Structure"));
            }
        }
        return localPart;
    }


}

