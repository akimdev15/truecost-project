package com.truecost.provider.rental;

import com.truecost.provider.ProviderResult;
import java.util.List;

/**
 * A source of rental car pricing. Implementations never throw, per the ProviderResult contract,
 * a fetch that fails for any reason returns Absent with a reason instead.
 */
public interface RentalQuoteProvider {

    String name();

    ProviderResult<List<RentalQuote>> fetchQuotes(RentalQuoteRequest request);
}
