<?xml version="1.0"?>
<xsl:stylesheet xmlns:xsl="http://www.w3.org/1999/XSL/Transform"
                xmlns:netex="http://www.netex.org.uk/netex"
                version="1.0">

    <xsl:output omit-xml-declaration="no" indent="yes"/>
    <xsl:strip-space elements="*"/>

    <xsl:param name="SERVICE_JOURNEY_ID"/>

    <!-- Copy any branch that contains a ServiceJourney element -->
    <xsl:template match="*|/">
        <xsl:if test="descendant::netex:ServiceJourney" >
            <xsl:copy>
                <xsl:apply-templates select="@*|*" />
            </xsl:copy>
        </xsl:if>
    </xsl:template>
    <!-- Copy all selected attributes -->
    <xsl:template match="@*" >
        <xsl:copy/>
    </xsl:template>
    <!-- Copy the subtree that has a ServiceJourney with the selected id -->
    <xsl:template match="netex:ServiceJourney[@id=$SERVICE_JOURNEY_ID]|netex:ServiceJourney[@id=$SERVICE_JOURNEY_ID]/*" >
        <xsl:copy>
            <xsl:apply-templates select="@*|*|text()" />
        </xsl:copy>
    </xsl:template>
</xsl:stylesheet>