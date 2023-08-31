package no.entur.nisaba.netex;

import no.entur.nisaba.exceptions.NisabaException;
import org.entur.netex.index.api.NetexEntitiesIndex;
import org.rutebanken.netex.model.DayType;
import org.rutebanken.netex.model.DayTypeAssignment;
import org.rutebanken.netex.model.DayTypeRefStructure;
import org.rutebanken.netex.model.DayTypeRefs_RelStructure;
import org.rutebanken.netex.model.NoticeAssignment;
import org.rutebanken.netex.model.OperatingDay;
import org.rutebanken.netex.model.OperatingPeriod;
import org.rutebanken.netex.model.Operator;
import org.rutebanken.netex.model.OperatorRefStructure;
import org.rutebanken.netex.model.ServiceJourney;

import jakarta.xml.bind.JAXBElement;
import java.util.Collection;
import java.util.HashSet;
import java.util.Objects;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * NeTEx entities referenced by a ServiceJourney.
 * The entities are looked up either in the current PublicationDelivery or in in the common files.
 * When found in the common files, the NeTEx version is added back to the reference in the current PublicationDelivery.
 */
public class ServiceJourneyReferencedEntities {

    private Collection<DayType> dayTypes = new HashSet<>();
    private Collection<DayTypeAssignment> dayTypeAssignments = new HashSet<>();
    private Collection<OperatingPeriod> operatingPeriods = new HashSet<>();
    private Collection<OperatingDay> operatingDays = new HashSet<>();
    private Collection<NoticeAssignment> noticeAssignments;
    private Operator operator;

    private ServiceJourneyReferencedEntities() {
    }

    public Collection<DayType> getDayTypes() {
        return dayTypes;
    }

    public Collection<DayTypeAssignment> getDayTypeAssignments() {
        return dayTypeAssignments;
    }

    public Collection<OperatingPeriod> getOperatingPeriods() {
        return operatingPeriods;
    }

    public Collection<OperatingDay> getOperatingDays() {
        return operatingDays;
    }

    public Collection<NoticeAssignment> getNoticeAssignments() {
        return noticeAssignments;
    }

    public Operator getOperator() {
        return operator;
    }

    public static class ServiceJourneyReferencedEntitiesBuilder {

        private ServiceJourney serviceJourney;
        private NetexEntitiesIndex netexCommonEntitiesIndex;
        private NetexEntitiesIndex netexLineEntitiesIndex;

        public ServiceJourneyReferencedEntitiesBuilder withServiceJourney(ServiceJourney serviceJourney) {
            this.serviceJourney = serviceJourney;
            return this;
        }

        public ServiceJourneyReferencedEntitiesBuilder withNetexCommonEntitiesIndex(NetexEntitiesIndex netexCommonEntitiesIndex) {
            this.netexCommonEntitiesIndex = netexCommonEntitiesIndex;
            return this;
        }

        public ServiceJourneyReferencedEntitiesBuilder withNetexLineEntitiesIndex(NetexEntitiesIndex netexLineEntitiesIndex) {
            this.netexLineEntitiesIndex = netexLineEntitiesIndex;
            return this;
        }


        public ServiceJourneyReferencedEntities build() {

            ServiceJourneyReferencedEntities serviceJourneyReferencedEntities = new ServiceJourneyReferencedEntities();

            DayTypeRefs_RelStructure dayTypeRefs = serviceJourney.getDayTypes();
            // ServiceJourneys used together with DatedServiceJourneys do not have day types.
            if (dayTypeRefs != null) {

                serviceJourneyReferencedEntities.dayTypes = dayTypeRefs.getDayTypeRef().stream().map(dayTypeRef -> getDayTypeAndUpdateVersion(netexCommonEntitiesIndex, dayTypeRef)).collect(Collectors.toSet());

                serviceJourneyReferencedEntities.dayTypeAssignments = serviceJourneyReferencedEntities.dayTypes.stream()
                        .map(dayType -> netexCommonEntitiesIndex.getDayTypeAssignmentsByDayTypeIdIndex().get(dayType.getId()))
                        .flatMap(Collection::stream)
                        .collect(Collectors.toSet());

                serviceJourneyReferencedEntities.operatingDays = serviceJourneyReferencedEntities.dayTypeAssignments.stream()
                        .map(DayTypeAssignment::getOperatingDayRef)
                        .filter(Objects::nonNull)
                        .map(operatingDayRefStructure -> netexCommonEntitiesIndex.getOperatingDayIndex().get(operatingDayRefStructure.getRef()))
                        .collect(Collectors.toSet());

                serviceJourneyReferencedEntities.operatingPeriods = serviceJourneyReferencedEntities.dayTypeAssignments.stream()
                        .map(DayTypeAssignment::getOperatingPeriodRef)
                        .filter(Objects::nonNull)
                        .map(operatingPeriodRefStructure -> netexCommonEntitiesIndex.getOperatingPeriodIndex().get(operatingPeriodRefStructure.getRef()))
                        .collect(Collectors.toSet());
            }

            netexLineEntitiesIndex.getDatedServiceJourneyByServiceJourneyRefIndex()
                    .get(serviceJourney.getId())
                    .stream()
                    .map(datedServiceJourney -> datedServiceJourney.getOperatingDayRef().getRef())
                    .forEach(operatingDayId -> serviceJourneyReferencedEntities.operatingDays.add(netexCommonEntitiesIndex.getOperatingDayIndex().get(operatingDayId)));


            Stream<NoticeAssignment> noticeAssignmentsOnServiceJourney = netexLineEntitiesIndex
                    .getNoticeAssignmentIndex()
                    .getAll()
                    .stream()
                    .filter(noticeAssignment -> noticeAssignment.getNoticedObjectRef().getRef().equals(serviceJourney.getId()));

            Stream<NoticeAssignment> noticeAssignmentsOnPassingTimes = netexLineEntitiesIndex
                    .getNoticeAssignmentIndex()
                    .getAll()
                    .stream()
                    .filter(noticeAssignment -> serviceJourney.getPassingTimes()
                            .getTimetabledPassingTime()
                            .stream()
                            .anyMatch(timetabledPassingTime -> noticeAssignment.getNoticedObjectRef()
                                    .getRef()
                                    .equals(timetabledPassingTime.getId())));

            serviceJourneyReferencedEntities.noticeAssignments = Stream.concat(noticeAssignmentsOnServiceJourney, noticeAssignmentsOnPassingTimes).toList();

            OperatorRefStructure operatorRef = serviceJourney.getOperatorRef();
            if (operatorRef != null) {
                Operator operator = netexCommonEntitiesIndex.getOperatorIndex().get(operatorRef.getRef());
                if(operator == null) {
                    throw new NisabaException("Unknown operator " + operatorRef.getRef() + " for service journey " + serviceJourney.getId());
                }
                serviceJourneyReferencedEntities.operator = operator;
                operatorRef.setVersion(operator.getVersion());
            }

            return serviceJourneyReferencedEntities;
        }

        private static DayType getDayTypeAndUpdateVersion(NetexEntitiesIndex netexCommonEntitiesIndex, JAXBElement<? extends DayTypeRefStructure> dayTypeRef) {
            DayType dayType = netexCommonEntitiesIndex.getDayTypeIndex().get(dayTypeRef.getValue().getRef());
            dayTypeRef.getValue().setVersion(dayType.getVersion());
            return dayType;
        }

    }

}

