package no.entur.nisaba.routes.netex.publication;

import org.entur.netex.index.api.NetexEntitiesIndex;
import org.rutebanken.netex.model.JourneyPattern;
import org.rutebanken.netex.model.PassengerStopAssignment;
import org.rutebanken.netex.model.ScheduledStopPoint;
import org.rutebanken.netex.model.StopPointInJourneyPattern;

import java.util.Collection;
import java.util.stream.Collectors;

public class JourneyPatternReferencedEntities {

    private Collection<ScheduledStopPoint> scheduledStopPoints;

    private Collection<PassengerStopAssignment> passengerStopAssignments;


    public JourneyPatternReferencedEntities(JourneyPattern journeyPattern, NetexEntitiesIndex netexEntitiesIndex) {


        scheduledStopPoints = journeyPattern.getPointsInSequence()
                .getPointInJourneyPatternOrStopPointInJourneyPatternOrTimingPointInJourneyPattern()
                .stream()
                .map(pointInLinkSequence -> ((StopPointInJourneyPattern) pointInLinkSequence).getScheduledStopPointRef().getValue().getRef())
                .map(scheduledStopPointId -> netexEntitiesIndex.getScheduledStopPointIndex().get(scheduledStopPointId))
                .collect(Collectors.toSet());

        passengerStopAssignments = scheduledStopPoints.stream()
                .map(scheduledStopPoint -> netexEntitiesIndex.getPassengerStopAssignmentsByStopPointRefIndex().get(scheduledStopPoint.getId()))
                .flatMap(Collection::stream)
                .collect(Collectors.toSet());
    }

    public Collection<ScheduledStopPoint> getScheduledStopPoints() {
        return scheduledStopPoints;
    }

    public Collection<PassengerStopAssignment> getPassengerStopAssignments() {
        return passengerStopAssignments;
    }


}

