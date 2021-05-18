<?xml version="1.0"?>
<xsl:stylesheet xmlns:xsl="http://www.w3.org/1999/XSL/Transform"
                xmlns:netex="http://www.netex.org.uk/netex"
                version="1.0">

    <xsl:output omit-xml-declaration="no" indent="no"/>
    <xsl:strip-space elements="*"/>

    <xsl:param name="SPLIT_LOWER_BOUND"/>
    <xsl:param name="SPLIT_UPPER_BOUND"/>

    <!-- copy the sub-tree that contains the scheduled stop points and discard the other sub-trees -->
    <xsl:template match="*|/">
        <xsl:if test="descendant-or-self::netex:scheduledStopPoints">
            <xsl:copy>
                <xsl:apply-templates select="@*|*"/>
            </xsl:copy>
        </xsl:if>
    </xsl:template>

    <!-- copy all attributes by default -->
    <xsl:template match="@*">
        <xsl:copy/>
    </xsl:template>

    <!-- discard the ScheduledStopPoints that fall outside of the current range -->
    <xsl:template match="netex:ScheduledStopPoint[position() &lt; $SPLIT_LOWER_BOUND  or position() > $SPLIT_UPPER_BOUND ]">
    </xsl:template>

    <!-- copy all other ScheduledStopPoints -->
    <xsl:template match="netex:ScheduledStopPoint|netex:ScheduledStopPoint//*">
        <xsl:copy>
            <xsl:apply-templates select="@*|*|text()"/>
        </xsl:copy>
    </xsl:template>
</xsl:stylesheet>