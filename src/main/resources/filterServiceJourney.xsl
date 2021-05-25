<?xml version="1.0" encoding="UTF-8"?>
<xsl:stylesheet xmlns:xsl="http://www.w3.org/1999/XSL/Transform"
                xmlns:netex="http://www.netex.org.uk/netex"
                version="1.0">

    <xsl:output omit-xml-declaration="no" indent="no"/>
    <xsl:strip-space elements="*"/>

    <!-- ID of the selected service journey -->
    <xsl:param name="SERVICE_JOURNEY_ID"/>

    <!-- ID of the journey pattern of the selected service journey -->
    <xsl:variable name="journeyPatternId"
                  select="string(/netex:PublicationDelivery/netex:dataObjects/netex:CompositeFrame/netex:frames/netex:TimetableFrame/netex:vehicleJourneys/netex:ServiceJourney[@id=$SERVICE_JOURNEY_ID]/netex:JourneyPatternRef/@ref)"/>

    <!-- ID of the route of the selected service journey -->
    <xsl:variable name="routeId"
                  select="string(/netex:PublicationDelivery/netex:dataObjects/netex:CompositeFrame/netex:frames/netex:ServiceFrame/netex:journeyPatterns/netex:JourneyPattern[@id = $journeyPatternId]/netex:RouteRef/@ref)"/>

    <!-- ID of the (unique) line in the file  -->
    <xsl:variable name="lineId"
                  select="string(/netex:PublicationDelivery/netex:dataObjects/netex:CompositeFrame/netex:frames/netex:ServiceFrame/netex:lines/netex:Line/@id)"/>

    <!-- Concatenated list of ids of the TimetabledPassingTime of the selected service journey -->
    <xsl:variable name="timetablePassingTimeIds"
                  select="string-join(/netex:PublicationDelivery/netex:dataObjects/netex:CompositeFrame/netex:frames/netex:TimetableFrame/netex:vehicleJourneys/netex:ServiceJourney[@id=$SERVICE_JOURNEY_ID]/netex:passingTimes/netex:TimetabledPassingTime/@id, ' ')"/>

    <!-- Concatenated list of ids of the stop points of the selected service journey -->
    <xsl:variable name="stopPointsIds"
                  select="string-join(/netex:PublicationDelivery/netex:dataObjects/netex:CompositeFrame/netex:frames/netex:ServiceFrame/netex:journeyPatterns/netex:JourneyPattern[@id = $journeyPatternId]/netex:pointsInSequence/netex:StopPointInJourneyPattern/@id, ' ')"/>


    <!-- Concatenated list of ids for noticed elements related to the selected service journey (the service journey + the journey pattern + the line + the TimetabledPassingTimes + the stop points) -->
    <xsl:variable name="noticedElementIds"
                  select="concat($SERVICE_JOURNEY_ID, ' ', $journeyPatternId, ' ', $lineId, ' ', $timetablePassingTimeIds, ' ', $stopPointsIds)"/>


    <!-- Copy all nodes by default -->
    <xsl:template match="*|@*|text()|/">
        <xsl:copy>
            <xsl:apply-templates select="@*|*|text()"/>
        </xsl:copy>
    </xsl:template>

    <!-- Remove other ServiceJourneys than the selected ServiceJourney -->
    <xsl:template
            match="netex:ServiceJourney[@id!=$SERVICE_JOURNEY_ID]">
    </xsl:template>

    <!-- Remove DatedServiceJourneys that are not referring to the selected ServiceJourney -->
    <xsl:template
            match="netex:DatedServiceJourney[netex:ServiceJourneyRef/@ref != $SERVICE_JOURNEY_ID]">
    </xsl:template>

    <!-- Remove JourneyPatterns that are not referred by the selected ServiceJourney -->
    <xsl:template
            match="netex:JourneyPattern[@id != $journeyPatternId]">
    </xsl:template>

    <!-- Remove Routes that are not referred by the selected ServiceJourney -->
    <xsl:template
            match="netex:Route[@id != $routeId]">
    </xsl:template>

    <!-- Remove ServiceJourneyInterchanges that are not referring to the selected ServiceJourney -->
    <xsl:template
            match="netex:ServiceJourneyInterchange[netex:FromJourneyRef/@ref != $SERVICE_JOURNEY_ID and netex:ToJourneyRef/@ref != $SERVICE_JOURNEY_ID]">
    </xsl:template>

    <!-- Remove NoticeAssignments that are not related to the selected ServiceJourney -->
    <xsl:template
            match="netex:NoticeAssignment[not(contains($noticedElementIds, netex:NoticedObjectRef/@ref))]">
    </xsl:template>

</xsl:stylesheet>
