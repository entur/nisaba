package no.entur.nisaba.routes.netex.publication;

import org.apache.camel.Header;
import org.entur.netex.index.api.NetexEntitiesIndex;
import org.rutebanken.netex.model.Common_VersionFrameStructure;
import org.rutebanken.netex.model.CompositeFrame;
import org.rutebanken.netex.model.DataObjectDeliveryStructure;
import org.rutebanken.netex.model.DayTypeAssignmentsInFrame_RelStructure;
import org.rutebanken.netex.model.DayTypesInFrame_RelStructure;
import org.rutebanken.netex.model.Frames_RelStructure;
import org.rutebanken.netex.model.JourneyPattern;
import org.rutebanken.netex.model.JourneyPatternsInFrame_RelStructure;
import org.rutebanken.netex.model.JourneysInFrame_RelStructure;
import org.rutebanken.netex.model.Line;
import org.rutebanken.netex.model.LinesInFrame_RelStructure;
import org.rutebanken.netex.model.ObjectFactory;
import org.rutebanken.netex.model.OperatingDaysInFrame_RelStructure;
import org.rutebanken.netex.model.OperatingPeriodsInFrame_RelStructure;
import org.rutebanken.netex.model.PassengerStopAssignment;
import org.rutebanken.netex.model.PublicationDeliveryStructure;
import org.rutebanken.netex.model.Route;
import org.rutebanken.netex.model.RoutePointsInFrame_RelStructure;
import org.rutebanken.netex.model.RoutesInFrame_RelStructure;
import org.rutebanken.netex.model.ScheduledStopPointsInFrame_RelStructure;
import org.rutebanken.netex.model.ServiceCalendarFrame;
import org.rutebanken.netex.model.ServiceFrame;
import org.rutebanken.netex.model.ServiceJourney;
import org.rutebanken.netex.model.StopAssignment_VersionStructure;
import org.rutebanken.netex.model.StopAssignmentsInFrame_RelStructure;
import org.rutebanken.netex.model.TimetableFrame;
import org.rutebanken.netex.model.VersionOfObjectRefStructure;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.xml.bind.JAXBElement;
import javax.xml.namespace.QName;
import java.util.Collection;
import java.util.List;
import java.util.stream.Collectors;

import static no.entur.nisaba.Constants.PUBLICATION_DELIVERY_TEMPLATE;

public class PublicationDeliveryUpdater {

    private static final Logger LOGGER = LoggerFactory.getLogger(PublicationDeliveryUpdater.class);

    private ObjectFactory objectFactory = new ObjectFactory();


