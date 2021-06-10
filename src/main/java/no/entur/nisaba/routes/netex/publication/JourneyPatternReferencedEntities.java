package no.entur.nisaba.routes.netex.publication;

import org.entur.netex.index.api.NetexEntitiesIndex;
import org.rutebanken.netex.model.JourneyPattern;
import org.rutebanken.netex.model.NoticeAssignment;
import org.rutebanken.netex.model.PassengerStopAssignment;
import org.rutebanken.netex.model.ScheduledStopPoint;
import org.rutebanken.netex.model.StopPointInJourneyPattern;

import java.util.Collection;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class JourneyPatternReferencedEntities {

    private Collection<ScheduledStopPoint> scheduledStopPoints;
    private Collection<PassengerStopAssignment> passengerStopAssignments;


    private Collection<NoticeAssignment> noticeAssignments;


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


        Stream<NoticeAssignment> noticeAssignmentsOnJourneyPattern = netexEntitiesIndex
                .getNoticeAssignmentIndex()
                .getAll()
                .stream()
                .filter(noticeAssignment -> noticeAssignment.getNoticedObjectRef().getRef().equals(journeyPattern.getId()));

        Stream<NoticeAssignment> noticeAssignmentsOnStopPoints = netexEntitiesIndex.getNoticeAssignmentIndex()
                .getAll()
                .stream()
                .filter(noticeAssignment -> journeyPattern.getPointsInSequence()
                        .getPointInJourneyPatternOrStopPointInJourneyPatternOrTimingPointInJourneyPattern()
                        .stream()
                        .anyMatch(pointInLinkSequence_versionedChildStructure -> noticeAssignment.getNoticedObjectRef()
                                .getRef()
                                .equals(pointInLinkSequence_versionedChildStructure.getId())));


        noticeAssignments = Stream.concat(noticeAssignmentsOnJourneyPattern, noticeAssignmentsOnStopPoints).collect(Collectors.toList());

    }

    public Collection<ScheduledStopPoint> getScheduledStopPoints() {
        return scheduledStopPoints;
    }

    public Collection<PassengerStopAssignment> getPassengerStopAssignments() {
        return passengerStopAssignments;
    }

    public Collection<NoticeAssignment> getNoticeAssignments() {
        return noticeAssignments;
    }

}

