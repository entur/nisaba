package no.entur.nisaba.netex;

import org.entur.netex.index.api.NetexEntitiesIndex;
import org.rutebanken.netex.model.JourneyPattern;
import org.rutebanken.netex.model.NoticeAssignment;
import org.rutebanken.netex.model.PassengerStopAssignment;
import org.rutebanken.netex.model.ScheduledStopPoint;
import org.rutebanken.netex.model.ScheduledStopPointRefStructure;
import org.rutebanken.netex.model.StopPointInJourneyPattern;

import java.util.Collection;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * NeTEx entities referenced by a JourneyPattern.
 * The entities are looked up either in the current PublicationDelivery or in in the common files.
 * When found in the common files, the NeTEx version is added back to the reference in the current PublicationDelivery.
 */
public class JourneyPatternReferencedEntities {

    private JourneyPattern journeyPattern;
    private Collection<ScheduledStopPoint> scheduledStopPoints;
    private Collection<PassengerStopAssignment> passengerStopAssignments;
    private Collection<NoticeAssignment> noticeAssignments;


    private JourneyPatternReferencedEntities() {
    }

    public JourneyPattern getJourneyPattern() {
        return journeyPattern;
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


    public static class JourneyPatternReferencedEntitiesBuilder {
        private JourneyPattern journeyPattern;
        private NetexEntitiesIndex netexCommonEntitiesIndex;
        private NetexEntitiesIndex netexLineEntitiesIndex;

        public JourneyPatternReferencedEntitiesBuilder withJourneyPattern(JourneyPattern journeyPattern) {
            this.journeyPattern = journeyPattern;
            return this;
        }

        public JourneyPatternReferencedEntitiesBuilder withNetexCommonEntitiesIndex(NetexEntitiesIndex netexCommonEntitiesIndex) {
            this.netexCommonEntitiesIndex = netexCommonEntitiesIndex;
            return this;
        }

        public JourneyPatternReferencedEntitiesBuilder withNetexLineEntitiesIndex(NetexEntitiesIndex netexLineEntitiesIndex) {
            this.netexLineEntitiesIndex = netexLineEntitiesIndex;
            return this;
        }

        public JourneyPatternReferencedEntities build() {
            JourneyPatternReferencedEntities journeyPatternReferencedEntities = new JourneyPatternReferencedEntities();

            journeyPatternReferencedEntities.journeyPattern = JourneyPatternReferencedEntitiesBuilder.this.journeyPattern;

            journeyPatternReferencedEntities.scheduledStopPoints = journeyPattern.getPointsInSequence()
                    .getPointInJourneyPatternOrStopPointInJourneyPatternOrTimingPointInJourneyPattern()
                    .stream()
                    .map(pointInLinkSequence -> ((StopPointInJourneyPattern) pointInLinkSequence).getScheduledStopPointRef().getValue())
                    .map(scheduledStopPointRef -> getScheduledStopPointAndUpdateVersion(netexCommonEntitiesIndex, scheduledStopPointRef))
                    .collect(Collectors.toSet());

            journeyPatternReferencedEntities.passengerStopAssignments = journeyPatternReferencedEntities.scheduledStopPoints.stream()
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

            journeyPatternReferencedEntities.noticeAssignments = Stream.concat(noticeAssignmentsOnJourneyPattern, noticeAssignmentsOnStopPoints).collect(Collectors.toList());

            return journeyPatternReferencedEntities;
        }

        private static ScheduledStopPoint getScheduledStopPointAndUpdateVersion(NetexEntitiesIndex netexCommonEntitiesIndex, ScheduledStopPointRefStructure scheduledStopPointRef) {
            ScheduledStopPoint scheduledStopPoint = netexCommonEntitiesIndex.getScheduledStopPointIndex().get(scheduledStopPointRef.getRef());
            scheduledStopPointRef.setVersion(scheduledStopPoint.getVersion());
            return scheduledStopPoint;
        }

    }


}

