package no.entur.nisaba.netex;

import org.entur.netex.index.api.NetexEntitiesIndex;
import org.rutebanken.netex.model.JourneyPattern;
import org.rutebanken.netex.model.NoticeAssignment;
import org.rutebanken.netex.model.Operator;
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


    public JourneyPatternReferencedEntities(JourneyPattern journeyPattern, NetexEntitiesIndex netexCommonEntitiesIndex, NetexEntitiesIndex netexLineEntitiesIndex) {


        scheduledStopPoints = journeyPattern.getPointsInSequence()
                .getPointInJourneyPatternOrStopPointInJourneyPatternOrTimingPointInJourneyPattern()
                .stream()
                .map(pointInLinkSequence -> ((StopPointInJourneyPattern) pointInLinkSequence).getScheduledStopPointRef().getValue().getRef())
                .map(scheduledStopPointId -> netexCommonEntitiesIndex.getScheduledStopPointIndex().get(scheduledStopPointId))
                .collect(Collectors.toSet());

        passengerStopAssignments = scheduledStopPoints.stream()
                .map(scheduledStopPoint -> netexCommonEntitiesIndex.getPassengerStopAssignmentsByStopPointRefIndex().get(scheduledStopPoint.getId()))
                .flatMap(Collection::stream)
                .collect(Collectors.toSet());


        Stream<NoticeAssignment> noticeAssignmentsOnJourneyPattern = netexLineEntitiesIndex
                .getNoticeAssignmentIndex()
                .getAll()
                .stream()
                .filter(noticeAssignment -> noticeAssignment.getNoticedObjectRef().getRef().equals(journeyPattern.getId()));

        Stream<NoticeAssignment> noticeAssignmentsOnStopPoints = netexLineEntitiesIndex.getNoticeAssignmentIndex()
                .getAll()
                .stream()
                .filter(noticeAssignment -> journeyPattern.getPointsInSequence()
                        .getPointInJourneyPatternOrStopPointInJourneyPatternOrTimingPointInJourneyPattern()
                        .stream()
                        .anyMatch(stopPointInJourneyPattern -> noticeAssignment.getNoticedObjectRef()
                                .getRef()
                                .equals(stopPointInJourneyPattern.getId())));


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

