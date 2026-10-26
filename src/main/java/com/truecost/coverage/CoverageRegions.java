package com.truecost.coverage;

import com.truecost.persist.CoverageRegionRepository;
import java.util.List;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * The union of seeded coverage rectangles, which is the area where every tolled facility a route
 * could use is either seeded or nonexistent. Regions are read once on first use rather than at
 * boot so the API starts against an unseeded database, and a reseed takes effect on restart.
 */
@Component
public class CoverageRegions {

    private static final Logger log = LoggerFactory.getLogger(CoverageRegions.class);

    private final Supplier<List<CoverageRegion>> source;
    private volatile List<CoverageRegion> regions;

    @Autowired
    public CoverageRegions(CoverageRegionRepository repository) {
        this(() -> repository.findAll().stream()
                .map(r -> new CoverageRegion(r.code(), r.name(), r.minLat(), r.minLng(), r.maxLat(), r.maxLng()))
                .toList());
    }

    public static CoverageRegions of(List<CoverageRegion> fixed) {
        return new CoverageRegions(() -> fixed);
    }

    CoverageRegions(Supplier<List<CoverageRegion>> source) {
        this.source = source;
    }

    public boolean contains(double lat, double lng) {
        for (CoverageRegion region : loaded()) {
            if (region.contains(lat, lng)) {
                return true;
            }
        }
        return false;
    }

    /**
     * An empty table is a deployment error, not an answer. Treating it as no coverage would reject
     * every trip with a misleading message, so it fails loudly and is retried on the next request.
     */
    private List<CoverageRegion> loaded() {
        List<CoverageRegion> current = regions;
        if (current != null) {
            return current;
        }
        List<CoverageRegion> fresh = source.get();
        if (fresh.isEmpty()) {
            log.error("coverage_region table is empty, run the seed loader before serving trip plans");
            throw new IllegalStateException("no coverage regions seeded");
        }
        regions = fresh;
        log.info("coverage regions loaded, count={}", fresh.size());
        return fresh;
    }
}
