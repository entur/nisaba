package no.entur.nisaba.netex;

import org.entur.netex.index.api.NetexEntitiesIndex;
import org.rutebanken.netex.model.PointRefStructure;
import org.rutebanken.netex.model.Route;
import org.rutebanken.netex.model.RoutePoint;

import java.util.Collection;
import java.util.stream.Collectors;

public class RouteReferencedEntities {


    private Collection<RoutePoint> routePoints;

    public RouteReferencedEntities(Route route, NetexEntitiesIndex netexEntitiesIndex) {

        routePoints = route.getPointsInSequence().getPointOnRoute().stream()
                .map(pointOnRoute -> pointOnRoute.getPointRef().getValue())
                .map(routePointRef -> getRoutePointAndUpdateVersion(netexEntitiesIndex, routePointRef))
                .collect(Collectors.toSet());
    }

    private RoutePoint getRoutePointAndUpdateVersion(NetexEntitiesIndex netexEntitiesIndex, PointRefStructure routePointRef) {
        RoutePoint routePoint = netexEntitiesIndex.getRoutePointIndex().get(routePointRef.getRef());
        routePointRef.setVersion(routePoint.getVersion());
        return routePoint;
    }

    public Collection<RoutePoint> getRoutePoints() {
        return routePoints;
    }


}

