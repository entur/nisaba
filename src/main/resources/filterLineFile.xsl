<?xml version="1.0"?>
<xsl:stylesheet xmlns:xsl="http://www.w3.org/1999/XSL/Transform"
                xmlns:netex="http://www.netex.org.uk/netex"
                version="1.0">

    <xsl:output omit-xml-declaration="no" indent="yes"/>
    <xsl:strip-space elements="*"/>


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
    <!-- copy mandatory first-level elements in the CompositeFrame -->
    <xsl:template match="/netex:PublicationDelivery/netex:dataObjects/netex:CompositeFrame/netex:validityConditions|/netex:PublicationDelivery/netex:dataObjects/netex:CompositeFrame/netex:validityConditions//*">
        <xsl:copy>
            <xsl:apply-templates select="@*|*|text()"/>
        </xsl:copy>
    </xsl:template>
    <xsl:template match="/netex:PublicationDelivery/netex:dataObjects/netex:CompositeFrame/netex:codespaces|/netex:PublicationDelivery/netex:dataObjects/netex:CompositeFrame/netex:codespaces//*">
        <xsl:copy>
            <xsl:apply-templates select="@*|*|text()"/>
        </xsl:copy>
    </xsl:template>
    <xsl:template match="/netex:PublicationDelivery/netex:dataObjects/netex:CompositeFrame/netex:FrameDefaults|/netex:PublicationDelivery/netex:dataObjects/netex:CompositeFrame/netex:FrameDefaults//*">
        <xsl:copy>
            <xsl:apply-templates select="@*|*|text()"/>
        </xsl:copy>
    </xsl:template>

    <!-- copy the sub-tree that contains the line and discard the other sub-trees -->
    <xsl:template match="*|/">
        <xsl:if test="descendant-or-self::netex:lines">
            <xsl:copy>
                <xsl:apply-templates select="@*|*"/>
            </xsl:copy>
        </xsl:if>
    </xsl:template>

    <!-- copy all other attributes by default -->
    <xsl:template match="@*">
        <xsl:copy/>
    </xsl:template>

</xsl:stylesheet>
