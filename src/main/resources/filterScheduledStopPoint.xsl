<?xml version="1.0"?>
<xsl:stylesheet xmlns:xsl="http://www.w3.org/1999/XSL/Transform"
                xmlns:netex="http://www.netex.org.uk/netex"
                version="1.0">

    <xsl:output omit-xml-declaration="no" indent="no"/>
    <xsl:strip-space elements="*"/>

    <!-- copy the sub-tree that contains the scheduled stop points and discard the other sub-trees -->
    <xsl:template match="*|/">
        <xsl:if test="descendant::netex:scheduledStopPoints">
            <xsl:copy>
                <xsl:apply-templates select="@*|*"/>
            </xsl:copy>
        </xsl:if>
    </xsl:template>

    <!-- copy all attributes by default -->
    <xsl:template match="@*">
        <xsl:copy/>
    </xsl:template>

    <!-- copy all children of the scheduledStopPoints node -->
    <xsl:template match="netex:scheduledStopPoints|netex:scheduledStopPoints//*">
        <xsl:copy>
            <xsl:apply-templates select="@*|*|text()"/>
        </xsl:copy>
    </xsl:template>
</xsl:stylesheet>