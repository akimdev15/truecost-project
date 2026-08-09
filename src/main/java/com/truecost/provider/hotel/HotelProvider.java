package com.truecost.provider.hotel;

import com.truecost.provider.ProviderResult;
import java.util.List;

/**
 * A source of hotel pricing. Implementations never throw, per the ProviderResult contract, a
 * fetch that fails for any reason returns Absent with a reason instead.
 */
public interface HotelProvider {

    String name();

    ProviderResult<List<HotelOffer>> fetchOffers(HotelRequest request);
}