    public PublicationDeliveryStructure update(@Header(PUBLICATION_DELIVERY_TEMPLATE) PublicationDeliveryStructure templatePublicationDeliveryStructure,
                       @Header("COMMON_FILE_INDEX") NetexEntitiesIndex commonEntities,
                       @Header("LINE_FILE_INDEX") NetexEntitiesIndex lineEntities,
                       @Header("SERVICE_JOURNEY_ID") String serviceJourneyId) {

        PublicationDeliveryStructure publicationDeliveryStructure = createPublicationDeliveryStructure(templatePublicationDeliveryStructure);


        ServiceJourney serviceJourney = lineEntities.getServiceJourneyIndex().get(serviceJourneyId);
        ServiceJourneyReferencedEntities serviceJourneyReferencedEntities = new ServiceJourneyReferencedEntities(serviceJourney, commonEntities);

        JourneyPattern journeyPattern = lineEntities.getJourneyPatternIndex().get(serviceJourney.getJourneyPatternRef().getValue().getRef());
        JourneyPatternReferencedEntities journeyPatternReferencedEntities = new JourneyPatternReferencedEntities(journeyPattern, commonEntities);

        Route route = lineEntities.getRouteIndex().get(journeyPattern.getRouteRef().getRef());
        RouteReferencedEntities routeReferencedEntities = new RouteReferencedEntities(route, commonEntities);

        // service frame

        ServiceFrame serviceFrame = getServiceFrame(publicationDeliveryStructure);

        LinesInFrame_RelStructure linesInFrameRelStructure = objectFactory.createLinesInFrame_RelStructure();
        List<JAXBElement<Line>> lines = lineEntities.getLineIndex().getAll().stream().map(this::wrapAsJAXBElement).collect(Collectors.toList());
        linesInFrameRelStructure.getLine_().addAll(lines);
        serviceFrame.setLines(linesInFrameRelStructure);

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

        TimetableFrame timetableFrame = objectFactory.createTimetableFrame().withId("id").withVersion("version");
        getFrames(publicationDeliveryStructure).add(wrapAsJAXBElement(timetableFrame));

        JourneysInFrame_RelStructure journeysInFrameRelStructure = objectFactory.createJourneysInFrame_RelStructure();
        journeysInFrameRelStructure.getVehicleJourneyOrDatedVehicleJourneyOrNormalDatedVehicleJourney().add(serviceJourney);
        timetableFrame.setVehicleJourneys(journeysInFrameRelStructure);



        // service calendar frame

        ServiceCalendarFrame serviceCalendarFrame = objectFactory.createServiceCalendarFrame().withId("id").withVersion("version");
        getFrames(publicationDeliveryStructure).add(wrapAsJAXBElement(serviceCalendarFrame));

        DayTypesInFrame_RelStructure dayTypesInFrameRelStructure = objectFactory.createDayTypesInFrame_RelStructure();
        dayTypesInFrameRelStructure.getDayType_().addAll(serviceJourneyReferencedEntities.getDayTypes().stream().map(dayType -> wrapAsJAXBElement(dayType)).collect(Collectors.toList()));
        serviceCalendarFrame.setDayTypes(dayTypesInFrameRelStructure);

        DayTypeAssignmentsInFrame_RelStructure dayTypeAssignmentsInFrameRelStructure = objectFactory.createDayTypeAssignmentsInFrame_RelStructure();
        dayTypeAssignmentsInFrameRelStructure.getDayTypeAssignment().addAll(serviceJourneyReferencedEntities.getDayTypeAssignments());
        serviceCalendarFrame.setDayTypeAssignments(dayTypeAssignmentsInFrameRelStructure);

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

    private PublicationDeliveryStructure createPublicationDeliveryStructure(PublicationDeliveryStructure templatePublicationDeliveryStructure) {

        CompositeFrame compositeFrame = objectFactory.createCompositeFrame();
        CompositeFrame templateCompositeFrame = getCompositeFrame(templatePublicationDeliveryStructure);
        compositeFrame.setId(templateCompositeFrame.getId());
        compositeFrame.setVersion(templateCompositeFrame.getVersion());
        compositeFrame.setCodespaces(templateCompositeFrame.getCodespaces());
        compositeFrame.setValidityConditions(templateCompositeFrame.getValidityConditions());
        compositeFrame.setFrameDefaults(templateCompositeFrame.getFrameDefaults());
        Frames_RelStructure frames_relStructure = objectFactory.createFrames_RelStructure();
        compositeFrame.setFrames(frames_relStructure);


        ServiceFrame serviceFrame = objectFactory.createServiceFrame();
        compositeFrame.getFrames().getCommonFrame().add(wrapAsJAXBElement(serviceFrame));

        PublicationDeliveryStructure.DataObjects dataObjects = objectFactory.createPublicationDeliveryStructureDataObjects();
        dataObjects.getCompositeFrameOrCommonFrame().add(wrapAsJAXBElement(compositeFrame));

        PublicationDeliveryStructure publicationDeliveryStructure = objectFactory.createPublicationDeliveryStructure();
        publicationDeliveryStructure.setDataObjects(dataObjects);
        publicationDeliveryStructure.setDescription(templatePublicationDeliveryStructure.getDescription());
        publicationDeliveryStructure.setParticipantRef(templatePublicationDeliveryStructure.getParticipantRef());
        publicationDeliveryStructure.setPublicationTimestamp(templatePublicationDeliveryStructure.getPublicationTimestamp());
        publicationDeliveryStructure.setVersion(templatePublicationDeliveryStructure.getVersion());

        return publicationDeliveryStructure;


    }

    private CompositeFrame getCompositeFrame(PublicationDeliveryStructure publicationDeliveryStructure) {
        Common_VersionFrameStructure commonVersionFrameStructure = publicationDeliveryStructure.getDataObjects().getCompositeFrameOrCommonFrame().get(0).getValue();
        return ((CompositeFrame) commonVersionFrameStructure);
    }

    private List<JAXBElement<? extends Common_VersionFrameStructure>> getFrames(PublicationDeliveryStructure publicationDeliveryStructure) {
        return getCompositeFrame(publicationDeliveryStructure).getFrames().getCommonFrame();
    }

    private ServiceFrame getServiceFrame(PublicationDeliveryStructure publicationDeliveryStructure) {
        List<JAXBElement<? extends Common_VersionFrameStructure>> commonFrame = getFrames(publicationDeliveryStructure);
        return (ServiceFrame) commonFrame.stream().filter(jaxbElement -> jaxbElement.getValue() instanceof ServiceFrame).findFirst().get().getValue();
    }

    private TimetableFrame getTimetableFrame(PublicationDeliveryStructure publicationDeliveryStructure) {
        List<JAXBElement<? extends Common_VersionFrameStructure>> commonFrame = getFrames(publicationDeliveryStructure);
        return (TimetableFrame) commonFrame.stream().filter(jaxbElement -> jaxbElement.getValue() instanceof TimetableFrame).findFirst().get().getValue();
    }


    public <E> JAXBElement<E> wrapAsJAXBElement(E entity) {
        if (entity == null) {
            return null;
        }
        return new JAXBElement(new QName("http://www.netex.org.uk/netex", getEntityName(entity)), entity.getClass(), null, entity);
    }

    public static <E> String getEntityName(E entity) {
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

