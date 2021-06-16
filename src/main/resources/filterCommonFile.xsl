<?xml version="1.0" encoding="UTF-8"?>
<xsl:stylesheet xmlns:xsl="http://www.w3.org/1999/XSL/Transform"
                xmlns:netex="http://www.netex.org.uk/netex"
                version="1.0">

    <xsl:output omit-xml-declaration="no" indent="no"/>
    <xsl:strip-space elements="*"/>

    <!-- Copy all nodes by default -->
    <xsl:template match="*|@*|text()|/">
        <xsl:copy>
            <xsl:apply-templates select="@*|*|text()"/>
        </xsl:copy>
    </xsl:template>

    <!-- Remove scheduled stop points -->
    <xsl:template
            match="netex:scheduledStopPoints">
    </xsl:template>

    <!-- Remove stop assignments -->
    <xsl:template
            match="netex:stopAssignments">
    </xsl:template>


    <!-- Remove route points -->
    <xsl:template
            match="netex:routePoints">
    </xsl:template>

    <!-- Remove service links -->
    <xsl:template
            match="netex:serviceLinks">
    </xsl:template>

    <!-- Remove ServiceCalendarFrame -->
    <xsl:template
            match="netex:ServiceCalendarFrame">
    </xsl:template>

</xsl:stylesheet>
