<?xml version="1.0"?>
<xsl:stylesheet xmlns:xsl="http://www.w3.org/1999/XSL/Transform"
                xmlns:netex="http://www.netex.org.uk/netex"
                version="1.0">

    <xsl:output omit-xml-declaration="no" indent="no"/>
    <xsl:strip-space elements="*"/>

    <!-- copy the sub-tree that contains the route points and discard the other sub-trees -->
    <xsl:template match="*|/">
        <xsl:if test="descendant::netex:routePoints">
            <xsl:copy>
                <xsl:apply-templates select="@*|*"/>
            </xsl:copy>
        </xsl:if>
    </xsl:template>

    <!-- discard the version attribute on ProjectedPointRef since ScheduledStopPoints are defined in another document -->
    <xsl:template
            match="/netex:PublicationDelivery/netex:dataObjects/netex:CompositeFrame/netex:frames/netex:ServiceFrame/netex:routePoints/netex:RoutePoint/netex:projections/netex:PointProjection/netex:ProjectedPointRef/@version">
    </xsl:template>

    <!-- copy all other attributes by default -->
    <xsl:template match="@*">
        <xsl:copy/>
    </xsl:template>

    <!-- copy all children of the routePoints node -->
    <xsl:template match="netex:routePoints|netex:routePoints//*">
        <xsl:copy>
            <xsl:apply-templates select="@*|*|text()"/>
        </xsl:copy>
    </xsl:template>

</xsl:stylesheet>