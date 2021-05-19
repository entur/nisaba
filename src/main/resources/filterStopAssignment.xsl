<?xml version="1.0"?>
<xsl:stylesheet xmlns:xsl="http://www.w3.org/1999/XSL/Transform"
                xmlns:netex="http://www.netex.org.uk/netex"
                version="1.0">

    <xsl:output omit-xml-declaration="no" indent="no"/>
    <xsl:strip-space elements="*"/>

    <xsl:param name="SPLIT_LOWER_BOUND"/>
    <xsl:param name="SPLIT_UPPER_BOUND"/>

    <!-- copy mandatory first-level elements in the PublicationDelivery -->
    <xsl:template match="/netex:PublicationDelivery/netex:PublicationTimestamp|/netex:PublicationDelivery/netex:PublicationTimestamp//*">
        <xsl:copy>
            <xsl:apply-templates select="@*|*|text()"/>
        </xsl:copy>
    </xsl:template>
    <xsl:template match="/netex:PublicationDelivery/netex:ParticipantRef|/netex:PublicationDelivery/netex:ParticipantRef//*">
        <xsl:copy>
            <xsl:apply-templates select="@*|*|text()"/>
        </xsl:copy>
    </xsl:template>
    <xsl:template match="/netex:PublicationDelivery/netex:Description|/netex:PublicationDelivery/netex:Description//*">
        <xsl:copy>
            <xsl:apply-templates select="@*|*|text()"/>
        </xsl:copy>
    </xsl:template>

    <!-- copy the sub-tree that contains the stop assigmments and discard the other sub-trees -->
    <xsl:template match="*|/">
        <xsl:if test="descendant-or-self::netex:stopAssignments">
            <xsl:copy>
                <xsl:apply-templates select="@*|*"/>
            </xsl:copy>
        </xsl:if>
    </xsl:template>

    <!-- discard the version attribute on ScheduledStopPointRef since ScheduledStopPoints are defined in another document -->
    <xsl:template
            match="/netex:PublicationDelivery/netex:dataObjects/netex:CompositeFrame/netex:frames/netex:ServiceFrame/netex:stopAssignments/netex:PassengerStopAssignment/netex:ScheduledStopPointRef/@version">
    </xsl:template>

    <!-- copy all other attributes by default -->
    <xsl:template match="@*">
        <xsl:copy/>
    </xsl:template>

    <!-- discard the PassengerStopAssignment that fall outside of the current range -->
    <xsl:template match="netex:PassengerStopAssignment[position() &lt; $SPLIT_LOWER_BOUND  or position() > $SPLIT_UPPER_BOUND ]">
    </xsl:template>

    <!-- copy all other  PassengerStopAssignment -->
    <xsl:template match="netex:PassengerStopAssignment|netex:PassengerStopAssignment//*">
        <xsl:copy>
            <xsl:apply-templates select="@*|*|text()"/>
        </xsl:copy>
    </xsl:template>

</xsl:stylesheet>