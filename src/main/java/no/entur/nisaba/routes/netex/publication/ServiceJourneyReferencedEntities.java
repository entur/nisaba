package no.entur.nisaba.routes.netex.publication;

import org.entur.netex.index.api.NetexEntitiesIndex;
import org.rutebanken.netex.model.DayType;
import org.rutebanken.netex.model.DayTypeAssignment;
import org.rutebanken.netex.model.NoticeAssignment;
import org.rutebanken.netex.model.OperatingDay;
import org.rutebanken.netex.model.OperatingPeriod;
import org.rutebanken.netex.model.ServiceJourney;

import java.util.Collection;
import java.util.Objects;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class ServiceJourneyReferencedEntities {

    private Collection<DayType> dayTypes;
    private Collection<DayTypeAssignment> dayTypeAssignments;
    private Collection<OperatingPeriod> operatingPeriods;
    private Collection<OperatingDay> operatingDays;
    private Collection<NoticeAssignment> noticeAssignments;


    public ServiceJourneyReferencedEntities(ServiceJourney serviceJourney, NetexEntitiesIndex netexEntitiesIndex) {

        dayTypes = serviceJourney.getDayTypes().getDayTypeRef().stream().map(jaxbElement -> netexEntitiesIndex.getDayTypeIndex().get(jaxbElement.getValue().getRef())).collect(Collectors.toList());

        dayTypeAssignments = dayTypes.stream()
                .map(dayType -> netexEntitiesIndex.getDayTypeAssignmentsByDayTypeIdIndex().get(dayType.getId()))
                .flatMap(Collection::stream)
                .collect(Collectors.toSet());

        operatingDays = dayTypeAssignments.stream()
                .map(DayTypeAssignment::getOperatingDayRef)
                .filter(Objects::nonNull)
                .map(operatingDayRefStructure -> netexEntitiesIndex.getOperatingDayIndex().get(operatingDayRefStructure.getRef()))
                .collect(Collectors.toSet());

        operatingPeriods = dayTypeAssignments.stream()
                .map(DayTypeAssignment::getOperatingPeriodRef)
                .filter(Objects::nonNull)
                .map(operatingPeriodRefStructure -> netexEntitiesIndex.getOperatingPeriodIndex().get(operatingPeriodRefStructure.getRef()))
                .collect(Collectors.toSet());


        Stream<NoticeAssignment> noticeAssignmentsOnServiceJourney = netexEntitiesIndex
                .getNoticeAssignmentIndex()
                .getAll()
                .stream()
                .filter(noticeAssignment -> noticeAssignment.getNoticedObjectRef().getRef().equals(serviceJourney.getId()));

        Stream<NoticeAssignment> noticeAssignmentsOnPassingTimes = netexEntitiesIndex
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

}

