package no.entur.nisaba.netex;

import no.entur.nisaba.exceptions.NisabaException;
import org.entur.netex.index.api.NetexEntitiesIndex;
import org.rutebanken.netex.model.Authority;
import org.rutebanken.netex.model.Branding;
import org.rutebanken.netex.model.BrandingRefStructure;
import org.rutebanken.netex.model.FlexibleLine;
import org.rutebanken.netex.model.GroupOfLinesRefStructure;
import org.rutebanken.netex.model.Line;
import org.rutebanken.netex.model.Network;
import org.rutebanken.netex.model.Operator;
import org.rutebanken.netex.model.OperatorRefStructure;

import java.util.Optional;

/**
 * NeTEx entities referenced by a Line or FlexibleLine.
 * The entities are looked up either in the current PublicationDelivery or in in the common files.
 * When found in the common files, the NeTEx version is added back to the reference in the current PublicationDelivery.
 */
public class LineReferencedEntities {

    private Line line;
    private FlexibleLine flexibleLine;


    private Operator operator;
    private Branding branding;
    private Network network;
    private Authority authority;


    private LineReferencedEntities() {
    }

    public Line getLine() {
        return line;
    }

    public FlexibleLine getFlexibleLine() {
        return flexibleLine;
    }

    public Operator getOperator() {
        return operator;
    }

    public Network getNetwork() {
        return network;
    }

    public Authority getAuthority() {
        return authority;
    }

    public Branding getBranding() {
        return branding;
    }


    public static class LineReferencedEntitiesBuilder {

        private NetexEntitiesIndex netexCommonEntitiesIndex;
        private NetexEntitiesIndex netexLineEntitiesIndex;

        public LineReferencedEntitiesBuilder withNetexCommonEntitiesIndex(NetexEntitiesIndex netexCommonEntitiesIndex) {
            this.netexCommonEntitiesIndex = netexCommonEntitiesIndex;
            return this;
        }

        public LineReferencedEntitiesBuilder withNetexLineEntitiesIndex(NetexEntitiesIndex netexLineEntitiesIndex) {
            this.netexLineEntitiesIndex = netexLineEntitiesIndex;
            return this;
        }


        public LineReferencedEntities build() {

            LineReferencedEntities lineReferencedEntities = new LineReferencedEntities();

            if (netexLineEntitiesIndex.getLineIndex().getAll().size() > 1) {
                throw new NisabaException("The PublicationDelivery contains more than one line");
            }
            if (netexLineEntitiesIndex.getFlexibleLineIndex().getAll().size() > 1) {
                throw new NisabaException("The PublicationDelivery contains more than one flexible line");
            }
            Optional<Line> optionalLine = netexLineEntitiesIndex.getLineIndex().getAll().stream().findFirst();
            Optional<FlexibleLine> optionalFlexibleLine = netexLineEntitiesIndex.getFlexibleLineIndex().getAll().stream().findFirst();
            if (optionalLine.isPresent() && optionalFlexibleLine.isPresent()) {
                throw new NisabaException("The PublicationDelivery contains both a line and a flexible line");
            }
            if (optionalLine.isEmpty() && optionalFlexibleLine.isEmpty()) {
                throw new NisabaException("The PublicationDelivery contains neither a line nor a flexible line");
            }

            if (optionalLine.isPresent()) {
                Line line = optionalLine.get();
                lineReferencedEntities.line = line;
                lineReferencedEntities.operator = getOperatorAndUpdateVersion(line.getOperatorRef());
                lineReferencedEntities.network = getNetworkAndUpdateVersion(line.getRepresentedByGroupRef());
            } else {
                FlexibleLine flexibleLine = optionalFlexibleLine.get();
                lineReferencedEntities.flexibleLine = flexibleLine;
                lineReferencedEntities.operator = getOperatorAndUpdateVersion(flexibleLine.getOperatorRef());
                lineReferencedEntities.network = getNetworkAndUpdateVersion(flexibleLine.getRepresentedByGroupRef());
            }

            lineReferencedEntities.authority = netexCommonEntitiesIndex.getAuthorityIndex().get(lineReferencedEntities.network.getTransportOrganisationRef().getValue().getRef());

            BrandingRefStructure brandingRef = lineReferencedEntities.operator.getBrandingRef();
            if (brandingRef != null) {
                lineReferencedEntities.branding = netexCommonEntitiesIndex.getBrandingIndex().get(brandingRef.getRef());
            }


            return lineReferencedEntities;
        }

        private Network getNetworkAndUpdateVersion(GroupOfLinesRefStructure networkOrGroupOfLinesRef) {
            Network network = findNetwork(networkOrGroupOfLinesRef.getRef(), netexCommonEntitiesIndex);
            networkOrGroupOfLinesRef.setVersion(network.getVersion());
            return network;
        }

        private Operator getOperatorAndUpdateVersion(OperatorRefStructure operatorRef) {
            Operator operator = netexCommonEntitiesIndex.getOperatorIndex().get(operatorRef.getRef());
            operatorRef.setVersion(operator.getVersion());
            return operator;
        }

        /**
         * Return the network referenced by the <RepresentedByGroupRef>.
         * RepresentedByGroupRef can reference a network either directly or indirectly (through a group of lines)
         *
         * @param networkOrGroupOfLinesRef
         * @param netexCommonEntitiesIndex
         * @return
         */
        private static Network findNetwork(String networkOrGroupOfLinesRef, NetexEntitiesIndex netexCommonEntitiesIndex) {
            Network network = netexCommonEntitiesIndex.getNetworkIndex().get(networkOrGroupOfLinesRef);
            if (network != null) {
                return network;
            } else {
                return netexCommonEntitiesIndex.getNetworkIndex()
                        .getAll()
                        .stream()
                        .filter(n -> n.getGroupsOfLines() != null)
                        .filter(n -> n.getGroupsOfLines()
                                .getGroupOfLines()
                                .stream()
                                .anyMatch(groupOfLine -> groupOfLine.getId().equals(networkOrGroupOfLinesRef)))
                        .findFirst()
                        .orElseThrow();
            }
        }
    }
}

