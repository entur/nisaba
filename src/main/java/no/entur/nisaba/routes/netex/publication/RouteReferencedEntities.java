package no.entur.nisaba.routes.netex.publication;

import org.entur.netex.index.api.NetexEntitiesIndex;
import org.rutebanken.netex.model.Route;
import org.rutebanken.netex.model.RoutePoint;

import java.util.Collection;
import java.util.stream.Collectors;

public class RouteReferencedEntities {


    private Collection<RoutePoint> routePoints;

    public RouteReferencedEntities(Route route, NetexEntitiesIndex netexEntitiesIndex) {

        routePoints = route.getPointsInSequence().getPointOnRoute().stream()
                .map(pointOnRoute -> pointOnRoute.getPointRef().getValue().getRef())
                .map(routePointId -> netexEntitiesIndex.getRoutePointIndex().get(routePointId))
                .collect(Collectors.toSet());
    }

    public Collection<RoutePoint> getRoutePoints() {
        return routePoints;
    }


}

