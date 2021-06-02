package no.entur.nisaba.routes.netex.publication;

import org.apache.camel.Header;
import org.entur.netex.index.api.NetexEntitiesIndex;
import org.rutebanken.netex.model.Common_VersionFrameStructure;
import org.rutebanken.netex.model.CompositeFrame;
import org.rutebanken.netex.model.DayTypeAssignmentsInFrame_RelStructure;
import org.rutebanken.netex.model.DayTypesInFrame_RelStructure;
import org.rutebanken.netex.model.JourneyPattern;
import org.rutebanken.netex.model.ObjectFactory;
import org.rutebanken.netex.model.OperatingDaysInFrame_RelStructure;
import org.rutebanken.netex.model.OperatingPeriodsInFrame_RelStructure;
import org.rutebanken.netex.model.PassengerStopAssignment;
import org.rutebanken.netex.model.PublicationDeliveryStructure;
import org.rutebanken.netex.model.Route;
import org.rutebanken.netex.model.RoutePointsInFrame_RelStructure;
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

public class PublicationDeliveryUpdater {

    private static final Logger LOGGER = LoggerFactory.getLogger(PublicationDeliveryUpdater.class);

    private ObjectFactory objectFactory = new ObjectFactory();


    public void update(@Header("PUBLICATION_DELIVERY") PublicationDeliveryStructure publicationDeliveryStructure,
                       @Header("COMMON_FILE_INDEX") NetexEntitiesIndex commonEntities,
                       @Header("LINE_FILE_INDEX") NetexEntitiesIndex lineEntities,
                       @Header("SERVICE_JOURNEY_ID") String serviceJourneyId) {
        LOGGER.info("updating " + publicationDeliveryStructure);


        ServiceJourney serviceJourney = lineEntities.getServiceJourneyIndex().get(serviceJourneyId);
        ServiceJourneyReferencedEntities serviceJourneyReferencedEntities = new ServiceJourneyReferencedEntities(serviceJourney, commonEntities);

        JourneyPattern journeyPattern = lineEntities.getJourneyPatternIndex().get(serviceJourney.getJourneyPatternRef().getValue().getRef());
        JourneyPatternReferencedEntities journeyPatternReferencedEntities = new JourneyPatternReferencedEntities(journeyPattern, commonEntities);

        Route route = lineEntities.getRouteIndex().get(journeyPattern.getRouteRef().getRef());
        RouteReferencedEntities routeReferencedEntities = new RouteReferencedEntities(route, commonEntities);

        // service frame

        ServiceFrame serviceFrame = getServiceFrame(publicationDeliveryStructure);

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

    }

    private ServiceFrame getServiceFrame(PublicationDeliveryStructure publicationDeliveryStructure) {
        List<JAXBElement<? extends Common_VersionFrameStructure>> commonFrame = getFrames(publicationDeliveryStructure);
        return (ServiceFrame) commonFrame.stream().filter(jaxbElement -> jaxbElement.getValue() instanceof ServiceFrame).findFirst().get().getValue();
    }

    private List<JAXBElement<? extends Common_VersionFrameStructure>> getFrames(PublicationDeliveryStructure publicationDeliveryStructure) {
        Common_VersionFrameStructure commonVersionFrameStructure = publicationDeliveryStructure.getDataObjects().getCompositeFrameOrCommonFrame().get(0).getValue();
        return ((CompositeFrame) commonVersionFrameStructure).getFrames().getCommonFrame();
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

