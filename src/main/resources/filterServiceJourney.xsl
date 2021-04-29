<?xml version="1.0"?>
<xsl:stylesheet xmlns:xsl="http://www.w3.org/1999/XSL/Transform"
                xmlns:netex="http://www.netex.org.uk/netex"
                version="1.0">

    <xsl:output omit-xml-declaration="no" indent="yes"/>
    <xsl:strip-space elements="*"/>

    <!-- ID of the selected service journey -->
    <xsl:param name="SERVICE_JOURNEY_ID"/>
    <!-- ID of the journey pattern referred to by the selected service journey -->
    <xsl:variable name="journeyPatternId"
                  select="string(/netex:PublicationDelivery/netex:dataObjects/netex:CompositeFrame/netex:frames/netex:TimetableFrame/netex:vehicleJourneys/netex:ServiceJourney[@id=$SERVICE_JOURNEY_ID]/netex:JourneyPatternRef/@ref)"/>
    <!-- ID of the route referred to by the selected service journey -->
    <xsl:variable name="routeID"
                  select="string(/netex:PublicationDelivery/netex:dataObjects/netex:CompositeFrame/netex:frames/netex:ServiceFrame/netex:journeyPatterns/netex:JourneyPattern[@id = $journeyPatternId]/netex:RouteRef/@ref)"/>

    <!-- Copy all nodes by default -->
    <xsl:template match="*|@*|text()|/">
        <xsl:copy>
            <xsl:apply-templates select="@*|*|text()"/>
        </xsl:copy>
    </xsl:template>

    <!-- Remove other ServiceJourneys than the one whose id is passed as a parameter-->
    <xsl:template
            match="netex:ServiceJourney[@id!=$SERVICE_JOURNEY_ID]">
    </xsl:template>

    <!-- Remove DatedServiceJourneys that are not referring to the selected ServiceJourney -->
    <xsl:template
            match="netex:DatedServiceJourney[/ServiceJourneyRef/@ref != $SERVICE_JOURNEY_ID]">
    </xsl:template>

    <!-- Remove JourneyPatterns that are not referred by the selected ServiceJourney -->
    <xsl:template
            match="netex:JourneyPattern[@id != $journeyPatternId]">
    </xsl:template>

    <!-- Remove Routes that are not referred by the selected ServiceJourney -->
    <xsl:template
            match="netex:Route[@id != $routeID]">
    </xsl:template>

    <!-- Remove ServiceJourneyInterchange that are not referring to the selected ServiceJourney -->
    <xsl:template
            match="netex:ServiceJourneyInterchange[/netex:FromJourneyRef/@ref != $SERVICE_JOURNEY_ID and /netex:ToJourneyRef/@ref != $SERVICE_JOURNEY_ID]">
    </xsl:template>


</xsl:stylesheet>
