package no.entur.nisaba.routes.netex.publication;

import com.google.common.collect.Multimap;
import org.entur.netex.index.api.NetexEntitiesIndex;
import org.rutebanken.netex.model.DayType;
import org.rutebanken.netex.model.DayTypeAssignment;
import org.rutebanken.netex.model.OperatingDay;
import org.rutebanken.netex.model.OperatingPeriod;
import org.rutebanken.netex.model.ScheduledStopPoint;
import org.rutebanken.netex.model.ServiceJourney;

import java.util.Collection;
import java.util.Objects;
import java.util.stream.Collectors;

public class ServiceJourneyReferencedEntities {

    private Collection<ScheduledStopPoint> scheduledStopPoints;
    private Collection<DayType> dayTypes;
    private Collection<DayTypeAssignment> dayTypeAssignments;
    private Collection<OperatingPeriod> operatingPeriods;
    private Collection<OperatingDay> operatingDays;


    public ServiceJourneyReferencedEntities(ServiceJourney serviceJourney, NetexEntitiesIndex netexEntitiesIndex) {

        dayTypes = serviceJourney.getDayTypes().getDayTypeRef().stream().map(jaxbElement -> netexEntitiesIndex.getDayTypeIndex().get(jaxbElement.getValue().getRef())).collect(Collectors.toList());

        dayTypeAssignments = dayTypes.stream()
                .map(dayType -> {
                    Multimap<String, DayTypeAssignment> dayTypeAssignmentsByDayTypeIdIndex = netexEntitiesIndex.getDayTypeAssignmentsByDayTypeIdIndex();
                    String id = dayType.getId();
                    if(id== null) {
                        System.out.println("bb");
                    }
                    Collection<DayTypeAssignment> dayTypeAssignments = dayTypeAssignmentsByDayTypeIdIndex.get(id);
                    if(dayTypeAssignments == null) {
                        System.out.println("aa");
                    }
                    return dayTypeAssignments;
                } )
                .flatMap(Collection::stream)
                .collect(Collectors.toList());

        operatingDays = dayTypeAssignments.stream()
                .map(DayTypeAssignment::getOperatingDayRef)
                .filter(Objects::nonNull)
                .map(operatingDayRefStructure -> netexEntitiesIndex.getOperatingDayIndex().get(operatingDayRefStructure.getRef()))
                .collect(Collectors.toList());

        operatingPeriods = dayTypeAssignments.stream()
                .map(DayTypeAssignment::getOperatingPeriodRef)
                .filter(Objects::nonNull)
                .map(operatingPeriodRefStructure -> netexEntitiesIndex.getOperatingPeriodIndex().get(operatingPeriodRefStructure.getRef()))
                .collect(Collectors.toList());


    }

    public Collection<ScheduledStopPoint> getScheduledStopPoints() {
        return scheduledStopPoints;
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

    public void setScheduledStopPoints(Collection<ScheduledStopPoint> scheduledStopPoints) {
        this.scheduledStopPoints = scheduledStopPoints;
    }

    public void setDayTypes(Collection<DayType> dayTypes) {
        this.dayTypes = dayTypes;
    }

    public void setDayTypeAssignments(Collection<DayTypeAssignment> dayTypeAssignments) {
        this.dayTypeAssignments = dayTypeAssignments;
    }

    public void setOperatingPeriods(Collection<OperatingPeriod> operatingPeriods) {
        this.operatingPeriods = operatingPeriods;
    }

    public void setOperatingDays(Collection<OperatingDay> operatingDays) {
        this.operatingDays = operatingDays;
    }


}

