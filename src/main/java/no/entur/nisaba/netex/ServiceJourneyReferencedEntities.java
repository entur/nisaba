package no.entur.nisaba.netex;

import org.entur.netex.index.api.NetexEntitiesIndex;
import org.rutebanken.netex.model.DayType;
import org.rutebanken.netex.model.DayTypeAssignment;
import org.rutebanken.netex.model.DayTypeRefs_RelStructure;
import org.rutebanken.netex.model.NoticeAssignment;
import org.rutebanken.netex.model.OperatingDay;
import org.rutebanken.netex.model.OperatingPeriod;
import org.rutebanken.netex.model.Operator;
import org.rutebanken.netex.model.OperatorRefStructure;
import org.rutebanken.netex.model.ServiceJourney;

import java.util.Collection;
import java.util.HashSet;
import java.util.Objects;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class ServiceJourneyReferencedEntities {

    private Collection<DayType> dayTypes = new HashSet<>();
    private Collection<DayTypeAssignment> dayTypeAssignments = new HashSet<>();
    private Collection<OperatingPeriod> operatingPeriods = new HashSet<>();
    private Collection<OperatingDay> operatingDays = new HashSet<>();
    private Collection<NoticeAssignment> noticeAssignments;

    private Operator operator;


    public ServiceJourneyReferencedEntities(ServiceJourney serviceJourney, NetexEntitiesIndex netexCommonEntitiesIndex, NetexEntitiesIndex netexLineEntitiesIndex) {

        DayTypeRefs_RelStructure dayTypeRefs = serviceJourney.getDayTypes();
        // ServiceJourneys used together with DatedServiceJourneys do not have day types.
        if(dayTypeRefs != null) {
            dayTypes = dayTypeRefs.getDayTypeRef().stream().map(jaxbElement -> netexCommonEntitiesIndex.getDayTypeIndex().get(jaxbElement.getValue().getRef())).collect(Collectors.toSet());

            dayTypeAssignments = this.dayTypes.stream()
                    .map(dayType -> netexCommonEntitiesIndex.getDayTypeAssignmentsByDayTypeIdIndex().get(dayType.getId()))
                    .flatMap(Collection::stream)
                    .collect(Collectors.toSet());

            operatingDays = dayTypeAssignments.stream()
                    .map(DayTypeAssignment::getOperatingDayRef)
                    .filter(Objects::nonNull)
                    .map(operatingDayRefStructure -> netexCommonEntitiesIndex.getOperatingDayIndex().get(operatingDayRefStructure.getRef()))
                    .collect(Collectors.toSet());

            operatingPeriods = dayTypeAssignments.stream()
                    .map(DayTypeAssignment::getOperatingPeriodRef)
                    .filter(Objects::nonNull)
                    .map(operatingPeriodRefStructure -> netexCommonEntitiesIndex.getOperatingPeriodIndex().get(operatingPeriodRefStructure.getRef()))
                    .collect(Collectors.toSet());
        }

        netexLineEntitiesIndex.getDatedServiceJourneyByServiceJourneyRefIndex()
                .get(serviceJourney.getId())
                .stream()
                .map(datedServiceJourney ->  datedServiceJourney.getOperatingDayRef().getRef())
                .forEach(operatingDayId -> operatingDays.add(netexCommonEntitiesIndex.getOperatingDayIndex().get(operatingDayId)));


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

        noticeAssignments = Stream.concat(noticeAssignmentsOnServiceJourney, noticeAssignmentsOnPassingTimes).collect(Collectors.toList());

        OperatorRefStructure operatorRef = serviceJourney.getOperatorRef();
        if(operatorRef != null) {
            operator = netexCommonEntitiesIndex.getOperatorIndex().get(operatorRef.getRef());
        }
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

}

