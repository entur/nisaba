<?xml version="1.0"?>
<xsl:stylesheet xmlns:xsl="http://www.w3.org/1999/XSL/Transform"
                xmlns:netex="http://www.netex.org.uk/netex"
                version="1.0">

    <xsl:output omit-xml-declaration="no" indent="no"/>
    <xsl:strip-space elements="*"/>

    <!-- copy the sub-tree that contains the service links and discard the other sub-trees -->
    <xsl:template match="*|/">
        <xsl:if test="descendant::netex:serviceLinks">
            <xsl:copy>
                <xsl:apply-templates select="@*|*"/>
            </xsl:copy>
        </xsl:if>
    </xsl:template>

    <!-- discard the version attribute on FromPointRef and ToPointRef since ScheduledStopPoints are defined in another document -->
    <xsl:template
            match="/netex:PublicationDelivery/netex:dataObjects/netex:CompositeFrame/netex:frames/netex:ServiceFrame/netex:serviceLinks/netex:ServiceLink/netex:FromPointRef/@version|/netex:PublicationDelivery/netex:dataObjects/netex:CompositeFrame/netex:frames/netex:ServiceFrame/netex:serviceLinks/netex:ServiceLink/netex:ToPointRef/@version">
    </xsl:template>

    <!-- copy all other attributes by default -->
    <xsl:template match="@*">
        <xsl:copy/>
    </xsl:template>

    <!-- copy all children of the serviceLinks node -->
    <xsl:template match="netex:serviceLinks|netex:serviceLinks//*">
        <xsl:copy>
            <xsl:apply-templates select="@*|*|text()"/>
        </xsl:copy>
    </xsl:template>

</xsl:stylesheet>